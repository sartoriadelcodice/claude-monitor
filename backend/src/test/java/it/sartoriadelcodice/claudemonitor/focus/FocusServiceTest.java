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

/** Scelta della macchina su cui aprire una sessione: casi limite delle risposte degli agenti. */
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
        agents(new AgentInfo("casa", false), new AgentInfo("studio", true));

        assertThat(service.focus(USER_ID, session("casa", null))).contains(new FocusResult("casa", false));
        verify(registry, never()).probeIdle(any(), any(), any());
    }

    @Test
    void sessionMacThatDoesNotAnswerLosesOnlyToAMacInUse() {
        agents(new AgentInfo("casa", false), new AgentInfo("studio", true));

        // Agente del Mac della sessione di una versione precedente: non risponde alla richiesta
        idle(Map.of("studio", FocusService.ACTIVE_IDLE_SECONDS - 1));
        assertThat(service.focus(USER_ID, session("casa", REMOTE_ID))).contains(new FocusResult("studio", true));

        idle(Map.of("studio", FocusService.ACTIVE_IDLE_SECONDS));
        assertThat(service.focus(USER_ID, session("casa", REMOTE_ID))).contains(new FocusResult("casa", false));
    }

    @Test
    void withoutAnswersTheSessionMacIsKept() {
        agents(new AgentInfo("casa", false), new AgentInfo("studio", true));
        idle(Map.of());

        assertThat(service.focus(USER_ID, session("casa", REMOTE_ID))).contains(new FocusResult("casa", false));
    }

    @Test
    void mostRecentlyUsedMacWinsAmongSeveral() {
        agents(new AgentInfo("portatile", true), new AgentInfo("studio", true));
        idle(Map.of("portatile", 40L, "studio", 900L));

        // La sessione gira su una macchina senza agente (ad esempio Linux)
        assertThat(service.focus(USER_ID, session("server", REMOTE_ID)))
                .contains(new FocusResult("portatile", true));
        verify(registry).openRemote(USER_ID, "portatile", REMOTE_ID);
    }

    @Test
    void failedRemoteOpeningFallsBackToTheSessionWindow() {
        agents(new AgentInfo("casa", false), new AgentInfo("studio", true));
        idle(Map.of("casa", 600L, "studio", 2L));
        when(registry.openRemote(USER_ID, "studio", REMOTE_ID)).thenReturn(false);

        assertThat(service.focus(USER_ID, session("casa", REMOTE_ID))).contains(new FocusResult("casa", false));
    }

    @Test
    void macsWithoutClaudeAppAreNotCandidates() {
        agents(new AgentInfo("casa", false), new AgentInfo("vecchio", false));

        assertThat(service.focus(USER_ID, session("casa", REMOTE_ID))).contains(new FocusResult("casa", false));
        verify(registry, never()).probeIdle(any(), any(), any());
        assertThat(service.focus(USER_ID, session("server", REMOTE_ID))).isEmpty();
    }
}
