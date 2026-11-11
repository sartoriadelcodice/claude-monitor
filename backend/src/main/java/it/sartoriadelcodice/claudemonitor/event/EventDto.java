package it.sartoriadelcodice.claudemonitor.event;

import java.time.Instant;

import it.sartoriadelcodice.claudemonitor.session.SessionStatus;

/** History entry exposed to the frontend. */
public record EventDto(Long id, String eventName, String notificationType, String message,
                       SessionStatus statusAfter, Instant receivedAt) {

    public static EventDto from(SessionEvent e) {
        return new EventDto(e.getId(), e.getEventName(), e.getNotificationType(), e.getMessage(),
                e.getStatusAfter(), e.getReceivedAt());
    }
}
