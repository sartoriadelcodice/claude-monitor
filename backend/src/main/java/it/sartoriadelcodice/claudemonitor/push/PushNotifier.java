package it.sartoriadelcodice.claudemonitor.push;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionStatus;
import nl.martijndwars.webpush.Notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Sends Web Push (VAPID) notifications when a session needs attention. */
@Service
public class PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    /** Languages of the notifications: the first one is the default. Texts in messages*.properties. */
    public static final List<String> LANGUAGES = List.of("en", "it");

    private final PushSubscriptionRepository subscriptions;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;
    /** null when the VAPID keys are missing or invalid: push notifications are disabled. */
    private final WebPushClient client;

    public PushNotifier(PushSubscriptionRepository subscriptions, ObjectMapper objectMapper, MessageSource messages,
                        MonitorProperties properties) {
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.client = createClient(properties.push());
    }

    /** The requested language if notifications support it, otherwise the default one. */
    public static String supportedLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return LANGUAGES.contains(normalized) ? normalized : LANGUAGES.getFirst();
    }

    private static WebPushClient createClient(MonitorProperties.Push push) {
        if (!push.enabled()) {
            log.warn("VAPID keys not configured: push notifications disabled");
            return null;
        }
        try {
            return new WebPushClient(push.vapidPublicKey(), push.vapidPrivateKey(), push.vapidSubject());
        } catch (GeneralSecurityException | RuntimeException e) {
            log.error("Invalid VAPID keys: push notifications disabled", e);
            return null;
        }
    }

    /** Sends the notification to the owner's devices. Asynchronous: it does not slow down event reception. */
    @Async
    public void notifyAttention(Long userId, SessionDto session) {
        if (client == null) {
            return;
        }
        // Each device gets the notification in its own language: one payload per language
        Map<String, String> payloads = new HashMap<>();
        for (PushSubscription subscription : subscriptions.findByUserId(userId)) {
            String language = supportedLanguage(subscription.getLanguage());
            String payload = payloads.computeIfAbsent(language, l -> serialize(session, l));
            if (payload != null) {
                send(subscription, payload);
            }
        }
    }

    private String serialize(SessionDto session, String language) {
        try {
            return objectMapper.writeValueAsString(buildPayload(session, messages, Locale.forLanguageTag(language)));
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize the push notification", e);
            return null;
        }
    }

    /** Payload in the format expected by the Angular service worker ("notification" key). */
    static Map<String, Object> buildPayload(SessionDto session, MessageSource messages, Locale locale) {
        boolean needsAttention = session.status() == SessionStatus.NEEDS_ATTENTION;
        String title = messages.getMessage(needsAttention ? "push.title.attention" : "push.title.waiting",
                new Object[] {session.project()}, locale);
        // The message of a permission request comes from Claude Code and is shown as it is
        String detail = needsAttention && session.lastNotificationMessage() != null
                ? session.lastNotificationMessage()
                : messages.getMessage(needsAttention ? "push.body.attention" : "push.body.waiting", null, locale);
        return Map.of("notification", Map.of(
                "title", title,
                "body", messages.getMessage("push.body.machine", new Object[] {detail, session.machine()}, locale),
                "icon", "icons/icon-192x192.png",
                "badge", "icons/icon-96x96.png",
                // Same tag per session: a new notification replaces the previous one
                "tag", session.id(),
                "renotify", true,
                "data", Map.of(
                        "sessionId", session.id(),
                        "onActionClick", Map.of(
                                "default", Map.of("operation", "navigateLastFocusedOrOpen", "url", "/")))));
    }

    private void send(PushSubscription subscription, String payload) {
        try {
            var notification = new Notification(
                    subscription.getEndpoint(), subscription.getP256dh(), subscription.getAuth(), payload);
            int status = client.send(notification);
            if (status == 404 || status == 410) {
                // Subscription expired or revoked by the device
                subscriptions.deleteById(subscription.getId());
            } else if (status >= 400) {
                log.warn("Push delivery failed with status {}", status);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Push delivery failed: {}", e.getMessage());
        }
    }
}
