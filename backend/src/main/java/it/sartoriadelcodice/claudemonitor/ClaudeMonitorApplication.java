package it.sartoriadelcodice.claudemonitor;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Nessun utente in memoria: l'autenticazione passa solo da AuthFilter
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class ClaudeMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaudeMonitorApplication.class, args);
    }

    /** Orologio iniettabile, così i test possono controllare il tempo. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
