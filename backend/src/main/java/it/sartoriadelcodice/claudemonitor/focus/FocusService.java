package it.sartoriadelcodice.claudemonitor.focus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import it.sartoriadelcodice.claudemonitor.focus.FocusAgentRegistry.AgentInfo;
import it.sartoriadelcodice.claudemonitor.session.SessionDto;
import it.sartoriadelcodice.claudemonitor.user.User;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.springframework.stereotype.Service;

/**
 * Focus delle finestre: interruttore dell'utente, che vale per tutte le sue macchine, e scelta della macchina
 * su cui aprire una sessione.
 */
@Service
public class FocusService {

    /** Attesa massima delle risposte degli agenti quando si cerca il Mac in uso. */
    static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);
    /**
     * Se il Mac della sessione non risponde (agente di una versione precedente), un altro Mac vince solo
     * se è stato usato entro questo tempo.
     */
    static final long ACTIVE_IDLE_SECONDS = 120;

    private final UserRepository users;
    private final FocusAgentRegistry registry;

    public FocusService(UserRepository users, FocusAgentRegistry registry) {
        this.users = users;
        this.registry = registry;
    }

    /** Falso anche se l'utente non esiste più: nel dubbio il focus resta spento. */
    public boolean isEnabled(Long userId) {
        return users.findById(userId).map(User::isFocusEnabled).orElse(false);
    }

    /** Accende o spegne il focus. Allo spegnimento gli agenti collegati vengono avvisati e scollegati subito. */
    public void setEnabled(Long userId, boolean enabled) {
        users.findById(userId).ifPresent(user -> {
            user.setFocusEnabled(enabled);
            users.save(user);
        });
        if (!enabled) {
            registry.disconnect(userId);
        }
    }

    /**
     * Apre la sessione sul Mac che l'utente sta usando. Senza controllo remoto è sempre la finestra sul Mac
     * della sessione. Con il controllo remoto attivo concorrono anche i Mac con l'app Claude: vince quello
     * usato per ultimo, dove la sessione si apre nell'app; a parità resta il Mac della sessione.
     *
     * @return dove è stata aperta; vuoto se nessun agente adatto è collegato
     */
    public Optional<FocusResult> focus(Long userId, SessionDto session) {
        String home = session.machine();
        List<AgentInfo> agents = registry.agents(userId);
        boolean homeConnected = agents.stream().anyMatch(agent -> agent.machine().equals(home));
        List<String> viewers = session.remoteSessionId() == null ? List.of() : agents.stream()
                .filter(agent -> agent.claudeApp() && !agent.machine().equals(home))
                .map(AgentInfo::machine)
                .toList();

        String target = home;
        if (!viewers.isEmpty()) {
            // Con una sola macchina possibile non serve chiedere chi la sta usando
            target = homeConnected || viewers.size() > 1
                    ? lastUsed(userId, home, homeConnected, viewers)
                    : viewers.getFirst();
        }
        if (!target.equals(home) && registry.openRemote(userId, target, session.remoteSessionId())) {
            return Optional.of(new FocusResult(target, true));
        }
        if (homeConnected && registry.focus(userId, home, session.id())) {
            return Optional.of(new FocusResult(home, false));
        }
        return Optional.empty();
    }

    /** Mac usato per ultimo, secondo i secondi di inattività riportati dagli agenti. */
    private String lastUsed(Long userId, String home, boolean homeConnected, List<String> viewers) {
        List<String> machines = new ArrayList<>(viewers);
        if (homeConnected) {
            machines.add(home);
        }
        Map<String, Long> idle = registry.probeIdle(userId, machines, PROBE_TIMEOUT);
        Optional<String> viewer = viewers.stream()
                .filter(idle::containsKey)
                .min(Comparator.comparing(idle::get));
        if (viewer.isEmpty()) {
            // Nessuna risposta dagli altri Mac: resta quello della sessione, se è collegato
            return homeConnected ? home : viewers.getFirst();
        }
        long viewerIdle = idle.get(viewer.get());
        Long homeIdle = idle.get(home);
        if (!homeConnected) {
            return viewer.get();
        }
        if (homeIdle != null) {
            return viewerIdle < homeIdle ? viewer.get() : home;
        }
        return viewerIdle < ACTIVE_IDLE_SECONDS ? viewer.get() : home;
    }

    /**
     * Esito del focus.
     *
     * @param machine   macchina su cui è stata aperta la sessione
     * @param claudeApp vero se è stata aperta nell'app Claude (controllo remoto) e non nella sua finestra
     */
    public record FocusResult(String machine, boolean claudeApp) {
    }
}
