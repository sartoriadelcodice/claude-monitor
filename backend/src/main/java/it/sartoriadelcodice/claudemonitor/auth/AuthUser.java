package it.sartoriadelcodice.claudemonitor.auth;

import it.sartoriadelcodice.claudemonitor.user.Role;

/** Authenticated user of the current request (from a JWT or a hook key). */
public record AuthUser(Long id, String username, Role role) {
}
