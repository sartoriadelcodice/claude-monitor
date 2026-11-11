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
 * All'avvio: crea l'amministratore iniziale se non esistono utenti e gli assegna
 * i dati precedenti alla gestione multiutente (sessioni, subscription push, HOOK_TOKEN).
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
                log.warn("Nessun utente e ADMIN_PASSWORD non impostata: impossibile creare l'amministratore iniziale");
                return;
            }
            users.save(new User(auth.username(), passwordEncoder.encode(auth.password()), Role.ADMIN,
                    clock.instant()));
            log.info("Creato l'amministratore iniziale '{}'", auth.username());
        }

        // Le credenziali nelle variabili d'ambiente valgono solo per la prima creazione:
        // dopo, l'amministratore iniziale si riconosce dal nome utente
        Optional<User> admin = users.findByUsernameIgnoreCase(auth.username())
                .filter(u -> u.getRole() == Role.ADMIN);
        if (admin.isEmpty()) {
            return;
        }
        Long adminId = admin.get().getId();
        int adoptedSessions = sessions.assignOrphansTo(adminId);
        int adoptedSubscriptions = pushSubscriptions.assignOrphansTo(adminId);
        if (adoptedSessions + adoptedSubscriptions > 0) {
            log.info("Assegnati a '{}': {} sessioni e {} dispositivi push senza proprietario",
                    auth.username(), adoptedSessions, adoptedSubscriptions);
        }
        // Il vecchio token condiviso diventa una normale chiave hook dell'amministratore, revocabile
        String legacyToken = properties.hookToken();
        if (legacyToken != null && !legacyToken.isBlank()
                && hookKeys.importLegacyKey(adminId, legacyToken.trim(), "HOOK_TOKEN (.env)")) {
            log.info("HOOK_TOKEN importato come chiave hook di '{}'", auth.username());
        }
    }
}
