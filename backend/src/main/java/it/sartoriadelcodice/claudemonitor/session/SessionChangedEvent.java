package it.sartoriadelcodice.claudemonitor.session;

/**
 * Evento applicativo pubblicato quando una sessione viene creata o modificata.
 * Viene consegnato dopo il commit a SSE e Web Push.
 */
public record SessionChangedEvent(Long userId, SessionDto session, boolean statusChanged) {
}
