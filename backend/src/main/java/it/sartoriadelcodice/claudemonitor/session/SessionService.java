package it.sartoriadelcodice.claudemonitor.session;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import it.sartoriadelcodice.claudemonitor.event.EventDto;
import it.sartoriadelcodice.claudemonitor.event.HookEvent;
import it.sartoriadelcodice.claudemonitor.event.SessionEvent;
import it.sartoriadelcodice.claudemonitor.event.SessionEventRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Session management: applying events, listing, seen/archived flags, expiration. */
@Service
public class SessionService {

    /** Maximum number of sessions returned when the history is requested too. */
    private static final int HISTORY_LIMIT = 500;

    private static final Set<SessionStatus> STALE_CANDIDATES = Set.of(
            SessionStatus.IDLE, SessionStatus.WORKING, SessionStatus.NEEDS_ATTENTION, SessionStatus.WAITING_INPUT,
            SessionStatus.BACKGROUND);

    private final SessionRepository sessions;
    private final SessionEventRepository events;
    private final SessionStateMapper mapper;
    private final ApplicationEventPublisher publisher;
    private final MonitorProperties properties;
    private final Clock clock;

    public SessionService(SessionRepository sessions, SessionEventRepository events, SessionStateMapper mapper,
                          ApplicationEventPublisher publisher, MonitorProperties properties, Clock clock) {
        this.sessions = sessions;
        this.events = events;
        this.mapper = mapper;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Applies an event received from a hook, creating the session if it does not exist.
     * The session belongs to the user of the hook key; another user cannot update it.
     */
    @Transactional
    public SessionDto handleEvent(Long userId, HookEvent event) {
        Instant now = clock.instant();
        Optional<Session> existing = sessions.findById(event.sessionId());
        if (existing.isPresent() && !userId.equals(existing.get().getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The session belongs to another user");
        }
        Session session = existing.orElseGet(
                () -> new Session(event.sessionId(), userId, event.machine(), event.project(), now));
        SessionStatus current = existing.map(Session::getStatus).orElse(null);

        session.setLastEventAt(now);
        session.setMachine(event.machine());
        // Project and folder stay those of the launch: the cwd of later events changes with every "cd"
        // Claude runs and would rename the card mid-work
        if (existing.isEmpty() || event.isSessionLaunch()) {
            session.setProject(event.project());
            if (event.cwd() != null) {
                session.setCwd(event.cwd());
            }
        }
        if (event.title() != null) {
            session.setTitle(event.title());
        }
        if (event.model() != null) {
            session.setModel(event.model());
        }
        // Every event carries the current Remote Control state: if it is missing, it has been turned off
        session.setRemoteSessionId(event.remoteSessionId());

        boolean statusChanged = mapper.map(event.eventName(), event.notificationType(), event.source(),
                        event.backgroundWork(), event.subagent(), current)
                .map(next -> session.changeStatus(next, now))
                .orElse(false);
        // A new session counts as a change even if it stays in the initial IDLE status
        statusChanged = statusChanged || existing.isEmpty();

        if (event.isNotification()) {
            session.setLastNotificationMessage(event.message());
            session.setLastNotificationType(event.notificationType());
        } else if (statusChanged && session.getStatus() == SessionStatus.WORKING) {
            // Once work has resumed, the old message is no longer current (it stays in the history)
            session.setLastNotificationMessage(null);
            session.setLastNotificationType(null);
        }
        // An event on an archived session brings it back to the main view
        if (session.isArchived() && statusChanged) {
            session.setArchived(false);
        }
        // An ended session is archived at once: it leaves the main view and stays in the history
        if (statusChanged && session.getStatus() == SessionStatus.ENDED) {
            session.setArchived(true);
        }

        Session saved = sessions.save(session);

        // PostToolUse events are very frequent: they are recorded only when they change the status
        if (!event.isPostToolUse() || statusChanged) {
            events.save(new SessionEvent(saved.getId(), event.eventName(), event.notificationType(),
                    event.message(), saved.getStatus(), event.rawPayload(), now));
        }

        SessionDto dto = SessionDto.from(saved);
        publisher.publishEvent(new SessionChangedEvent(userId, dto, statusChanged));
        return dto;
    }

    /** Session list. Without history, only non-archived sessions (ended sessions are archived automatically). */
    @Transactional(readOnly = true)
    public List<SessionDto> list(Long userId, SessionStatus status, String machine, boolean includeHistory) {
        List<Session> source = includeHistory
                ? sessions.findByUserIdOrderByLastEventAtDesc(userId, PageRequest.of(0, HISTORY_LIMIT))
                : sessions.findByUserIdAndArchivedFalseOrderByLastEventAtDesc(userId);
        return source.stream()
                .filter(s -> status == null || s.getStatus() == status)
                .filter(s -> machine == null || machine.isBlank() || machine.equalsIgnoreCase(s.getMachine()))
                .map(SessionDto::from)
                .toList();
    }

    /** The user's session; empty if it does not exist or belongs to another user. */
    @Transactional(readOnly = true)
    public Optional<SessionDto> find(Long userId, String sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId).map(SessionDto::from);
    }

    @Transactional(readOnly = true)
    public Optional<List<EventDto>> eventsOf(Long userId, String sessionId) {
        if (sessions.findByIdAndUserId(sessionId, userId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.findBySessionIdOrderByReceivedAtDescIdDesc(sessionId).stream()
                .map(EventDto::from)
                .toList());
    }

    /** Marks the session as seen and/or archives it. Null parameters are ignored. */
    @Transactional
    public Optional<SessionDto> update(Long userId, String sessionId, Boolean seen, Boolean archived) {
        return sessions.findByIdAndUserId(sessionId, userId).map(session -> {
            if (seen != null) {
                session.setSeenAt(seen ? clock.instant() : null);
            }
            if (archived != null) {
                session.setArchived(archived);
            }
            SessionDto dto = SessionDto.from(sessions.save(session));
            publisher.publishEvent(new SessionChangedEvent(userId, dto, false));
            return dto;
        });
    }

    /** Marks as STALE the active sessions with no events for longer than the configured time. */
    @Transactional
    public int markStaleSessions() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.sessions().staleAfter());
        List<Session> stale = sessions.findByStatusInAndLastEventAtBefore(STALE_CANDIDATES, threshold);
        for (Session session : stale) {
            session.changeStatus(SessionStatus.STALE, now);
            publisher.publishEvent(
                    new SessionChangedEvent(session.getUserId(), SessionDto.from(sessions.save(session)), true));
        }
        return stale.size();
    }
}
