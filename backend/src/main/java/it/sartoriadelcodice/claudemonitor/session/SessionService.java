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

/** Gestione delle sessioni: applicazione degli eventi, elenco, vista/archiviazione, scadenza. */
@Service
public class SessionService {

    /** Numero massimo di sessioni restituite quando si chiede anche lo storico. */
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
     * Applica un evento ricevuto da un hook, creando la sessione se non esiste.
     * La sessione appartiene all'utente della chiave hook; un altro utente non può aggiornarla.
     */
    @Transactional
    public SessionDto handleEvent(Long userId, HookEvent event) {
        Instant now = clock.instant();
        Optional<Session> existing = sessions.findById(event.sessionId());
        if (existing.isPresent() && !userId.equals(existing.get().getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La sessione appartiene a un altro utente");
        }
        Session session = existing.orElseGet(
                () -> new Session(event.sessionId(), userId, event.machine(), event.project(), now));
        SessionStatus current = existing.map(Session::getStatus).orElse(null);

        session.setLastEventAt(now);
        session.setMachine(event.machine());
        // Progetto e cartella restano quelli dell'avvio: il cwd degli eventi successivi cambia a ogni "cd"
        // di Claude e farebbe cambiare nome alla card a metà lavoro
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

        boolean statusChanged = mapper.map(event.eventName(), event.notificationType(), event.source(),
                        event.backgroundWork(), event.subagent(), current)
                .map(next -> session.changeStatus(next, now))
                .orElse(false);
        // Una sessione nuova conta come cambiamento anche se resta nello stato iniziale IDLE
        statusChanged = statusChanged || existing.isEmpty();

        if (event.isNotification()) {
            session.setLastNotificationMessage(event.message());
            session.setLastNotificationType(event.notificationType());
        } else if (statusChanged && session.getStatus() == SessionStatus.WORKING) {
            // Ripartito il lavoro, il vecchio messaggio non è più attuale (resta nella cronologia)
            session.setLastNotificationMessage(null);
            session.setLastNotificationType(null);
        }
        // Un evento su una sessione archiviata la riporta nella vista principale
        if (session.isArchived() && statusChanged) {
            session.setArchived(false);
        }
        // Una sessione chiusa viene archiviata subito: esce dalla vista principale e resta nello storico
        if (statusChanged && session.getStatus() == SessionStatus.ENDED) {
            session.setArchived(true);
        }

        Session saved = sessions.save(session);

        // I PostToolUse sono molto frequenti: si registrano solo quando cambiano lo stato
        if (!event.isPostToolUse() || statusChanged) {
            events.save(new SessionEvent(saved.getId(), event.eventName(), event.notificationType(),
                    event.message(), saved.getStatus(), event.rawPayload(), now));
        }

        SessionDto dto = SessionDto.from(saved);
        publisher.publishEvent(new SessionChangedEvent(userId, dto, statusChanged));
        return dto;
    }

    /** Elenco sessioni. Senza storico solo quelle non archiviate (le sessioni chiuse si archiviano da sole). */
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

    @Transactional(readOnly = true)
    public Optional<List<EventDto>> eventsOf(Long userId, String sessionId) {
        if (sessions.findByIdAndUserId(sessionId, userId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.findBySessionIdOrderByReceivedAtDescIdDesc(sessionId).stream()
                .map(EventDto::from)
                .toList());
    }

    /** Segna la sessione come vista e/o la archivia. I parametri null vengono ignorati. */
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

    /** Marca STALE le sessioni attive senza eventi da più del tempo configurato. */
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
