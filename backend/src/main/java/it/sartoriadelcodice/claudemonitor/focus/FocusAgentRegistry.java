package it.sartoriadelcodice.claudemonitor.focus;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registro degli agenti collegati: il processo che su ogni macchina resta in ascolto e porta in primo piano
 * la finestra di una sessione. Gli agenti sono distinti per utente e nome della macchina.
 * <p>
 * Eventi verso l'agente, ognuno con un solo identificativo come dato:
 * <ul>
 *   <li>{@code focus}: identificativo della sessione, di cui l'agente porta avanti la finestra;</li>
 *   <li>{@code remote}: identificativo del controllo remoto, che l'agente apre nell'app Claude del Mac;</li>
 *   <li>{@code probe}: identificativo di una richiesta, a cui l'agente risponde con i secondi trascorsi
 *       dall'ultimo uso di tastiera e mouse ({@link #reportIdle});</li>
 *   <li>{@code disabled}: l'utente ha spento il focus.</li>
 * </ul>
 */
@Component
public class FocusAgentRegistry {

    private final Map<AgentKey, List<Agent>> agentsByKey = new ConcurrentHashMap<>();
    /** Richieste di inattività in attesa di risposta, per identificativo. */
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Registra lo stream di un agente.
     *
     * @param claudeApp vero se sul Mac c'è l'app Claude, dove l'agente sa aprire le sessioni in controllo remoto
     */
    public SseEmitter register(Long userId, String machine, boolean claudeApp) {
        List<Agent> agents = agentsByKey.computeIfAbsent(new AgentKey(userId, machine),
                key -> new CopyOnWriteArrayList<>());
        // Nessun timeout lato server: la connessione resta viva grazie agli heartbeat
        Agent agent = new Agent(new SseEmitter(0L), claudeApp);
        agent.emitter().onCompletion(() -> agents.remove(agent));
        agent.emitter().onTimeout(() -> agents.remove(agent));
        agent.emitter().onError(e -> agents.remove(agent));
        agents.add(agent);
        send(agents, agent, SseEmitter.event().comment("connected"));
        return agent.emitter();
    }

    /**
     * Chiede agli agenti della macchina di portare in primo piano la finestra della sessione.
     *
     * @return false se nessun agente di quell'utente è collegato dalla macchina
     */
    public boolean focus(Long userId, String machine, String sessionId) {
        return sendToAll(userId, machine, false, SseEmitter.event().name("focus").data(sessionId));
    }

    /**
     * Chiede agli agenti della macchina di aprire nell'app Claude la sessione in controllo remoto.
     *
     * @return false se dalla macchina non è collegato un agente in grado di farlo
     */
    public boolean openRemote(Long userId, String machine, String remoteSessionId) {
        return sendToAll(userId, machine, true, SseEmitter.event().name("remote").data(remoteSessionId));
    }

    /**
     * Chiede alle macchine quanti secondi sono passati dall'ultimo uso di tastiera e mouse e aspetta le
     * risposte fino a {@code timeout}. Le macchine che non rispondono in tempo (o con un agente che non
     * conosce la richiesta) mancano dal risultato.
     */
    public Map<String, Long> probeIdle(Long userId, Collection<String> machines, Duration timeout) {
        String probeId = HexFormat.of().formatHex(randomBytes());
        Map<String, CompletableFuture<Long>> replies = new HashMap<>();
        for (String machine : machines) {
            replies.put(machine, new CompletableFuture<>());
        }
        probes.put(probeId, new Probe(userId, replies));
        try {
            List<CompletableFuture<Long>> pending = machines.stream()
                    .filter(machine -> sendToAll(userId, machine, false,
                            SseEmitter.event().name("probe").data(probeId)))
                    .map(replies::get)
                    .toList();
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // Si usano le risposte arrivate fin qui
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Map<String, Long> idle = new HashMap<>();
            replies.forEach((machine, reply) -> {
                if (reply.isDone() && !reply.isCompletedExceptionally()) {
                    idle.put(machine, reply.join());
                }
            });
            return idle;
        } finally {
            probes.remove(probeId);
        }
    }

    /**
     * Risposta di un agente a una richiesta di inattività.
     *
     * @return false se la richiesta non esiste più, è di un altro utente o non era rivolta a quella macchina
     */
    public boolean reportIdle(Long userId, String probeId, String machine, long idleSeconds) {
        Probe probe = probes.get(probeId);
        if (probe == null || !probe.userId().equals(userId)) {
            return false;
        }
        CompletableFuture<Long> reply = probe.replies().get(machine);
        return reply != null && reply.complete(idleSeconds);
    }

    /** Scollega tutti gli agenti dell'utente, dopo averli avvisati che il focus è stato spento. */
    public void disconnect(Long userId) {
        agentsByKey.forEach((key, agents) -> {
            if (!key.userId().equals(userId)) {
                return;
            }
            for (Agent agent : agents) {
                if (send(agents, agent, SseEmitter.event().name("disabled").data("1"))) {
                    agent.emitter().complete();
                }
                agents.remove(agent);
            }
        });
    }

    /** Macchine dell'utente con un agente collegato in questo momento, in ordine di nome. */
    public List<AgentInfo> agents(Long userId) {
        return agentsByKey.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId) && !entry.getValue().isEmpty())
                .map(entry -> new AgentInfo(entry.getKey().machine(),
                        entry.getValue().stream().anyMatch(Agent::claudeApp)))
                .sorted(Comparator.comparing(AgentInfo::machine))
                .toList();
    }

    /** Heartbeat periodico: tiene aperta la connessione e scopre gli agenti che non ci sono più. */
    @Scheduled(fixedDelayString = "PT20S")
    public void heartbeat() {
        for (List<Agent> agents : agentsByKey.values()) {
            for (Agent agent : agents) {
                send(agents, agent, SseEmitter.event().comment("ping"));
            }
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return bytes;
    }

    /** Invia l'evento a tutti gli agenti della macchina (solo a quelli con l'app Claude, se richiesto). */
    private boolean sendToAll(Long userId, String machine, boolean claudeAppOnly,
                              SseEmitter.SseEventBuilder event) {
        List<Agent> agents = agentsByKey.getOrDefault(new AgentKey(userId, machine), List.of());
        boolean delivered = false;
        for (Agent agent : agents) {
            if (!claudeAppOnly || agent.claudeApp()) {
                delivered |= send(agents, agent, event);
            }
        }
        return delivered;
    }

    private boolean send(List<Agent> agents, Agent agent, SseEmitter.SseEventBuilder event) {
        try {
            agent.emitter().send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            agents.remove(agent);
            return false;
        }
    }

    /** Agente collegato da una macchina. */
    public record AgentInfo(String machine, boolean claudeApp) {
    }

    private record AgentKey(Long userId, String machine) {
    }

    private record Agent(SseEmitter emitter, boolean claudeApp) {
    }

    private record Probe(Long userId, Map<String, CompletableFuture<Long>> replies) {
    }
}
