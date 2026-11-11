package it.sartoriadelcodice.claudemonitor.stream;

import it.sartoriadelcodice.claudemonitor.auth.AuthUser;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Endpoint SSE con gli aggiornamenti in tempo reale delle sessioni. */
@RestController
public class StreamController {

    private final SessionBroadcaster broadcaster;

    public StreamController(SessionBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthUser user, HttpServletResponse response) {
        // Disattiva il buffering di nginx per questa risposta
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return broadcaster.register(user.id());
    }
}
