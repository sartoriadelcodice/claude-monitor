package it.sartoriadelcodice.claudemonitor.event;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Evento hook già normalizzato: i campi che servono al dominio più il payload originale. */
public record HookEvent(
        String sessionId,
        String eventName,
        String machine,
        String project,
        String cwd,
        String notificationType,
        String message,
        String source,
        String title,
        String model,
        boolean backgroundWork,
        boolean subagent,
        String remoteSessionId,
        String rawPayload) {

    /** Lunghezza massima del payload salvato nella cronologia. */
    private static final int MAX_PAYLOAD_LENGTH = 20_000;

    /**
     * Identificativo del controllo remoto come lo scrive Claude Code (session_... o cse_...). Finisce in un link
     * aperto dall'agente di un'altra macchina: si accetta solo questa forma, mai altro testo.
     */
    private static final Pattern REMOTE_SESSION_ID = Pattern.compile("(cse|session)_[A-Za-z0-9_-]{1,80}");

    /** Contenuti della conversazione: non servono alla dashboard e non vanno conservati. */
    private static final List<String> PRIVATE_FIELDS = List.of(
            "prompt", "tool_input", "tool_response", "permission_suggestions", "last_assistant_message");

    public boolean isNotification() {
        return "Notification".equals(eventName);
    }

    /**
     * Avvio o ripresa della sessione: l'unico momento in cui cwd è di sicuro la cartella del progetto.
     * Negli altri eventi cwd segue i "cd" fatti da Claude durante il lavoro.
     */
    public boolean isSessionLaunch() {
        return "SessionStart".equals(eventName) && ("startup".equals(source) || "resume".equals(source));
    }

    public boolean isPostToolUse() {
        return "PostToolUse".equals(eventName);
    }

    /**
     * Costruisce l'evento dal JSON inviato dallo script hook: il payload di Claude Code
     * con in più i campi "machine" e "project".
     *
     * @throws IllegalArgumentException se mancano session_id o hook_event_name
     */
    public static HookEvent fromJson(JsonNode json) {
        String sessionId = text(json, "session_id");
        String eventName = text(json, "hook_event_name");
        if (sessionId == null || eventName == null) {
            throw new IllegalArgumentException("session_id e hook_event_name sono obbligatori");
        }
        if (sessionId.length() > 100 || eventName.length() > 100) {
            throw new IllegalArgumentException("session_id o hook_event_name troppo lunghi");
        }
        String cwd = text(json, "cwd");
        String machine = text(json, "machine");
        String project = text(json, "project");
        if (project == null) {
            project = lastPathSegment(cwd);
        }
        String raw = withoutPrivateContent(json).toString();
        if (raw.length() > MAX_PAYLOAD_LENGTH) {
            raw = raw.substring(0, MAX_PAYLOAD_LENGTH);
        }
        // Il modello arriva all'avvio (model) e a ogni cambio (to_model di PostModelSwitch)
        String model = "PostModelSwitch".equals(eventName) ? text(json, "to_model") : text(json, "model");
        // I limiti sono quelli delle colonne del database: un valore troppo lungo non deve far fallire l'evento
        return new HookEvent(sessionId, eventName, limit(machine != null ? machine : "sconosciuta", 255),
                limit(project, 255), cwd, limit(text(json, "notification_type"), 100), text(json, "message"),
                text(json, "source"), limit(text(json, "session_title"), 500), limit(model, 100),
                hasBackgroundWork(json), text(json, "agent_id") != null, remoteSessionId(json), raw);
    }

    /**
     * Controllo remoto attivo: lo script hook aggiunge remote_session_id a ogni evento in cui lo trova.
     * Un valore malformato vale come assente.
     */
    private static String remoteSessionId(JsonNode json) {
        String value = text(json, "remote_session_id");
        return value != null && REMOTE_SESSION_ID.matcher(value).matches() ? value : null;
    }

    private static String limit(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * Copia del payload senza i contenuti della conversazione: testo di prompt e risposte, input e output
     * dei tool, comandi dei task in background e prompt dei risvegli pianificati.
     */
    private static JsonNode withoutPrivateContent(JsonNode json) {
        if (!(json instanceof ObjectNode)) {
            return json;
        }
        ObjectNode copy = json.deepCopy();
        copy.remove(PRIVATE_FIELDS);
        retainFields(copy.get("background_tasks"), List.of("id", "type", "status"));
        retainFields(copy.get("session_crons"), List.of("id", "schedule", "recurring"));
        return copy;
    }

    private static void retainFields(JsonNode array, List<String> fields) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            if (item instanceof ObjectNode object) {
                object.retain(fields);
            }
        }
    }

    /**
     * Vero se allo Stop restano task in background (shell, subagent, workflow, ...) o risvegli pianificati:
     * la sessione è in pausa e ripartirà da sola, non aspetta l'utente.
     * Claude Code elenca in background_tasks e session_crons solo ciò che è ancora in corso.
     */
    private static boolean hasBackgroundWork(JsonNode json) {
        return isNonEmptyArray(json.get("background_tasks")) || isNonEmptyArray(json.get("session_crons"));
    }

    private static boolean isNonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty();
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String lastPathSegment(String path) {
        if (path == null) {
            return "sconosciuto";
        }
        String[] parts = path.split("[/\\\\]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) {
                return parts[i];
            }
        }
        return "sconosciuto";
    }
}
