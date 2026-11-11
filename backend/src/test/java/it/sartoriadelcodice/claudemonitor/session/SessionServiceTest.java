package it.sartoriadelcodice.claudemonitor.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import it.sartoriadelcodice.claudemonitor.event.HookEvent;
import it.sartoriadelcodice.claudemonitor.event.SessionEvent;
import it.sartoriadelcodice.claudemonitor.event.SessionEventRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

/** Test dell'applicazione degli eventi alle sessioni, con repository simulati. */
class SessionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private final Map<String, Session> store = new HashMap<>();
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final SessionEventRepository events = mock(SessionEventRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private SessionService service;

    @BeforeEach
    void setUp() {
        when(sessions.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        when(sessions.findByIdAndUserId(any(), any())).thenAnswer(inv -> Optional
                .ofNullable(store.get(inv.<String>getArgument(0)))
                .filter(s -> s.getUserId().equals(inv.<Long>getArgument(1))));
        when(sessions.save(any())).thenAnswer(inv -> {
            Session s = inv.getArgument(0);
            store.put(s.getId(), s);
            return s;
        });
        var properties = new MonitorProperties("token", null,
                new MonitorProperties.Sessions(Duration.ofMinutes(30)), null);
        service = new SessionService(sessions, events, new SessionStateMapper(), publisher, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HookEvent event(String name, String notificationType, String message) {
        return new HookEvent("s1", name, "macbook", "demo", "/work/demo", notificationType, message, null, null,
                null, false, false, "{}");
    }

    @Test
    void firstEventCreatesSession() {
        SessionDto dto = service.handleEvent(USER_ID, event("SessionStart", null, null));

        assertThat(dto.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(dto.machine()).isEqualTo("macbook");
        assertThat(dto.project()).isEqualTo("demo");
        verify(publisher).publishEvent(new SessionChangedEvent(USER_ID, dto, true));
    }

    @Test
    void notificationStoresMessageAndRequiresAttention() {
        service.handleEvent(USER_ID, event("UserPromptSubmit", null, null));
        SessionDto dto = service.handleEvent(USER_ID, event("Notification", "permission_prompt", "Claude needs your permission"));

        assertThat(dto.status()).isEqualTo(SessionStatus.NEEDS_ATTENTION);
        assertThat(dto.lastNotificationMessage()).isEqualTo("Claude needs your permission");
        assertThat(dto.lastNotificationType()).isEqualTo("permission_prompt");
    }

    @Test
    void statusChangeClearsSeenFlag() {
        service.handleEvent(USER_ID, event("Stop", null, null));
        assertThat(service.update(USER_ID, "s1", true, null)).get().extracting(SessionDto::seenAt).isEqualTo(NOW);

        SessionDto dto = service.handleEvent(USER_ID, event("UserPromptSubmit", null, null));

        assertThat(dto.seenAt()).isNull();
    }

    @Test
    void resumedWorkClearsOldNotification() {
        service.handleEvent(USER_ID, event("Notification", "permission_prompt", "Permesso"));
        SessionDto dto = service.handleEvent(USER_ID, event("PostToolUse", null, null));

        assertThat(dto.status()).isEqualTo(SessionStatus.WORKING);
        assertThat(dto.lastNotificationMessage()).isNull();
    }

    @Test
    void postToolUseWithoutStatusChangeIsNotRecorded() {
        service.handleEvent(USER_ID, event("UserPromptSubmit", null, null));
        service.handleEvent(USER_ID, event("PostToolUse", null, null));

        ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventName()).isEqualTo("UserPromptSubmit");
    }

    @Test
    void unchangedStatusIsReportedAsNotChanged() {
        service.handleEvent(USER_ID, event("Stop", null, null));
        SessionDto dto = service.handleEvent(USER_ID, event("Stop", null, null));

        verify(publisher).publishEvent(new SessionChangedEvent(USER_ID, dto, false));
    }

    @Test
    void staleJobMarksInactiveSessions() {
        Session old = new Session("old", USER_ID, "macbook", "demo", NOW.minus(Duration.ofHours(2)));
        when(sessions.findByStatusInAndLastEventAtBefore(any(), any())).thenReturn(List.of(old));

        assertThat(service.markStaleSessions()).isEqualTo(1);
        assertThat(old.getStatus()).isEqualTo(SessionStatus.STALE);
    }

    @Test
    void anotherUserCannotUpdateTheSession() {
        service.handleEvent(USER_ID, event("Stop", null, null));

        assertThatThrownBy(() -> service.handleEvent(OTHER_USER_ID, event("UserPromptSubmit", null, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(store.get("s1").getStatus()).isEqualTo(SessionStatus.WAITING_INPUT);
    }

    @Test
    void anotherUserCannotSeeOrPatchTheSession() {
        service.handleEvent(USER_ID, event("Stop", null, null));

        assertThat(service.update(OTHER_USER_ID, "s1", true, null)).isEmpty();
        assertThat(service.eventsOf(OTHER_USER_ID, "s1")).isEmpty();
    }

    @Test
    void endedSessionIsArchivedAutomatically() {
        service.handleEvent(USER_ID, event("Stop", null, null));
        SessionDto dto = service.handleEvent(USER_ID, event("SessionEnd", null, null));

        assertThat(dto.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(dto.archived()).isTrue();
    }

    @Test
    void resumedSessionLeavesTheArchive() {
        service.handleEvent(USER_ID, event("SessionEnd", null, null));
        SessionDto dto = service.handleEvent(USER_ID, event("SessionStart", null, null));

        assertThat(dto.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(dto.archived()).isFalse();
    }

    @Test
    void modelAndTitleAreKeptUntilReplaced() {
        service.handleEvent(USER_ID, new HookEvent("s1", "SessionStart", "macbook", "demo", "/work/demo", null,
                null, "startup", "auth-refactor", "claude-opus-5", false, false, "{}"));
        SessionDto unchanged = service.handleEvent(USER_ID, event("Stop", null, null));
        assertThat(unchanged.model()).isEqualTo("claude-opus-5");
        assertThat(unchanged.title()).isEqualTo("auth-refactor");

        SessionDto switched = service.handleEvent(USER_ID, new HookEvent("s1", "PostModelSwitch", "macbook",
                "demo", "/work/demo", null, null, "command", null, "claude-sonnet-5", false, false, "{}"));
        assertThat(switched.model()).isEqualTo("claude-sonnet-5");
        assertThat(switched.status()).isEqualTo(SessionStatus.WAITING_INPUT);
    }

    @Test
    void projectAndCwdStayThoseOfTheLaunchDirectory() {
        service.handleEvent(USER_ID, event("SessionStart", null, null));

        // Claude ha fatto "cd" in una sottocartella: cwd e project dell'evento cambiano
        SessionDto moved = service.handleEvent(USER_ID, new HookEvent("s1", "PostToolUse", "macbook", "frontend",
                "/work/demo/frontend", null, null, null, null, null, false, false, "{}"));
        assertThat(moved.project()).isEqualTo("demo");
        assertThat(moved.cwd()).isEqualTo("/work/demo");

        // Una ripresa da un'altra cartella invece aggiorna il progetto
        SessionDto resumed = service.handleEvent(USER_ID, new HookEvent("s1", "SessionStart", "macbook", "other",
                "/work/other", null, null, "resume", null, null, false, false, "{}"));
        assertThat(resumed.project()).isEqualTo("other");
        assertThat(resumed.cwd()).isEqualTo("/work/other");
    }

    @Test
    void updateOnMissingSessionReturnsEmpty() {
        assertThat(service.update(USER_ID, "missing", true, null)).isEmpty();
        verify(publisher, never()).publishEvent(any());
    }
}
