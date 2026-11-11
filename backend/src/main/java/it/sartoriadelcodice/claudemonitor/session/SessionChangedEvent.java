package it.sartoriadelcodice.claudemonitor.session;

/**
 * Application event published when a session is created or modified.
 * It is delivered to SSE and Web Push after the commit.
 */
public record SessionChangedEvent(Long userId, SessionDto session, boolean statusChanged) {
}
