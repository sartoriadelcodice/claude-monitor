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
 * Registry of the connected agents: the process that listens on each machine and brings a session's window
 * to the front. Agents are keyed by user and machine name.
 * <p>
 * Events sent to the agent, each carrying a single identifier as data:
 * <ul>
 *   <li>{@code focus}: session identifier, whose window the agent brings to the front;</li>
 *   <li>{@code remote}: Remote Control identifier, which the agent opens in the Mac's Claude app;</li>
 *   <li>{@code probe}: request identifier, to which the agent replies with the seconds elapsed since
 *       the last keyboard and mouse use ({@link #reportIdle});</li>
 *   <li>{@code disabled}: the user turned focus off.</li>
 * </ul>
 */
@Component
public class FocusAgentRegistry {

    private final Map<AgentKey, List<Agent>> agentsByKey = new ConcurrentHashMap<>();
    /** Idle-time requests awaiting a reply, by identifier. */
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Registers an agent's stream.
     *
     * @param claudeApp true if the Mac has the Claude app, where the agent can open Remote Control sessions
     */
    public SseEmitter register(Long userId, String machine, boolean claudeApp) {
        List<Agent> agents = agentsByKey.computeIfAbsent(new AgentKey(userId, machine),
                key -> new CopyOnWriteArrayList<>());
        // No server-side timeout: heartbeats keep the connection alive
        Agent agent = new Agent(new SseEmitter(0L), claudeApp);
        agent.emitter().onCompletion(() -> agents.remove(agent));
        agent.emitter().onTimeout(() -> agents.remove(agent));
        agent.emitter().onError(e -> agents.remove(agent));
        agents.add(agent);
        send(agents, agent, SseEmitter.event().comment("connected"));
        return agent.emitter();
    }

    /**
     * Asks the machine's agents to bring the session's window to the front.
     *
     * @return false if no agent of that user is connected from the machine
     */
    public boolean focus(Long userId, String machine, String sessionId) {
        return sendToAll(userId, machine, false, SseEmitter.event().name("focus").data(sessionId));
    }

    /**
     * Asks the machine's agents to open the Remote Control session in the Claude app.
     *
     * @return false if no agent able to do so is connected from the machine
     */
    public boolean openRemote(Long userId, String machine, String remoteSessionId) {
        return sendToAll(userId, machine, true, SseEmitter.event().name("remote").data(remoteSessionId));
    }

    /**
     * Asks the machines how many seconds have passed since the last keyboard and mouse use and waits for the
     * replies up to {@code timeout}. Machines that do not reply in time (or whose agent does not know the
     * request) are missing from the result.
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
                // Use the replies received so far
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
     * An agent's reply to an idle-time request.
     *
     * @return false if the request no longer exists, belongs to another user or was not addressed to that machine
     */
    public boolean reportIdle(Long userId, String probeId, String machine, long idleSeconds) {
        Probe probe = probes.get(probeId);
        if (probe == null || !probe.userId().equals(userId)) {
            return false;
        }
        CompletableFuture<Long> reply = probe.replies().get(machine);
        return reply != null && reply.complete(idleSeconds);
    }

    /** Disconnects all of the user's agents, after notifying them that focus has been turned off. */
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

    /** The user's machines with an agent currently connected, ordered by name. */
    public List<AgentInfo> agents(Long userId) {
        return agentsByKey.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId) && !entry.getValue().isEmpty())
                .map(entry -> new AgentInfo(entry.getKey().machine(),
                        entry.getValue().stream().anyMatch(Agent::claudeApp)))
                .sorted(Comparator.comparing(AgentInfo::machine))
                .toList();
    }

    /** Periodic heartbeat: keeps the connection open and detects agents that are gone. */
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

    /** Sends the event to all of the machine's agents (only those with the Claude app, if requested). */
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

    /** Agent connected from a machine. */
    public record AgentInfo(String machine, boolean claudeApp) {
    }

    private record AgentKey(Long userId, String machine) {
    }

    private record Agent(SseEmitter emitter, boolean claudeApp) {
    }

    private record Probe(Long userId, Map<String, CompletableFuture<Long>> replies) {
    }
}
