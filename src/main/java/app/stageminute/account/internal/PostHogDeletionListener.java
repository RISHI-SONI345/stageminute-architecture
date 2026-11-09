package app.stageminute.account.internal;

import app.stageminute.account.Deletion;
import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.PostHogPersons;
import app.stageminute.platform.VendorDeletes;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Forgets the person in PostHog after their account is deleted (05 F-082). It runs after the commit, on its own, and
 * a failure leaves the event incomplete so the registry tries again (AD-87); a person PostHog does not know is done.
 * It sits in {@code account} because the shared kernel may not use {@code account}: the port is in the kernel, the
 * event is account's own.
 */
@Component
class PostHogDeletionListener {

    private static final String VENDOR = "posthog";

    private final PostHogPersons persons;
    private final VendorDeletes deletes;

    PostHogDeletionListener(PostHogPersons persons, VendorDeletes deletes) {
        this.persons = persons;
        this.deletes = deletes;
    }

    @ApplicationModuleListener
    void on(Deletion.Requested event) {
        DeleteOutcome outcome = persons.delete(event.user().toString());
        deletes.record(VENDOR, outcome);
    }
}
