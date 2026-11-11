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
 * Window focus: the user's switch, which applies to all of their machines, and the choice of the machine
 * on which to open a session.
 */
@Service
public class FocusService {

    /** Maximum wait for the agents' replies when looking for the Mac in use. */
    static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);
    /**
     * If the session's Mac does not reply (agent from an earlier version), another Mac wins only
     * if it was used within this time.
     */
    static final long ACTIVE_IDLE_SECONDS = 120;

    private final UserRepository users;
    private final FocusAgentRegistry registry;

    public FocusService(UserRepository users, FocusAgentRegistry registry) {
        this.users = users;
        this.registry = registry;
    }

    /** Also false if the user no longer exists: when in doubt, focus stays off. */
    public boolean isEnabled(Long userId) {
        return users.findById(userId).map(User::isFocusEnabled).orElse(false);
    }

    /** Turns focus on or off. When it is turned off, connected agents are notified and disconnected at once. */
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
     * Opens the session on the Mac the user is using. Without Remote Control it is always the window on the
     * session's Mac. With Remote Control active, Macs with the Claude app compete too: the one used last wins,
     * and the session opens in the app there; on a tie the session's Mac is kept.
     *
     * @return where it was opened; empty if no suitable agent is connected
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
            // With only one possible machine there is no need to ask which one is in use
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

    /** Mac used last, according to the idle seconds reported by the agents. */
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
            // No reply from the other Macs: keep the session's Mac, if it is connected
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
     * Outcome of the focus.
     *
     * @param machine   machine on which the session was opened
     * @param claudeApp true if it was opened in the Claude app (Remote Control) rather than in its own window
     */
    public record FocusResult(String machine, boolean claudeApp) {
    }
}
