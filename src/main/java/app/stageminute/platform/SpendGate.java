package app.stageminute.platform;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The question {@code practice} asks before it reserves a session: may this person start one, given what the AI has
 * cost today (03 F-087)? {@code analysis} answers it (it owns the spend counter); {@code practice} may not import
 * {@code analysis}, so the question lives here in the shared kernel, like {@link DailyCaps}.
 *
 * <p>This is the only place the plan is read for the throttle. The score never reads it.
 */
public interface SpendGate {

    /** The plan as the throttle needs it: free users first, then Pro. */
    enum Tier {
        FREE,
        PRO
    }

    /** Never throws: when the day's spend cannot be read the answer is the paused one (fail closed). */
    SpendDecision decide(Tier tier);

    /** When a pause ends: the start of the next UTC day (the counter row is per UTC date, so no job lifts it). */
    static Instant pauseEnds(Instant now) {
        return now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /**
     * The one way to refuse because of the throttle: {@code 503 throttled} carrying when the pause ends under both names
     * (the built app reads {@code resets_at} on every refusal; {@code 14-STATES-IN-WORDS} calls it {@code resumes_at}),
     * plus any {@code extra} fields the answer needs, such as {@code session_given_back}.
     */
    static BusinessRefusal throttled(Instant resumesAt, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(extra);
        body.put("resets_at", resumesAt.toString());
        body.put("resumes_at", resumesAt.toString());
        return new BusinessRefusal(Reason.THROTTLED, body);
    }
}
