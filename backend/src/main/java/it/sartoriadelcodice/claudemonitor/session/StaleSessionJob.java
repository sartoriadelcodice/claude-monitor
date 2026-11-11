package it.sartoriadelcodice.claudemonitor.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Controllo periodico delle sessioni senza eventi recenti. */
@Component
public class StaleSessionJob {

    private static final Logger log = LoggerFactory.getLogger(StaleSessionJob.class);

    private final SessionService sessionService;

    public StaleSessionJob(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void run() {
        int marked = sessionService.markStaleSessions();
        if (marked > 0) {
            log.info("Sessioni marcate STALE: {}", marked);
        }
    }
}
