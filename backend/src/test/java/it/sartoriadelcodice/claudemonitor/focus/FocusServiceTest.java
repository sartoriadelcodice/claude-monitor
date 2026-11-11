package it.sartoriadelcodice.claudemonitor.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.sartoriadelcodice.claudemonitor.focus.FocusAgentRegistry.AgentInfo;
import it.sartoriadelcodice.claudemonitor.focus.FocusService.FocusResult;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionStatus;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Choice of the machine on which to open a session: edge cases of the agents' replies. */
class FocusServiceTest {

    private static final Long USER_ID = 7L;
    private static final String REMOTE_ID = "session_01ABCdef234";

    private final FocusAgentRegistry registry = mock(FocusAgentRegistry.class);
    private final FocusService service = new FocusService(mock(UserRepository.class), registry);

    @BeforeEach
    void setUp() {
        when(registry.focus(any(), anyString(), anyString())).thenReturn(true);
        when(registry.openRemote(any(), anyString(), anyString())).thenReturn(true);
    }

    private static SessionDto session(String machine, String remoteSessionId) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new SessionDto("s1", machine, "demo", "/work/demo", null, null, SessionStatus.WAITING_INPUT, now, now,
                null, null, null, false, now, remoteSessionId);
    }

    private void agents(AgentInfo... agents) {
        when(registry.agents(USER_ID)).thenReturn(List.of(agents));
    }

    private void idle(Map<String, Long> idle) {
        when(registry.probeIdle(eq(USER_ID), any(), any())).thenReturn(idle);
    }

    @Test
    void withoutRemoteControlNoProbeIsSent() {
        agents(new AgentInfo("home", false), new AgentInfo("studio", true));

        assertThat(service.focus(USER_ID, session("home", null))).contains(new FocusResult("home", false));
        verify(registry, never()).probeIdle(any(), any(), any());
    }

    @Test
    void sessionMacThatDoesNotAnswerLosesOnlyToAMacInUse() {
        agents(new AgentInfo("home", false), new AgentInfo("studio", true));

        // The session Mac's agent is from an earlier version: it does not answer the request
        idle(Map.of("studio", FocusService.ACTIVE_IDLE_SECONDS - 1));
        assertThat(service.focus(USER_ID, session("home", REMOTE_ID))).contains(new FocusResult("studio", true));

        idle(Map.of("studio", FocusService.ACTIVE_IDLE_SECONDS));
        assertThat(service.focus(USER_ID, session("home", REMOTE_ID))).contains(new FocusResult("home", false));
    }

    @Test
    void withoutAnswersTheSessionMacIsKept() {
        agents(new AgentInfo("home", false), new AgentInfo("studio", true));
        idle(Map.of());

        assertThat(service.focus(USER_ID, session("home", REMOTE_ID))).contains(new FocusResult("home", false));
    }

    @Test
    void mostRecentlyUsedMacWinsAmongSeveral() {
        agents(new AgentInfo("laptop", true), new AgentInfo("studio", true));
        idle(Map.of("laptop", 40L, "studio", 900L));

        // The session runs on a machine without an agent (for example Linux)
        assertThat(service.focus(USER_ID, session("server", REMOTE_ID)))
                .contains(new FocusResult("laptop", true));
        verify(registry).openRemote(USER_ID, "laptop", REMOTE_ID);
    }

    @Test
    void failedRemoteOpeningFallsBackToTheSessionWindow() {
        agents(new AgentInfo("home", false), new AgentInfo("studio", true));
        idle(Map.of("home", 600L, "studio", 2L));
        when(registry.openRemote(USER_ID, "studio", REMOTE_ID)).thenReturn(false);

        assertThat(service.focus(USER_ID, session("home", REMOTE_ID))).contains(new FocusResult("home", false));
    }

    @Test
    void macsWithoutClaudeAppAreNotCandidates() {
        agents(new AgentInfo("home", false), new AgentInfo("old-mac", false));

        assertThat(service.focus(USER_ID, session("home", REMOTE_ID))).contains(new FocusResult("home", false));
        verify(registry, never()).probeIdle(any(), any(), any());
        assertThat(service.focus(USER_ID, session("server", REMOTE_ID))).isEmpty();
    }
}
