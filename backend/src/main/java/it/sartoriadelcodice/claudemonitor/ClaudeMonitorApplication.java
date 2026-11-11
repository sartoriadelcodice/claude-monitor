package it.sartoriadelcodice.claudemonitor;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// No in-memory users: authentication goes through AuthFilter only
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class ClaudeMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaudeMonitorApplication.class, args);
    }

    /** Injectable clock, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
