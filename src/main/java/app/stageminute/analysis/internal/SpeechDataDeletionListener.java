package app.stageminute.analysis.internal;

import app.stageminute.account.Deletion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the person's words from server memory the moment their account is deleted (05 F-082, rule 4). The retry cache
 * keeps {@code speech_data} for ten minutes after a take; without this, a person who deleted the account right after a
 * take would leave their words in memory until the window ended. It runs on the deleting thread just after the commit,
 * so the answer to {@code DELETE /v1/me} is sent only after the words are gone.
 */
@Component
class SpeechDataDeletionListener {

    private final SpeechDataCache cache;

    SpeechDataDeletionListener(SpeechDataCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(Deletion.Requested event) {
        cache.forget(event.user());
    }
}
