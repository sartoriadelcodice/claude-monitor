package it.sartoriadelcodice.claudemonitor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** Test della lettura del payload inviato dagli hook. */
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
        // Versioni di Claude Code che non inviano i due campi
        assertThat(parse("").backgroundWork()).isFalse();
    }

    @Test
    void storedPayloadDropsConversationContent() throws Exception {
        HookEvent event = parse(",\"last_assistant_message\":\"testo riservato\",\"prompt\":\"domanda riservata\","
                + "\"tool_input\":{\"command\":\"comando riservato\"},"
                + "\"background_tasks\":[{\"id\":\"t1\",\"type\":\"shell\",\"status\":\"running\","
                + "\"command\":\"comando riservato\",\"description\":\"descrizione riservata\"}],"
                + "\"session_crons\":[{\"id\":\"c1\",\"schedule\":\"* * * * *\",\"prompt\":\"prompt riservato\"}]");
        assertThat(event.rawPayload()).doesNotContain("riservat");
        assertThat(event.rawPayload()).contains("\"t1\"", "\"shell\"", "\"c1\"");
        // Lo stato si ricava comunque dal payload originale
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
        // Finisce in un link aperto da un'altra macchina: niente percorsi, parametri o altri schemi
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
