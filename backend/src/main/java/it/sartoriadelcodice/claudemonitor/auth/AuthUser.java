package it.sartoriadelcodice.claudemonitor.auth;

import it.sartoriadelcodice.claudemonitor.user.Role;

/** Utente autenticato della richiesta corrente (da JWT o da chiave hook). */
public record AuthUser(Long id, String username, Role role) {
}
