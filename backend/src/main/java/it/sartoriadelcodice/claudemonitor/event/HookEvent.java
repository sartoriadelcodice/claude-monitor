package it.sartoriadelcodice.claudemonitor.event;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Normalized hook event: the fields the domain needs plus the original payload. */
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

    /** Maximum length of the payload stored in the history. */
    private static final int MAX_PAYLOAD_LENGTH = 20_000;

    /**
     * Remote Control identifier as Claude Code writes it (session_... or cse_...). It ends up in a link opened
     * by the agent of another machine: only this form is accepted, never any other text.
     */
    private static final Pattern REMOTE_SESSION_ID = Pattern.compile("(cse|session)_[A-Za-z0-9_-]{1,80}");

    /** Conversation content: the dashboard does not need it and it must not be stored. */
    private static final List<String> PRIVATE_FIELDS = List.of(
            "prompt", "tool_input", "tool_response", "permission_suggestions", "last_assistant_message");

    public boolean isNotification() {
        return "Notification".equals(eventName);
    }

    /**
     * Session startup or resume: the only moment when cwd is certainly the project folder.
     * In the other events cwd follows the "cd" commands Claude runs while working.
     */
    public boolean isSessionLaunch() {
        return "SessionStart".equals(eventName) && ("startup".equals(source) || "resume".equals(source));
    }

    public boolean isPostToolUse() {
        return "PostToolUse".equals(eventName);
    }

    /**
     * Builds the event from the JSON sent by the hook script: the Claude Code payload
     * plus the "machine" and "project" fields.
     *
     * @throws IllegalArgumentException if session_id or hook_event_name is missing
     */
    public static HookEvent fromJson(JsonNode json) {
        String sessionId = text(json, "session_id");
        String eventName = text(json, "hook_event_name");
        if (sessionId == null || eventName == null) {
            throw new IllegalArgumentException("session_id and hook_event_name are required");
        }
        if (sessionId.length() > 100 || eventName.length() > 100) {
            throw new IllegalArgumentException("session_id or hook_event_name too long");
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
        // The model arrives at startup (model) and on every switch (to_model of PostModelSwitch)
        String model = "PostModelSwitch".equals(eventName) ? text(json, "to_model") : text(json, "model");
        // The limits are those of the database columns: a value that is too long must not make the event fail
        return new HookEvent(sessionId, eventName, limit(machine != null ? machine : "unknown", 255),
                limit(project, 255), cwd, limit(text(json, "notification_type"), 100), text(json, "message"),
                text(json, "source"), limit(text(json, "session_title"), 500), limit(model, 100),
                hasBackgroundWork(json), text(json, "agent_id") != null, remoteSessionId(json), raw);
    }

    /**
     * Remote Control active: the hook script adds remote_session_id to every event in which it finds it.
     * A malformed value counts as absent.
     */
    private static String remoteSessionId(JsonNode json) {
        String value = text(json, "remote_session_id");
        return value != null && REMOTE_SESSION_ID.matcher(value).matches() ? value : null;
    }

    private static String limit(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * Copy of the payload without the conversation content: prompt and response text, tool input and output,
     * background task commands and prompts of scheduled wake-ups.
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
     * True if background tasks (shell, subagent, workflow, ...) or scheduled wake-ups remain at Stop:
     * the session is paused and will resume on its own, it is not waiting for the user.
     * Claude Code lists in background_tasks and session_crons only what is still running.
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
            return "unknown";
        }
        String[] parts = path.split("[/\\\\]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) {
                return parts[i];
            }
        }
        return "unknown";
    }
}
