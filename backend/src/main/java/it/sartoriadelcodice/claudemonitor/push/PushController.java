package it.sartoriadelcodice.claudemonitor.push;

import java.time.Clock;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Registrazione delle subscription Web Push dei dispositivi. */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushSubscriptionRepository subscriptions;
    private final MonitorProperties properties;
    private final Clock clock;

    public PushController(PushSubscriptionRepository subscriptions, MonitorProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Chiave pubblica VAPID necessaria al browser per creare la subscription. */
    @GetMapping("/public-key")
    public PublicKeyResponse publicKey() {
        var push = properties.push();
        return new PublicKeyResponse(push.enabled(), push.enabled() ? push.vapidPublicKey() : null);
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void subscribe(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SubscriptionRequest request,
                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        if (!PushEndpointValidator.isAllowed(request.endpoint())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint push non valido");
        }
        String agent = userAgent != null && userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent;
        subscriptions.findByEndpoint(request.endpoint()).ifPresentOrElse(
                existing -> existing.update(user.id(), request.keys().p256dh(), request.keys().auth(), agent),
                () -> subscriptions.save(new PushSubscription(user.id(), request.endpoint(), request.keys().p256dh(),
                        request.keys().auth(), agent, clock.instant())));
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unsubscribe(@AuthenticationPrincipal AuthUser user, @RequestParam String endpoint) {
        subscriptions.deleteByEndpointAndUserId(endpoint, user.id());
    }

    public record PublicKeyResponse(boolean enabled, String publicKey) {
    }

    /** Stessa forma di PushSubscription.toJSON() del browser. */
    public record SubscriptionRequest(@NotBlank String endpoint, @NotNull @Valid Keys keys) {

        public record Keys(@NotBlank String p256dh, @NotBlank String auth) {
        }
    }
}
