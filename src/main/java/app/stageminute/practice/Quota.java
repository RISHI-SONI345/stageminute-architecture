package app.stageminute.practice;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What {@link Practice#quota} answers for {@code GET /me} (03 F-049, F-051): the same four words as
 * {@code POST /sessions}, the sessions the person can still start in the quota day, and when that day ends.
 *
 * @param state {@link QuotaState#OK} while a session is left; otherwise {@code QUOTA_ZERO} (Free) or
 *     {@code PRO_CAP} (Pro)
 * @param sessionsLeft the cap of the quota day minus the sessions reserved or committed, never below 0
 * @param adRewardsToday ad credits held in the quota day; null for Pro, where an ad adds nothing (F-051)
 * @param adRewardsMax the most ad credits a day (setting {@code reward_grants_per_day}); null for Pro
 * @param resetsAt when the day after the quota day starts, in the stored zone: the "next one at" time
 */
public record Quota(
        QuotaState state,
        int sessionsLeft,
        @Nullable Integer adRewardsToday,
        @Nullable Integer adRewardsMax,
        Instant resetsAt) {}
