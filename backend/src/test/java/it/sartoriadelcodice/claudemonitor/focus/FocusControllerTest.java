package it.sartoriadelcodice.claudemonitor.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.sartoriadelcodice.claudemonitor.auth.AuthFilter;
import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.auth.JwtService;
import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import it.sartoriadelcodice.claudemonitor.config.SecurityConfig;
import it.sartoriadelcodice.claudemonitor.hookkey.HookKeyService;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionService;
import it.sartoriadelcodice.claudemonitor.session.SessionStatus;
import it.sartoriadelcodice.claudemonitor.user.Role;
import it.sartoriadelcodice.claudemonitor.user.User;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Test del focus: stream dell'agente con chiave hook, richiesta dalla dashboard con JWT, isolamento tra utenti. */
@WebMvcTest(FocusController.class)
@Import({SecurityConfig.class, AuthFilter.class, JwtService.class, FocusAgentRegistry.class, FocusService.class})
@EnableConfigurationProperties(MonitorProperties.class)
@TestPropertySource(properties = {
        "monitor.auth.username=admin",
        "monitor.auth.password=secret",
        "monitor.auth.jwt-secret=0123456789abcdef0123456789abcdef",
        "monitor.auth.jwt-ttl=1h"
})
class FocusControllerTest {

    private static final String HOOK_KEY = "cmk_test-key";
    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final String REMOTE_ID = "session_01ABCdef234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private FocusAgentRegistry registry;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private HookKeyService hookKeyService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        when(hookKeyService.authenticate(any())).thenReturn(Optional.empty());
        when(hookKeyService.authenticate(HOOK_KEY))
                .thenReturn(Optional.of(new AuthUser(USER_ID, "mario", Role.USER)));
        for (Long id : new Long[] {USER_ID, OTHER_USER_ID}) {
            // Utente vero e non simulato: l'interruttore del focus deve cambiare davvero
            User user = new User("utente" + id, "hash", Role.USER, Instant.parse("2026-11-10T10:00:00Z"));
            ReflectionTestUtils.setField(user, "id", id);
            when(userRepository.findById(id)).thenReturn(Optional.of(user));
        }
        // Il registro è condiviso tra i test della classe: ognuno parte senza agenti collegati
        registry.disconnect(USER_ID);
        registry.disconnect(OTHER_USER_ID);
        when(sessionService.find(any(), any())).thenReturn(Optional.empty());
        when(sessionService.find(USER_ID, "abc123")).thenReturn(Optional.of(session("macbook", null)));
        when(sessionService.find(USER_ID, "other-machine")).thenReturn(Optional.of(session("server", null)));
        when(sessionService.find(USER_ID, "remote")).thenReturn(Optional.of(session("macbook", REMOTE_ID)));
        when(sessionService.find(USER_ID, "remote-linux")).thenReturn(Optional.of(session("linux", REMOTE_ID)));
    }

    private static SessionDto session(String machine, String remoteSessionId) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new SessionDto("abc123", machine, "my-project", "/home/user/my-project", null, null,
                SessionStatus.WAITING_INPUT, now, now, null, null, null, false, now, remoteSessionId);
    }

    private List<String> machines(Long userId) {
        return registry.agents(userId).stream().map(FocusAgentRegistry.AgentInfo::machine).toList();
    }

    private String jwt(Long userId) {
        return "Bearer " + jwtService.issue(userId).token();
    }

    private MvcResult connectAgent(String machine) throws Exception {
        return connectAgent(machine, false);
    }

    private MvcResult connectAgent(String machine, boolean claudeApp) throws Exception {
        return mockMvc.perform(get("/api/agent/stream").param("machine", machine)
                        .param("claudeApp", String.valueOf(claudeApp))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String stream(MvcResult agent) throws Exception {
        return agent.getResponse().getContentAsString();
    }

    /**
     * Fa rispondere l'agente alla prossima richiesta di inattività, come farebbe lo script sul Mac:
     * aspetta l'evento "probe" sul suo stream e invia i secondi indicati.
     */
    private Thread answerProbe(MvcResult agent, String machine, long idle) {
        Thread thread = new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    Matcher probe = Pattern.compile("event:probe\\ndata:([0-9a-f]{32})").matcher(stream(agent));
                    if (probe.find()) {
                        mockMvc.perform(post("/api/agent/activity").param("probe", probe.group(1))
                                        .param("machine", machine).param("idle", String.valueOf(idle))
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                                .andExpect(status().isNoContent());
                        return;
                    }
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        return thread;
    }

    @Test
    void agentStreamRequiresHookKey() throws Exception {
        mockMvc.perform(get("/api/agent/stream").param("machine", "intruso"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/agent/stream").param("machine", "intruso")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        // Il JWT del frontend non apre lo stream dell'agente
        mockMvc.perform(get("/api/agent/stream").param("machine", "intruso")
                        .header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isUnauthorized());
        assertThat(machines(USER_ID)).doesNotContain("intruso");
    }

    @Test
    void hookKeyCannotRequestFocus() throws Exception {
        mockMvc.perform(post("/api/sessions/abc123/focus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void focusWithoutAgentIsConflict() throws Exception {
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    void focusReachesAgentOfSessionMachine() throws Exception {
        connectAgent("macbook");

        mockMvc.perform(get("/api/focus/agents").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].machine").value("macbook"))
                .andExpect(jsonPath("$[0].claudeApp").value(false));
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machine").value("macbook"))
                .andExpect(jsonPath("$.claudeApp").value(false));
        // L'agente di un'altra macchina non riceve il comando
        mockMvc.perform(post("/api/sessions/other-machine/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    void agentsAndSessionsAreNotSharedBetweenUsers() throws Exception {
        connectAgent("macbook");

        mockMvc.perform(get("/api/focus/agents").header(HttpHeaders.AUTHORIZATION, jwt(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(OTHER_USER_ID)))
                .andExpect(status().isNotFound());
    }

    private void setFocusEnabled(Long userId, boolean enabled) throws Exception {
        mockMvc.perform(put("/api/focus/settings").header(HttpHeaders.AUTHORIZATION, jwt(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(enabled));
    }

    @Test
    void switchOffDisconnectsAgentsAndBlocksFocus() throws Exception {
        connectAgent("macbook");
        setFocusEnabled(USER_ID, false);

        assertThat(machines(USER_ID)).isEmpty();
        mockMvc.perform(get("/api/focus/settings").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/focus/agents").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isForbidden());
        // Un agente che riprova a collegarsi viene respinto finché l'interruttore resta spento
        mockMvc.perform(get("/api/agent/stream").param("machine", "macbook")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isForbidden());
        assertThat(machines(USER_ID)).isEmpty();

        setFocusEnabled(USER_ID, true);
        connectAgent("macbook");
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void switchBelongsToTheUserAndNeedsJwt() throws Exception {
        // La chiave hook non può riaccendere il focus
        mockMvc.perform(put("/api/focus/settings").header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());

        connectAgent("macbook");
        setFocusEnabled(OTHER_USER_ID, false);
        // Lo spegnimento di un altro utente non tocca gli agenti di questo
        assertThat(machines(USER_ID)).contains("macbook");
        mockMvc.perform(get("/api/focus/settings").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void rejectsBlankMachine() throws Exception {
        mockMvc.perform(get("/api/agent/stream").param("machine", "  ")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void remoteSessionOpensInClaudeAppOfTheMacInUse() throws Exception {
        when(sessionService.find(USER_ID, "remote-in-use"))
                .thenReturn(Optional.of(session("mac-sessione", REMOTE_ID)));
        MvcResult home = connectAgent("mac-sessione", false);
        MvcResult viewer = connectAgent("mac-in-uso", true);
        Thread homeReply = answerProbe(home, "mac-sessione", 900);
        Thread viewerReply = answerProbe(viewer, "mac-in-uso", 4);

        mockMvc.perform(post("/api/sessions/remote-in-use/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machine").value("mac-in-uso"))
                .andExpect(jsonPath("$.claudeApp").value(true));
        homeReply.join(2_000);
        viewerReply.join(2_000);

        // All'altro Mac arriva solo l'identificativo del controllo remoto; quello della sessione non fa nulla
        assertThat(stream(viewer)).contains("event:remote\ndata:" + REMOTE_ID);
        assertThat(stream(home)).doesNotContain("event:focus");
    }

    @Test
    void sessionMacWinsWhenItIsTheOneInUse() throws Exception {
        when(sessionService.find(USER_ID, "remote-at-home"))
                .thenReturn(Optional.of(session("mac-casa", REMOTE_ID)));
        MvcResult home = connectAgent("mac-casa", false);
        MvcResult viewer = connectAgent("mac-studio", true);
        Thread homeReply = answerProbe(home, "mac-casa", 3);
        Thread viewerReply = answerProbe(viewer, "mac-studio", 3);

        // A parità di inattività resta la finestra sul Mac della sessione
        mockMvc.perform(post("/api/sessions/remote-at-home/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machine").value("mac-casa"))
                .andExpect(jsonPath("$.claudeApp").value(false));
        homeReply.join(2_000);
        viewerReply.join(2_000);

        assertThat(stream(home)).contains("event:focus\ndata:abc123");
        assertThat(stream(viewer)).doesNotContain("event:remote");
    }

    @Test
    void remoteSessionOfMachineWithoutAgentOpensInClaudeApp() throws Exception {
        MvcResult viewer = connectAgent("mac-unico", true);

        // Una sola macchina possibile: nessuna richiesta di inattività
        mockMvc.perform(post("/api/sessions/remote-linux/focus").header(HttpHeaders.AUTHORIZATION, jwt(OTHER_USER_ID)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sessions/remote-linux/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machine").value("mac-unico"))
                .andExpect(jsonPath("$.claudeApp").value(true));
        assertThat(stream(viewer)).doesNotContain("event:probe").contains("event:remote\ndata:" + REMOTE_ID);
    }

    @Test
    void sessionWithoutRemoteControlNeverLeavesItsMachine() throws Exception {
        connectAgent("mac-con-app", true);
        // Il Mac con l'app Claude non serve: senza controllo remoto la sessione si apre solo dove gira
        mockMvc.perform(post("/api/sessions/other-machine/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    void activityRequiresHookKeyAndAKnownProbe() throws Exception {
        String probe = "0123456789abcdef0123456789abcdef";
        mockMvc.perform(post("/api/agent/activity").param("probe", probe).param("machine", "macbook")
                        .param("idle", "5").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/agent/activity").param("probe", probe).param("machine", "macbook")
                        .param("idle", "5").header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agent/activity").param("probe", "not-a-probe").param("machine", "macbook")
                        .param("idle", "5").header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/agent/activity").param("probe", probe).param("machine", "macbook")
                        .param("idle", "-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isBadRequest());
    }
}
