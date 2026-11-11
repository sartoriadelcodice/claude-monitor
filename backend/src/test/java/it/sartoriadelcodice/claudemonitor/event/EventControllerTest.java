package it.sartoriadelcodice.claudemonitor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Tests for the POST /api/events endpoint: authentication, validation and payload parsing. */
@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, AuthFilter.class, JwtService.class})
@EnableConfigurationProperties(MonitorProperties.class)
@TestPropertySource(properties = {
        "monitor.auth.username=admin",
        "monitor.auth.password=secret",
        "monitor.auth.jwt-secret=0123456789abcdef0123456789abcdef",
        "monitor.auth.jwt-ttl=1h"
})
class EventControllerTest {

    private static final String HOOK_KEY = "cmk_test-key";
    private static final Long USER_ID = 7L;

    private static final String PAYLOAD = """
            {
              "session_id": "abc123",
              "hook_event_name": "Notification",
              "cwd": "/home/user/my-project",
              "notification_type": "permission_prompt",
              "message": "Claude needs your permission",
              "machine": "macbook",
              "project": "my-project"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

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
    }

    private static SessionDto dto() {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        return new SessionDto("abc123", "macbook", "my-project", "/home/user/my-project", null, null,
                SessionStatus.NEEDS_ATTENTION, now, now, "Claude needs your permission", "permission_prompt",
                null, false, now, null);
    }

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessionService);
    }

    @Test
    void rejectsUnknownOrRevokedKey() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessionService);
    }

    @Test
    void rejectsFrontendJwt() throws Exception {
        // The frontend JWT does not authorize sending events
        String jwt = jwtService.issue(USER_ID).token();
        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessionService);
    }

    @Test
    void acceptsValidEventAndParsesFields() throws Exception {
        when(sessionService.handleEvent(any(), any())).thenReturn(dto());

        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc123"))
                .andExpect(jsonPath("$.status").value("NEEDS_ATTENTION"));

        ArgumentCaptor<HookEvent> captor = ArgumentCaptor.forClass(HookEvent.class);
        verify(sessionService).handleEvent(eq(USER_ID), captor.capture());
        HookEvent event = captor.getValue();
        assertThat(event.sessionId()).isEqualTo("abc123");
        assertThat(event.eventName()).isEqualTo("Notification");
        assertThat(event.machine()).isEqualTo("macbook");
        assertThat(event.project()).isEqualTo("my-project");
        assertThat(event.notificationType()).isEqualTo("permission_prompt");
        assertThat(event.message()).isEqualTo("Claude needs your permission");
    }

    @Test
    void derivesProjectFromCwdWhenMissing() throws Exception {
        when(sessionService.handleEvent(any(), any())).thenReturn(dto());

        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"abc123","hook_event_name":"Stop","cwd":"C:\\\\work\\\\demo-app\\\\"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<HookEvent> captor = ArgumentCaptor.forClass(HookEvent.class);
        verify(sessionService).handleEvent(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().project()).isEqualTo("demo-app");
        assertThat(captor.getValue().machine()).isEqualTo("unknown");
    }

    @Test
    void readsModelFromSessionStartAndModelSwitch() throws Exception {
        when(sessionService.handleEvent(any(), any())).thenReturn(dto());

        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"abc123","hook_event_name":"SessionStart","cwd":"/w/p",
                                 "model":"claude-opus-5","session_title":"auth-refactor"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"abc123","hook_event_name":"PostModelSwitch","cwd":"/w/p",
                                 "from_model":"claude-opus-5","to_model":"claude-sonnet-5"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<HookEvent> captor = ArgumentCaptor.forClass(HookEvent.class);
        verify(sessionService, times(2)).handleEvent(eq(USER_ID), captor.capture());
        assertThat(captor.getAllValues().get(0).model()).isEqualTo("claude-opus-5");
        assertThat(captor.getAllValues().get(0).title()).isEqualTo("auth-refactor");
        assertThat(captor.getAllValues().get(1).model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void rejectsPayloadWithoutSessionId() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hook_event_name\":\"Stop\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessionService);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + HOOK_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessionService);
    }
}
