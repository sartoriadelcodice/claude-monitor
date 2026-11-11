package it.sartoriadelcodice.claudemonitor.session;

import java.util.List;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.event.EventDto;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** Elenco sessioni, con filtri opzionali per stato e macchina. */
    @GetMapping
    public List<SessionDto> list(@AuthenticationPrincipal AuthUser user,
                                 @RequestParam(required = false) SessionStatus status,
                                 @RequestParam(required = false) String machine,
                                 @RequestParam(defaultValue = "false") boolean includeHistory) {
        return sessionService.list(user.id(), status, machine, includeHistory);
    }

    /** Cronologia eventi della sessione, dal più recente. */
    @GetMapping("/{id}/events")
    public List<EventDto> events(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        return sessionService.eventsOf(user.id(), id).orElseThrow(SessionController::notFound);
    }

    /** Segna come vista o archivia la sessione. */
    @PatchMapping("/{id}")
    public SessionDto update(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
                             @RequestBody UpdateRequest request) {
        return sessionService.update(user.id(), id, request.seen(), request.archived())
                .orElseThrow(SessionController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione non trovata");
    }

    public record UpdateRequest(Boolean seen, Boolean archived) {
    }
}
