package it.sartoriadelcodice.claudemonitor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** Tests for parsing the payload sent by the hooks. */
class HookEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HookEvent parse(String extraFields) throws Exception {
        JsonNode node = JSON.readTree(
                "{\"session_id\":\"s1\",\"hook_event_name\":\"Stop\",\"cwd\":\"/work/demo\"" + extraFields + "}");
        return HookEvent.fromJson(node);
    }

    @Test
    void stopWithRunningBackgroundTaskHasBackgroundWork() throws Exception {
        HookEvent event = parse(",\"background_tasks\":[{\"id\":\"t1\",\"type\":\"shell\",\"status\":\"running\"}],"
                + "\"session_crons\":[]");
        assertThat(event.backgroundWork()).isTrue();
    }

    @Test
    void stopWithScheduledWakeupHasBackgroundWork() throws Exception {
        HookEvent event = parse(",\"background_tasks\":[],"
                + "\"session_crons\":[{\"id\":\"c1\",\"schedule\":\"*/5 * * * *\",\"recurring\":true}]");
        assertThat(event.backgroundWork()).isTrue();
    }

    @Test
    void stopWithEmptyOrMissingArraysHasNoBackgroundWork() throws Exception {
        assertThat(parse(",\"background_tasks\":[],\"session_crons\":[]").backgroundWork()).isFalse();
        // Claude Code versions that do not send the two fields
        assertThat(parse("").backgroundWork()).isFalse();
    }

    @Test
    void storedPayloadDropsConversationContent() throws Exception {
        HookEvent event = parse(",\"last_assistant_message\":\"private text\",\"prompt\":\"private question\","
                + "\"tool_input\":{\"command\":\"private command\"},"
                + "\"background_tasks\":[{\"id\":\"t1\",\"type\":\"shell\",\"status\":\"running\","
                + "\"command\":\"private command\",\"description\":\"private description\"}],"
                + "\"session_crons\":[{\"id\":\"c1\",\"schedule\":\"* * * * *\",\"prompt\":\"private prompt\"}]");
        assertThat(event.rawPayload()).doesNotContain("private");
        assertThat(event.rawPayload()).contains("\"t1\"", "\"shell\"", "\"c1\"");
        // The status is still derived from the original payload
        assertThat(event.backgroundWork()).isTrue();
    }

    @Test
    void overlongFieldsAreCutToColumnSize() throws Exception {
        HookEvent event = parse(",\"machine\":\"" + "m".repeat(400) + "\",\"session_title\":\"" + "t".repeat(900) + "\"");
        assertThat(event.machine()).hasSize(255);
        assertThat(event.title()).hasSize(500);
    }

    @Test
    void overlongSessionIdIsRejected() {
        JsonNode node = JSON.createObjectNode().put("session_id", "s".repeat(101)).put("hook_event_name", "Stop");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HookEvent.fromJson(node))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remoteSessionIdIsAcceptedOnlyInClaudeCodeForm() throws Exception {
        assertThat(parse(",\"remote_session_id\":\"session_01AbCdEfGhIjKlMnOpQrStUv\"").remoteSessionId())
                .isEqualTo("session_01AbCdEfGhIjKlMnOpQrStUv");
        assertThat(parse(",\"remote_session_id\":\"cse_01AbCdEfGh\"").remoteSessionId()).isEqualTo("cse_01AbCdEfGh");
        assertThat(parse("").remoteSessionId()).isNull();
        // It ends up in a link opened by another machine: no paths, parameters or other schemes
        assertThat(parse(",\"remote_session_id\":\"session_1/../../x\"").remoteSessionId()).isNull();
        assertThat(parse(",\"remote_session_id\":\"session_1?q=x\"").remoteSessionId()).isNull();
        assertThat(parse(",\"remote_session_id\":\"https://example.com\"").remoteSessionId()).isNull();
        assertThat(parse(",\"remote_session_id\":\"session_" + "a".repeat(81) + "\"").remoteSessionId()).isNull();
    }

    @Test
    void agentIdMarksSubagentEvents() throws Exception {
        assertThat(parse(",\"agent_id\":\"a1\",\"agent_type\":\"Explore\"").subagent()).isTrue();
        assertThat(parse("").subagent()).isFalse();
    }
}
