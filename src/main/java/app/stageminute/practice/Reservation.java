package app.stageminute.practice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What {@link Practice#reserve} answers. With {@link QuotaState#OK} it carries the session; with any other
 * state nothing was reserved and {@code sessionId} and {@code source} are null.
 *
 * @param day the account day the session counts on
 * @param resetsAt when the next account day starts: the {@code resets_at} of a refusal
 * @param firstSession true when no other session of this account is reserved or committed and this device
 *     has never finished a first session: the ad-free one (D-31). It is used up only at commit.
 * @param repeated true when the same take was sent again and the stored session came back, counted once
 */
public record Reservation(
        QuotaState state,
        @Nullable UUID sessionId,
        LocalDate day,
        Instant resetsAt,
        @Nullable Source source,
        boolean firstSession,
        boolean repeated) {

    /** What paid for the session; the {@code practice_sessions.source} column holds {@link #wire()}. */
    public enum Source {
        FREE,
        REWARDED,
        PRO;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
