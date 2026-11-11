package it.sartoriadelcodice.claudemonitor.user;

import java.time.Clock;
import java.util.Optional;

import it.sartoriadelcodice.claudemonitor.config.MonitorProperties;
import it.sartoriadelcodice.claudemonitor.hookkey.HookKeyService;
import it.sartoriadelcodice.claudemonitor.push.PushSubscriptionRepository;
import it.sartoriadelcodice.claudemonitor.session.SessionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * At startup: creates the initial administrator if no users exist and assigns them
 * the data that predates multi-user support (sessions, push subscriptions, HOOK_TOKEN).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PushSubscriptionRepository pushSubscriptions;
    private final HookKeyService hookKeys;
    private final PasswordEncoder passwordEncoder;
    private final MonitorProperties properties;
    private final Clock clock;

    public AdminBootstrap(UserRepository users, SessionRepository sessions,
                          PushSubscriptionRepository pushSubscriptions, HookKeyService hookKeys,
                          PasswordEncoder passwordEncoder, MonitorProperties properties, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.pushSubscriptions = pushSubscriptions;
        this.hookKeys = hookKeys;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var auth = properties.auth();
        if (users.count() == 0) {
            if (auth.password() == null || auth.password().isBlank()) {
                log.warn("No users and ADMIN_PASSWORD not set: unable to create the initial administrator");
                return;
            }
            users.save(new User(auth.username(), passwordEncoder.encode(auth.password()), Role.ADMIN,
                    clock.instant()));
            log.info("Created the initial administrator '{}'", auth.username());
        }

        // The credentials in the environment variables only apply to the first creation:
        // afterwards, the initial administrator is recognized by the username
        Optional<User> admin = users.findByUsernameIgnoreCase(auth.username())
                .filter(u -> u.getRole() == Role.ADMIN);
        if (admin.isEmpty()) {
            return;
        }
        Long adminId = admin.get().getId();
        int adoptedSessions = sessions.assignOrphansTo(adminId);
        int adoptedSubscriptions = pushSubscriptions.assignOrphansTo(adminId);
        if (adoptedSessions + adoptedSubscriptions > 0) {
            log.info("Assigned to '{}': {} sessions and {} push devices without an owner",
                    auth.username(), adoptedSessions, adoptedSubscriptions);
        }
        // The old shared token becomes a regular, revocable hook key of the administrator
        String legacyToken = properties.hookToken();
        if (legacyToken != null && !legacyToken.isBlank()
                && hookKeys.importLegacyKey(adminId, legacyToken.trim(), "HOOK_TOKEN (.env)")) {
            log.info("HOOK_TOKEN imported as a hook key of '{}'", auth.username());
        }
    }
}
