package it.sartoriadelcodice.claudemonitor.focus;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registro degli agenti collegati: il processo che su ogni macchina resta in ascolto e porta in primo piano
 * la finestra di una sessione. Gli agenti sono distinti per utente e nome della macchina.
 */
@Component
public class FocusAgentRegistry {

    private final Map<AgentKey, List<SseEmitter>> emittersByAgent = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId, String machine) {
        List<SseEmitter> emitters =
                emittersByAgent.computeIfAbsent(new AgentKey(userId, machine), key -> new CopyOnWriteArrayList<>());
        // Nessun timeout lato server: la connessione resta viva grazie agli heartbeat
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitters, emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    /**
     * Chiede agli agenti della macchina di portare in primo piano la finestra della sessione.
     *
     * @return false se nessun agente di quell'utente è collegato dalla macchina
     */
    public boolean focus(Long userId, String machine, String sessionId) {
        List<SseEmitter> emitters = emittersByAgent.getOrDefault(new AgentKey(userId, machine), List.of());
        boolean delivered = false;
        for (SseEmitter emitter : emitters) {
            delivered |= send(emitters, emitter, SseEmitter.event().name("focus").data(sessionId));
        }
        return delivered;
    }

    /** Scollega tutti gli agenti dell'utente, dopo averli avvisati che il focus è stato spento. */
    public void disconnect(Long userId) {
        emittersByAgent.forEach((key, emitters) -> {
            if (!key.userId().equals(userId)) {
                return;
            }
            for (SseEmitter emitter : emitters) {
                if (send(emitters, emitter, SseEmitter.event().name("disabled").data("1"))) {
                    emitter.complete();
                }
                emitters.remove(emitter);
            }
        });
    }

    /** Macchine dell'utente con un agente collegato in questo momento. */
    public Set<String> machines(Long userId) {
        return emittersByAgent.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId) && !entry.getValue().isEmpty())
                .map(entry -> entry.getKey().machine())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Heartbeat periodico: tiene aperta la connessione e scopre gli agenti che non ci sono più. */
    @Scheduled(fixedDelayString = "PT20S")
    public void heartbeat() {
        for (List<SseEmitter> emitters : emittersByAgent.values()) {
            for (SseEmitter emitter : emitters) {
                send(emitters, emitter, SseEmitter.event().comment("ping"));
            }
        }
    }

    private boolean send(List<SseEmitter> emitters, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            return false;
        }
    }

    private record AgentKey(Long userId, String machine) {
    }
}
