package it.sartoriadelcodice.claudemonitor.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione applicativa, valorizzata da variabili d'ambiente (vedi application.yml).
 * hookToken è il vecchio token condiviso: se presente viene importato all'avvio come chiave hook
 * dell'amministratore iniziale. Le credenziali in auth servono solo a creare quell'amministratore.
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
