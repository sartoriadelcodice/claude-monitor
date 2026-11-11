package it.sartoriadelcodice.claudemonitor.session;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Logica pura di mappatura evento hook → stato della sessione.
 * Restituisce un Optional vuoto quando l'evento non deve cambiare lo stato.
 */
@Component
public class SessionStateMapper {

    /** Tipi di Notification che richiedono davvero un intervento. */
    private static final Set<String> ATTENTION_NOTIFICATIONS = Set.of("permission_prompt", "elicitation_dialog");

    /**
     * @param eventName        valore di hook_event_name
     * @param notificationType notification_type (solo per Notification, può mancare)
     * @param source           source di SessionStart (startup, resume, clear, compact, fork)
     * @param current          stato attuale, null se la sessione è nuova
     */
    public Optional<SessionStatus> map(String eventName, String notificationType, String source,
                                       SessionStatus current) {
        return map(eventName, notificationType, source, false, false, current);
    }

    /**
     * @param backgroundWork vero se allo Stop restano task in background o risvegli pianificati
     * @param subagent       vero se l'evento arriva da un subagent (agent_id presente)
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
            // Con del lavoro ancora in corso sotto, la sessione ripartirà da sola: non tocca all'utente
            case "Stop" -> Optional.of(backgroundWork ? SessionStatus.BACKGROUND : SessionStatus.WAITING_INPUT);
            case "PostToolUse" -> mapPostToolUse(subagent, current);
            case "SessionEnd" -> Optional.of(SessionStatus.ENDED);
            default -> Optional.empty();
        };
    }

    private Optional<SessionStatus> mapSessionStart(String source, SessionStatus current) {
        // La compattazione avviene a metà lavoro: non deve riportare la sessione a IDLE
        if ("compact".equals(source) && current != null && current != SessionStatus.ENDED) {
            return Optional.empty();
        }
        return Optional.of(SessionStatus.IDLE);
    }

    private Optional<SessionStatus> mapNotification(String type, SessionStatus current) {
        // Versioni di Claude Code senza notification_type: si segue la regola base
        if (type == null || type.isBlank() || ATTENTION_NOTIFICATIONS.contains(type)) {
            return Optional.of(SessionStatus.NEEDS_ATTENTION);
        }
        // idle_prompt: Claude aspetta input da un po'. Utile solo se lo Stop è andato perso
        if ("idle_prompt".equals(type) && current == SessionStatus.WORKING) {
            return Optional.of(SessionStatus.WAITING_INPUT);
        }
        // Gli altri tipi (auth_success, agent_completed, ...) restano solo nella cronologia
        return Optional.empty();
    }

    private Optional<SessionStatus> mapPostToolUse(boolean subagent, SessionStatus current) {
        // Un tool appena eseguito indica che Claude è ripartito, ad esempio dopo un permesso concesso.
        // Non si tocca WAITING_INPUT: gli hook sono asincroni e un PostToolUse può arrivare dopo lo Stop.
        if (current == null || current == SessionStatus.NEEDS_ATTENTION
                || current == SessionStatus.STALE || current == SessionStatus.IDLE) {
            return Optional.of(SessionStatus.WORKING);
        }
        // Sessione in pausa con task in background: un tool della conversazione principale indica che
        // Claude è stato risvegliato. I tool dei subagent in background invece non contano.
        if (current == SessionStatus.BACKGROUND && !subagent) {
            return Optional.of(SessionStatus.WORKING);
        }
        return Optional.empty();
    }
}
