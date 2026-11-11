package it.sartoriadelcodice.claudemonitor.session;

import java.time.Instant;

/** Rappresentazione di una sessione esposta al frontend (REST e SSE). */
public record SessionDto(
        String id,
        String machine,
        String project,
        String cwd,
        String title,
        String model,
        SessionStatus status,
        Instant statusSince,
        Instant lastEventAt,
        String lastNotificationMessage,
        String lastNotificationType,
        Instant seenAt,
        boolean archived,
        Instant createdAt,
        String remoteSessionId) {

    public static SessionDto from(Session s) {
        return new SessionDto(s.getId(), s.getMachine(), s.getProject(), s.getCwd(), s.getTitle(), s.getModel(),
                s.getStatus(),
                s.getStatusSince(), s.getLastEventAt(), s.getLastNotificationMessage(), s.getLastNotificationType(),
                s.getSeenAt(), s.isArchived(), s.getCreatedAt(), s.getRemoteSessionId());
    }
}
