package app.stageminute.progress;

import app.stageminute.progress.internal.ProgressRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator marks a time when the server was down (03 F-053, EC-111). Every person with a failed start logged
 * inside it gets that day frozen for the streak. The admin console that calls this is #27; there is no endpoint yet.
 */
@Service
public class OutageWindows {

    private final ProgressRepository repository;

    OutageWindows(ProgressRepository repository) {
        this.repository = repository;
    }

    /**
     * Records the window and freezes the day of each failed start inside it. A start logged later, inside a window
     * that is already marked, is frozen as it is logged. Running it again with the same times changes nothing.
     *
     * @param from the first moment of the outage
     * @param to the last moment of the outage
     * @param createdBy the operator, by an opaque id
     * @return how many person-days were newly frozen
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    @Transactional
    public int mark(Instant from, Instant to, String createdBy) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("the window ends before it starts");
        }
        repository.insertWindow(from, to, createdBy);
        return repository.freezeFailedStartsBetween(from, to);
    }
}
