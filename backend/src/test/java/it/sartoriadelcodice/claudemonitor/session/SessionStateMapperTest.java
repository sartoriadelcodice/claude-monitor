package it.sartoriadelcodice.claudemonitor.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Test della mappatura evento → stato. */
class SessionStateMapperTest {

    private final SessionStateMapper mapper = new SessionStateMapper();

    @ParameterizedTest
    @CsvSource({
            "SessionStart, IDLE",
            "UserPromptSubmit, WORKING",
            "Notification, NEEDS_ATTENTION",
            "Stop, WAITING_INPUT",
            "SessionEnd, ENDED"
    })
    void baseEventsMapToExpectedStatus(String eventName, SessionStatus expected) {
        assertThat(mapper.map(eventName, null, null, null)).contains(expected);
    }

    @ParameterizedTest
    @EnumSource(SessionStatus.class)
    void baseMappingDoesNotDependOnCurrentStatus(SessionStatus current) {
        assertThat(mapper.map("UserPromptSubmit", null, null, current)).contains(SessionStatus.WORKING);
        assertThat(mapper.map("Stop", null, null, current)).contains(SessionStatus.WAITING_INPUT);
        assertThat(mapper.map("SessionEnd", null, null, current)).contains(SessionStatus.ENDED);
    }

    @ParameterizedTest
    @CsvSource({"permission_prompt", "elicitation_dialog"})
    void attentionNotificationsRequireAttention(String type) {
        assertThat(mapper.map("Notification", type, null, SessionStatus.WORKING))
                .contains(SessionStatus.NEEDS_ATTENTION);
    }

    @ParameterizedTest
    @CsvSource({"auth_success", "agent_completed", "unknown_type"})
    void informativeNotificationsDoNotChangeStatus(String type) {
        assertThat(mapper.map("Notification", type, null, SessionStatus.WORKING)).isEmpty();
    }

    @Test
    void idlePromptMovesWorkingSessionToWaitingInput() {
        assertThat(mapper.map("Notification", "idle_prompt", null, SessionStatus.WORKING))
                .contains(SessionStatus.WAITING_INPUT);
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = {"WAITING_INPUT", "NEEDS_ATTENTION", "IDLE", "STALE"})
    void idlePromptLeavesOtherStatusesUntouched(SessionStatus current) {
        assertThat(mapper.map("Notification", "idle_prompt", null, current)).isEmpty();
    }

    @Test
    void compactDoesNotResetAnActiveSession() {
        assertThat(mapper.map("SessionStart", null, "compact", SessionStatus.WORKING)).isEmpty();
    }

    @Test
    void compactOnNewSessionStartsIdle() {
        assertThat(mapper.map("SessionStart", null, "compact", null)).contains(SessionStatus.IDLE);
    }

    @Test
    void resumeReopensAnEndedSession() {
        assertThat(mapper.map("SessionStart", null, "resume", SessionStatus.ENDED)).contains(SessionStatus.IDLE);
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = {"NEEDS_ATTENTION", "STALE", "IDLE"})
    void postToolUseResumesWork(SessionStatus current) {
        assertThat(mapper.map("PostToolUse", null, null, current)).contains(SessionStatus.WORKING);
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = {"WAITING_INPUT", "WORKING", "ENDED"})
    void postToolUseDoesNotOverrideLaterStates(SessionStatus current) {
        // Un PostToolUse arrivato in ritardo non deve annullare lo Stop
        assertThat(mapper.map("PostToolUse", null, null, current)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SessionStatus.class)
    void stopWithBackgroundWorkDoesNotWaitForTheUser(SessionStatus current) {
        assertThat(mapper.map("Stop", null, null, true, false, current)).contains(SessionStatus.BACKGROUND);
    }

    @Test
    void stopWithoutBackgroundWorkEndsTheBackgroundPause() {
        assertThat(mapper.map("Stop", null, null, false, false, SessionStatus.BACKGROUND))
                .contains(SessionStatus.WAITING_INPUT);
    }

    @Test
    void postToolUseOfMainConversationWakesBackgroundSession() {
        assertThat(mapper.map("PostToolUse", null, null, false, false, SessionStatus.BACKGROUND))
                .contains(SessionStatus.WORKING);
    }

    @Test
    void postToolUseOfSubagentLeavesBackgroundSessionPaused() {
        assertThat(mapper.map("PostToolUse", null, null, false, true, SessionStatus.BACKGROUND)).isEmpty();
    }

    @Test
    void idlePromptDoesNotTurnBackgroundIntoWaitingInput() {
        assertThat(mapper.map("Notification", "idle_prompt", null, SessionStatus.BACKGROUND)).isEmpty();
    }

    @Test
    void unknownOrMissingEventIsIgnored() {
        assertThat(mapper.map("PreCompact", null, null, SessionStatus.WORKING)).isEmpty();
        assertThat(mapper.map(null, null, null, SessionStatus.WORKING)).isEmpty();
    }
}
