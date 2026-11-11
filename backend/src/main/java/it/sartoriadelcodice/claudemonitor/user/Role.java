package it.sartoriadelcodice.claudemonitor.user;

public enum Role {
    /** Sees their own sessions and manages their own hook keys. */
    USER,
    /** Like USER, plus user management. */
    ADMIN
}
