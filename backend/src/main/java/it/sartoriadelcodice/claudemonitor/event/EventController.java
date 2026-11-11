package it.sartoriadelcodice.claudemonitor.event;

import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.JsonNode;
import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Ricezione degli eventi inviati dagli hook di Claude Code. */
@RestController
@RequestMapping("/api/events")
public class EventController {

    /**
     * Gli hook sono asincroni: due eventi della stessa sessione nuova possono arrivare insieme.
     * Il lock serializza l'elaborazione ed evita il doppio inserimento della sessione.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private final SessionService sessionService;

    public EventController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public SessionDto receive(@AuthenticationPrincipal AuthUser user, @RequestBody JsonNode body) {
        HookEvent event;
        try {
            event = HookEvent.fromJson(body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        lock.lock();
        try {
            return sessionService.handleEvent(user.id(), event);
        } finally {
            lock.unlock();
        }
    }
}
