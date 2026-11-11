package it.sartoriadelcodice.claudemonitor.push;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A device's Web Push subscription. */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, unique = true)
    private String endpoint;

    @Column(nullable = false)
    private String p256dh;

    @Column(nullable = false)
    private String auth;

    @Column(name = "user_agent")
    private String userAgent;

    /** Language of the notifications on this device, one of {@link PushNotifier#LANGUAGES}. */
    @Column(nullable = false, length = 10)
    private String language;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PushSubscription() {
    }

    public PushSubscription(Long userId, String endpoint, String p256dh, String auth, String userAgent,
                            String language, Instant createdAt) {
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent;
        this.language = language;
        this.createdAt = createdAt;
    }

    /** Updates keys and language; the device passes to the user who has just subscribed. */
    public void update(Long userId, String p256dh, String auth, String userAgent, String language) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent;
        this.language = language;
    }

    public Long getId() {
        return id;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public String getLanguage() {
        return language;
    }
}
