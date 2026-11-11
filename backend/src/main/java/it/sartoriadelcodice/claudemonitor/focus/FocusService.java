package it.sartoriadelcodice.claudemonitor.focus;

import it.sartoriadelcodice.claudemonitor.user.User;
import it.sartoriadelcodice.claudemonitor.user.UserRepository;

import org.springframework.stereotype.Service;

/** Interruttore del focus delle finestre: una scelta dell'utente che vale per tutte le sue macchine. */
@Service
public class FocusService {

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
}
