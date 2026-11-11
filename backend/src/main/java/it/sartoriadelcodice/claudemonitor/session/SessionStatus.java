package it.sartoriadelcodice.claudemonitor.session;

/** Stati possibili di una sessione Claude Code. */
public enum SessionStatus {
    /** Aperta, in attesa del primo prompt. */
    IDLE,
    /** Claude sta lavorando. */
    WORKING,
    /** Serve un intervento: permesso o input richiesto. */
    NEEDS_ATTENTION,
    /** Risposta terminata, tocca all'utente. */
    WAITING_INPUT,
    /** Risposta terminata ma con task in background o risvegli pianificati: ripartirà da sola. */
    BACKGROUND,
    /** Sessione chiusa. */
    ENDED,
    /** Nessun evento da troppo tempo. */
    STALE;

    /** Stati che richiedono l'attenzione dell'utente (suono e notifica push). */
    public boolean requiresAttention() {
        return this == NEEDS_ATTENTION || this == WAITING_INPUT;
    }
}
