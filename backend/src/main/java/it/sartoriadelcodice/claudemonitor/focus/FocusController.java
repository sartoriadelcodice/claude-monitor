package it.sartoriadelcodice.claudemonitor.focus;

import java.util.Set;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.session.SessionService;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Finestra della sessione in primo piano sulla macchina che la ospita.
 * La dashboard chiede il focus; l'agente della macchina, in ascolto sul proprio stream, lo esegue.
 */
@RestController
public class FocusController {

    /** Stesso limite della colonna "machine" delle sessioni. */
    private static final int MAX_MACHINE_LENGTH = 255;

    private final FocusAgentRegistry registry;
    private final SessionService sessionService;
    private final FocusService focusService;

    public FocusController(FocusAgentRegistry registry, SessionService sessionService, FocusService focusService) {
        this.registry = registry;
        this.sessionService = sessionService;
        this.focusService = focusService;
    }

    /** Stream SSE per l'agente di una macchina, autenticato con la chiave hook. */
    @GetMapping(path = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentStream(@AuthenticationPrincipal AuthUser user, @RequestParam String machine,
                                  HttpServletResponse response) {
        requireEnabled(user);
        String name = machine.trim();
        if (name.isEmpty() || name.length() > MAX_MACHINE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome della macchina non valido");
        }
        // Disattiva il buffering di nginx per questa risposta
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return registry.register(user.id(), name);
    }

    /** Macchine dell'utente su cui la dashboard può portare una finestra in primo piano. */
    @GetMapping("/api/focus/machines")
    public Set<String> machines(@AuthenticationPrincipal AuthUser user) {
        return focusService.isEnabled(user.id()) ? registry.machines(user.id()) : Set.of();
    }

    /** Stato dell'interruttore del focus dell'utente. */
    @GetMapping("/api/focus/settings")
    public FocusSettings settings(@AuthenticationPrincipal AuthUser user) {
        return new FocusSettings(focusService.isEnabled(user.id()));
    }

    /** Accende o spegne il focus per tutte le macchine e le sessioni dell'utente. */
    @PutMapping("/api/focus/settings")
    public FocusSettings updateSettings(@AuthenticationPrincipal AuthUser user, @RequestBody FocusSettings request) {
        focusService.setEnabled(user.id(), request.enabled());
        return new FocusSettings(focusService.isEnabled(user.id()));
    }

    /**
     * Porta in primo piano la finestra della sessione; 403 se l'utente ha spento il focus,
     * 409 se sulla sua macchina non c'è un agente collegato.
     */
    @PostMapping("/api/sessions/{id}/focus")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void focus(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        requireEnabled(user);
        SessionDto session = sessionService.find(user.id(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione non trovata"));
        if (!registry.focus(user.id(), session.machine(), session.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nessun agente collegato dalla macchina");
        }
    }

    /** Con l'interruttore spento non si accettano né richieste di focus né agenti in ascolto. */
    private void requireEnabled(AuthUser user) {
        if (!focusService.isEnabled(user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Focus delle finestre disattivato");
        }
    }

    public record FocusSettings(boolean enabled) {
    }
}
