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
import java.util.Optional;

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
        when(sessionService.find(any(), any())).thenReturn(Optional.empty());
        when(sessionService.find(USER_ID, "abc123")).thenReturn(Optional.of(session("macbook")));
        when(sessionService.find(USER_ID, "other-machine")).thenReturn(Optional.of(session("server")));
    }

    private static SessionDto session(String machine) {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new SessionDto("abc123", machine, "my-project", "/home/user/my-project", null, null,
                SessionStatus.WAITING_INPUT, now, now, null, null, null, false, now);
    }

    private String jwt(Long userId) {
        return "Bearer " + jwtService.issue(userId).token();
    }

    private void connectAgent(String machine) throws Exception {
        mockMvc.perform(get("/api/agent/stream").param("machine", machine)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(request().asyncStarted());
    }

    @Test
    void agentStreamRequiresHookKey() throws Exception {
        // Nome usato solo qui: il registro è condiviso con gli altri test della classe
        mockMvc.perform(get("/api/agent/stream").param("machine", "intruso"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/agent/stream").param("machine", "intruso")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        // Il JWT del frontend non apre lo stream dell'agente
        mockMvc.perform(get("/api/agent/stream").param("machine", "intruso")
                        .header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isUnauthorized());
        assertThat(registry.machines(USER_ID)).doesNotContain("intruso");
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

        mockMvc.perform(get("/api/focus/machines").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("macbook"));
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isNoContent());
        // L'agente di un'altra macchina non riceve il comando
        mockMvc.perform(post("/api/sessions/other-machine/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    void agentsAndSessionsAreNotSharedBetweenUsers() throws Exception {
        connectAgent("macbook");

        mockMvc.perform(get("/api/focus/machines").header(HttpHeaders.AUTHORIZATION, jwt(OTHER_USER_ID)))
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

        assertThat(registry.machines(USER_ID)).isEmpty();
        mockMvc.perform(get("/api/focus/settings").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/focus/machines").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isForbidden());
        // Un agente che riprova a collegarsi viene respinto finché l'interruttore resta spento
        mockMvc.perform(get("/api/agent/stream").param("machine", "macbook")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isForbidden());
        assertThat(registry.machines(USER_ID)).isEmpty();

        setFocusEnabled(USER_ID, true);
        connectAgent("macbook");
        mockMvc.perform(post("/api/sessions/abc123/focus").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(status().isNoContent());
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
        assertThat(registry.machines(USER_ID)).contains("macbook");
        mockMvc.perform(get("/api/focus/settings").header(HttpHeaders.AUTHORIZATION, jwt(USER_ID)))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void rejectsBlankMachine() throws Exception {
        mockMvc.perform(get("/api/agent/stream").param("machine", "  ")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY))
                .andExpect(status().isBadRequest());
    }
}
