package it.sartoriadelcodice.claudemonitor.session;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Pure mapping logic from hook event to session status.
 * Returns an empty Optional when the event must not change the status.
 */
@Component
public class SessionStateMapper {

    /** Notification types that really require action. */
    private static final Set<String> ATTENTION_NOTIFICATIONS = Set.of("permission_prompt", "elicitation_dialog");

    /**
     * @param eventName        value of hook_event_name
     * @param notificationType notification_type (Notification only, may be missing)
     * @param source           source of SessionStart (startup, resume, clear, compact, fork)
     * @param current          current status, null if the session is new
     */
    public Optional<SessionStatus> map(String eventName, String notificationType, String source,
                                       SessionStatus current) {
        return map(eventName, notificationType, source, false, false, current);
    }

    /**
     * @param backgroundWork true if background tasks or scheduled wake-ups remain at Stop
     * @param subagent       true if the event comes from a subagent (agent_id present)
     */
    public Optional<SessionStatus> map(String eventName, String notificationType, String source,
                                       boolean backgroundWork, boolean subagent, SessionStatus current) {
        if (eventName == null) {
            return Optional.empty();
        }
        return switch (eventName) {
            case "SessionStart" -> mapSessionStart(source, current);
            case "UserPromptSubmit" -> Optional.of(SessionStatus.WORKING);
            case "Notification" -> mapNotification(notificationType, current);
            // With work still running underneath, the session will resume on its own: it is not the user's turn
            case "Stop" -> Optional.of(backgroundWork ? SessionStatus.BACKGROUND : SessionStatus.WAITING_INPUT);
            case "PostToolUse" -> mapPostToolUse(subagent, current);
            case "SessionEnd" -> Optional.of(SessionStatus.ENDED);
            default -> Optional.empty();
        };
    }

    private Optional<SessionStatus> mapSessionStart(String source, SessionStatus current) {
        // Compaction happens mid-work: it must not bring the session back to IDLE
        if ("compact".equals(source) && current != null && current != SessionStatus.ENDED) {
            return Optional.empty();
        }
        return Optional.of(SessionStatus.IDLE);
    }

    private Optional<SessionStatus> mapNotification(String type, SessionStatus current) {
        // Claude Code versions without notification_type: follow the basic rule
        if (type == null || type.isBlank() || ATTENTION_NOTIFICATIONS.contains(type)) {
            return Optional.of(SessionStatus.NEEDS_ATTENTION);
        }
        // idle_prompt: Claude has been waiting for input for a while. Useful only if the Stop was lost
        if ("idle_prompt".equals(type) && current == SessionStatus.WORKING) {
            return Optional.of(SessionStatus.WAITING_INPUT);
        }
        // The other types (auth_success, agent_completed, ...) only stay in the history
        return Optional.empty();
    }

    private Optional<SessionStatus> mapPostToolUse(boolean subagent, SessionStatus current) {
        // A tool that has just run means Claude has resumed, for example after a permission was granted.
        // WAITING_INPUT is left alone: hooks are asynchronous and a PostToolUse can arrive after the Stop.
        if (current == null || current == SessionStatus.NEEDS_ATTENTION
                || current == SessionStatus.STALE || current == SessionStatus.IDLE) {
            return Optional.of(SessionStatus.WORKING);
        }
        // Session paused with background tasks: a tool from the main conversation means Claude has been
        // woken up. Tools run by background subagents do not count.
        if (current == SessionStatus.BACKGROUND && !subagent) {
            return Optional.of(SessionStatus.WORKING);
        }
        return Optional.empty();
    }
}
