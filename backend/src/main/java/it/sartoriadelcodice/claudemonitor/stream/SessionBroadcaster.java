package it.sartoriadelcodice.claudemonitor.stream;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import it.sartoriadelcodice.claudemonitor.session.SessionDto;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Registry of the connected SSE clients, by user, and delivery of updates. */
@Component
public class SessionBroadcaster {

    private final Map<Long, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        List<SseEmitter> emitters = emittersByUser.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());
        // No server-side timeout: heartbeats keep the connection alive
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitters, emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    /** Sends the update only to the clients of the session's owner. */
    public void broadcast(Long userId, SessionDto session) {
        List<SseEmitter> emitters = emittersByUser.getOrDefault(userId, List.of());
        for (SseEmitter emitter : emitters) {
            send(emitters, emitter, SseEmitter.event().name("session").data(session, MediaType.APPLICATION_JSON));
        }
    }

    /** Periodic heartbeat: prevents proxies and tunnels from closing the idle connection. */
    @Scheduled(fixedDelayString = "PT20S")
    public void heartbeat() {
        for (List<SseEmitter> emitters : emittersByUser.values()) {
            for (SseEmitter emitter : emitters) {
                send(emitters, emitter, SseEmitter.event().comment("ping"));
            }
        }
    }

    private void send(List<SseEmitter> emitters, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
