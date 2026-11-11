package it.sartoriadelcodice.claudemonitor.session;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Claude Code session; the id is the session_id sent by the hooks. */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    private String id;

    /** Owner: the user of the hook key that sent the events. */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String machine;

    @Column(nullable = false)
    private String project;

    private String cwd;

    private String title;

    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(name = "status_since", nullable = false)
    private Instant statusSince;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "last_notification_message")
    private String lastNotificationMessage;

    @Column(name = "last_notification_type")
    private String lastNotificationType;

    @Column(name = "seen_at")
    private Instant seenAt;

    /** Claude Code Remote Control identifier (session_...), null if it is not active. */
    @Column(name = "remote_session_id")
    private String remoteSessionId;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Session() {
    }

    public Session(String id, Long userId, String machine, String project, Instant now) {
        this.id = id;
        this.userId = userId;
        this.machine = machine;
        this.project = project;
        this.status = SessionStatus.IDLE;
        this.statusSince = now;
        this.lastEventAt = now;
        this.createdAt = now;
    }

    /** Changes the status and clears the "seen" flag; returns false if the status was already that one. */
    public boolean changeStatus(SessionStatus newStatus, Instant now) {
        if (this.status == newStatus) {
            return false;
        }
        this.status = newStatus;
        this.statusSince = now;
        this.seenAt = null;
        return true;
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getMachine() {
        return machine;
    }

    public void setMachine(String machine) {
        this.machine = machine;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getStatusSince() {
        return statusSince;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public String getLastNotificationMessage() {
        return lastNotificationMessage;
    }

    public void setLastNotificationMessage(String lastNotificationMessage) {
        this.lastNotificationMessage = lastNotificationMessage;
    }

    public String getLastNotificationType() {
        return lastNotificationType;
    }

    public void setLastNotificationType(String lastNotificationType) {
        this.lastNotificationType = lastNotificationType;
    }

    public Instant getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(Instant seenAt) {
        this.seenAt = seenAt;
    }

    public String getRemoteSessionId() {
        return remoteSessionId;
    }

    public void setRemoteSessionId(String remoteSessionId) {
        this.remoteSessionId = remoteSessionId;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
