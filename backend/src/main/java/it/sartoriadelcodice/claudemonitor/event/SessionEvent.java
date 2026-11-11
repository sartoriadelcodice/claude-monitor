package it.sartoriadelcodice.claudemonitor.event;

import java.time.Instant;

import it.sartoriadelcodice.claudemonitor.session.SessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Hook event recorded in a session's history. */
@Entity
@Table(name = "session_events")
public class SessionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column(name = "notification_type")
    private String notificationType;

    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", nullable = false)
    private SessionStatus statusAfter;

    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected SessionEvent() {
    }

    public SessionEvent(String sessionId, String eventName, String notificationType, String message,
                        SessionStatus statusAfter, String payload, Instant receivedAt) {
        this.sessionId = sessionId;
        this.eventName = eventName;
        this.notificationType = notificationType;
        this.message = message;
        this.statusAfter = statusAfter;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEventName() {
        return eventName;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public String getMessage() {
        return message;
    }

    public SessionStatus getStatusAfter() {
        return statusAfter;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
