package it.sartoriadelcodice.claudemonitor.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application configuration, populated from environment variables (see application.yml).
 * hookToken is the old shared token: if present, it is imported at startup as a hook key of the
 * initial administrator. The credentials in auth are only used to create that administrator.
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(String hookToken, Auth auth, Sessions sessions, Push push) {

    public record Auth(String username, String password, String jwtSecret, Duration jwtTtl) {
    }

    public record Sessions(Duration staleAfter) {
    }

    public record Push(String vapidPublicKey, String vapidPrivateKey, String vapidSubject) {

        public boolean enabled() {
            return vapidPublicKey != null && !vapidPublicKey.isBlank()
                    && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
        }
    }
}
