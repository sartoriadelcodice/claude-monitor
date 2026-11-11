package it.sartoriadelcodice.claudemonitor.focus;

import java.util.List;
import java.util.regex.Pattern;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import it.sartoriadelcodice.claudemonitor.focus.FocusAgentRegistry.AgentInfo;
import it.sartoriadelcodice.claudemonitor.focus.FocusService.FocusResult;
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
 * Finestra della sessione in primo piano sulla macchina che l'utente sta usando.
 * La dashboard chiede il focus; l'agente della macchina scelta, in ascolto sul proprio stream, lo esegue.
 */
@RestController
public class FocusController {

    /** Stesso limite della colonna "machine" delle sessioni. */
    private static final int MAX_MACHINE_LENGTH = 255;
    /** Identificativi delle richieste di inattività, generati da {@link FocusAgentRegistry}. */
    private static final Pattern PROBE_ID = Pattern.compile("[0-9a-f]{32}");

    private final FocusAgentRegistry registry;
    private final SessionService sessionService;
    private final FocusService focusService;

    public FocusController(FocusAgentRegistry registry, SessionService sessionService, FocusService focusService) {
        this.registry = registry;
        this.sessionService = sessionService;
        this.focusService = focusService;
    }

    /**
     * Stream SSE per l'agente di una macchina, autenticato con la chiave hook.
     *
     * @param claudeApp vero se sul Mac c'è l'app Claude: l'agente può aprirvi le sessioni in controllo remoto
     */
    @GetMapping(path = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentStream(@AuthenticationPrincipal AuthUser user, @RequestParam String machine,
                                  @RequestParam(defaultValue = "false") boolean claudeApp,
                                  HttpServletResponse response) {
        requireEnabled(user);
        String name = machineName(machine);
        // Disattiva il buffering di nginx per questa risposta
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return registry.register(user.id(), name, claudeApp);
    }

    /**
     * Risposta dell'agente a una richiesta di inattività: secondi dall'ultimo uso di tastiera e mouse.
     * 404 se la richiesta è scaduta o non era rivolta a quella macchina.
     */
    @PostMapping("/api/agent/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activity(@AuthenticationPrincipal AuthUser user, @RequestParam String probe,
                         @RequestParam String machine, @RequestParam long idle) {
        requireEnabled(user);
        if (!PROBE_ID.matcher(probe).matches() || idle < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Risposta non valida");
        }
        if (!registry.reportIdle(user.id(), probe, machineName(machine), idle)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Richiesta scaduta o sconosciuta");
        }
    }

    /** Macchine dell'utente con l'agente collegato, e quali di queste hanno l'app Claude. */
    @GetMapping("/api/focus/agents")
    public List<AgentInfo> agents(@AuthenticationPrincipal AuthUser user) {
        return focusService.isEnabled(user.id()) ? registry.agents(user.id()) : List.of();
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
     * Porta in primo piano la sessione sul Mac in uso e dice dove l'ha aperta; 403 se l'utente ha spento
     * il focus, 409 se non c'è un agente collegato che possa aprirla.
     */
    @PostMapping("/api/sessions/{id}/focus")
    public FocusResult focus(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        requireEnabled(user);
        SessionDto session = sessionService.find(user.id(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione non trovata"));
        return focusService.focus(user.id(), session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Nessun agente collegato che possa aprire la sessione"));
    }

    /** Con l'interruttore spento non si accettano né richieste di focus né agenti in ascolto. */
    private void requireEnabled(AuthUser user) {
        if (!focusService.isEnabled(user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Focus delle finestre disattivato");
        }
    }

    private static String machineName(String machine) {
        String name = machine.trim();
        if (name.isEmpty() || name.length() > MAX_MACHINE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome della macchina non valido");
        }
        return name;
    }

    public record FocusSettings(boolean enabled) {
    }
}
