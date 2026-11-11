package it.sartoriadelcodice.claudemonitor.session;

/** Possible statuses of a Claude Code session. */
public enum SessionStatus {
    /** Open, waiting for the first prompt. */
    IDLE,
    /** Claude is working. */
    WORKING,
    /** Action needed: permission or input required. */
    NEEDS_ATTENTION,
    /** Response finished, it is the user's turn. */
    WAITING_INPUT,
    /** Response finished but with background tasks or scheduled wake-ups: it will resume on its own. */
    BACKGROUND,
    /** Session ended. */
    ENDED,
    /** No events for too long. */
    STALE;

    /** Statuses that require the user's attention (sound and push notification). */
    public boolean requiresAttention() {
        return this == NEEDS_ATTENTION || this == WAITING_INPUT;
    }
}
