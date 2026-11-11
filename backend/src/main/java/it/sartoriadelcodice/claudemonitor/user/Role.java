package it.sartoriadelcodice.claudemonitor.user;

public enum Role {
    /** Vede le proprie sessioni e gestisce le proprie chiavi hook. */
    USER,
    /** Come USER, più la gestione degli utenti. */
    ADMIN
}
