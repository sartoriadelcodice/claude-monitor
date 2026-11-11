package it.sartoriadelcodice.claudemonitor.stream;

import it.sartoriadelcodice.claudemonitor.push.PushNotifier;
import it.sartoriadelcodice.claudemonitor.session.SessionChangedEvent;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Dopo il commit inoltra le modifiche delle sessioni a SSE e Web Push. */
@Component
public class SessionChangeListener {

    private final SessionBroadcaster broadcaster;
    private final PushNotifier pushNotifier;

    public SessionChangeListener(SessionBroadcaster broadcaster, PushNotifier pushNotifier) {
        this.broadcaster = broadcaster;
        this.pushNotifier = pushNotifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionChanged(SessionChangedEvent event) {
        broadcaster.broadcast(event.userId(), event.session());
        if (event.statusChanged() && event.session().status().requiresAttention()) {
            pushNotifier.notifyAttention(event.userId(), event.session());
        }
    }
}
