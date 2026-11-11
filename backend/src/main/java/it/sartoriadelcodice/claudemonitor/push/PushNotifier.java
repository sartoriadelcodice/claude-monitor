package it.sartoriadelcodice.claudemonitor.push;

import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionStatus;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Invio delle notifiche Web Push (VAPID) quando una sessione richiede attenzione. */
@Service
public class PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    private final PushSubscriptionRepository subscriptions;
    private final ObjectMapper objectMapper;
    private final PushService pushService;

    public PushNotifier(PushSubscriptionRepository subscriptions, ObjectMapper objectMapper,
                        MonitorProperties properties) {
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
        this.pushService = createPushService(properties.push());
    }

    private static PushService createPushService(MonitorProperties.Push push) {
        if (!push.enabled()) {
            log.warn("Chiavi VAPID non configurate: notifiche push disattivate");
            return null;
        }
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            return new PushService(push.vapidPublicKey(), push.vapidPrivateKey(), push.vapidSubject());
        } catch (GeneralSecurityException | RuntimeException e) {
            log.error("Chiavi VAPID non valide: notifiche push disattivate", e);
            return null;
        }
    }

    /** Invia la notifica ai dispositivi del proprietario. Asincrono: non rallenta la ricezione degli eventi. */
    @Async
    public void notifyAttention(Long userId, SessionDto session) {
        if (pushService == null) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(buildPayload(session));
        } catch (JsonProcessingException e) {
            log.error("Impossibile serializzare la notifica push", e);
            return;
        }
        for (PushSubscription subscription : subscriptions.findByUserId(userId)) {
            send(subscription, payload);
        }
    }

    /** Payload nel formato atteso dal service worker di Angular (chiave "notification"). */
    static Map<String, Object> buildPayload(SessionDto session) {
        boolean needsAttention = session.status() == SessionStatus.NEEDS_ATTENTION;
        String title = (needsAttention ? "Richiede attenzione · " : "Risposta terminata · ") + session.project();
        String detail = needsAttention && session.lastNotificationMessage() != null
                ? session.lastNotificationMessage()
                : (needsAttention ? "Permesso o input richiesto" : "Claude ha finito, tocca a te");
        return Map.of("notification", Map.of(
                "title", title,
                "body", detail + " (" + session.machine() + ")",
                "icon", "icons/icon-192x192.png",
                "badge", "icons/icon-96x96.png",
                // Stesso tag per sessione: una nuova notifica sostituisce la precedente
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
            var response = pushService.send(notification, Encoding.AES128GCM);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                // Subscription scaduta o revocata dal dispositivo
                subscriptions.deleteById(subscription.getId());
            } else if (status >= 400) {
                log.warn("Invio push fallito con stato {}", status);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Invio push fallito: {}", e.getMessage());
        }
    }
}
