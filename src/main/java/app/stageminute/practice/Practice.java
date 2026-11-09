package app.stageminute.practice;

import app.stageminute.account.AccountCalendar;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.IdentifierHasher;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate;
import app.stageminute.platform.UserId;
import app.stageminute.platform.Uuids;
import app.stageminute.practice.internal.AllowanceRule;
import app.stageminute.practice.internal.DailyCountersRepository;
import app.stageminute.practice.internal.DeviceFlagsRepository;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPort;
import app.stageminute.practice.internal.QuotaEventKind;
import app.stageminute.practice.internal.QuotaLedgerRepository;
import app.stageminute.practice.internal.SessionsRepository;
import app.stageminute.practice.internal.SessionsRepository.SessionForAnalysis;
import app.stageminute.practice.internal.SessionsRepository.StoredSession;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sessions and the quota (03 F-048 to F-052). The server is the only counter: every number here comes from
 * the database and {@code Settings}, the day from {@link AccountCalendar} and the injected clock.
 *
 * <p>A reserve is one transaction (AD-11, AD-106): make the day's counter row if needed, take one session
 * with the conditional update (never read, check, write), add the {@code practice_sessions} row and the
 * {@code reserve} ledger row. A failure at any step leaves none of them.
 */
@Service
public class Practice {

    private static final Logger log = LoggerFactory.getLogger(Practice.class);
    /** The give-back reasons that are our fault, so the session still counts for the streak (D-38). */
    private static final List<String> OUR_FAILURES = Arrays.stream(Failure.values())
            .filter(failure -> failure.cause() == Failure.Cause.SERVER_CONFIRMED)
            .map(Failure::wire)
            .toList();

    private static final int LEDGER_RESERVE = 1;
    private static final int LEDGER_COMMIT = 0;
    private static final int LEDGER_RETURN = -1;
    private static final String RESULT_AFTER_RELEASE = "result_after_release";
    private static final String TIMEOUT_REASON = "timeout_48h";
    private static final String RELEASED = "released";
    private static final String SESSION_ENDED = "session_ended";
    private static final int RETRY_AFTER_S = 5;

    /** Why the app or the person ended a reserved session before a scored result (06-API section 5, F-037). */
    public enum ReleaseReason {
        DISCARD,
        CANCEL,
        ABANDON,
        NO_LOCAL_TAKE,
        NO_SCORE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A failure that stopped a take from getting its result, and whose side it was on (D-38, D-72). */
    public enum Failure {
        SERVER_ERROR(Cause.SERVER_CONFIRMED),
        SERVER_TIMEOUT(Cause.SERVER_CONFIRMED),
        ANALYSIS_UNAVAILABLE(Cause.SERVER_CONFIRMED),
        /** The AI-cost meter refused a vendor call after the session was reserved (03 F-087, v8-15h). */
        THROTTLED(Cause.SERVER_CONFIRMED),
        CLIENT_TIMEOUT(Cause.UNCONFIRMED),
        UPLOAD_ABANDONED(Cause.UNCONFIRMED),
        FILE_TOO_LARGE(Cause.USER_SIDE),
        UNSUPPORTED_FORMAT(Cause.USER_SIDE),
        CONSENT_OFF(Cause.USER_SIDE);

        /** Server-confirmed: given back, no cap. Unconfirmed: given back up to the daily cap. User-side: never. */
        public enum Cause {
            SERVER_CONFIRMED,
            UNCONFIRMED,
            USER_SIDE
        }

        private final Cause cause;

        Failure(Cause cause) {
            this.cause = cause;
        }

        public Cause cause() {
            return cause;
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * We refused a start or failed a take: the person did nothing wrong (03 F-053). {@code progress} listens: a
     * throttled start freezes that day for the streak at once (D-78), and the others are logged for the operator's
     * outage window.
     *
     * @param user whose start or take it was
     * @param day the account day it happened on
     * @param kind what happened
     * @param at when
     */
    public record StartRefused(UserId user, LocalDate day, Kind kind, Instant at) {

        /** The three kinds {@code failed_start_log} keeps. */
        public enum Kind {
            THROTTLED("throttled"),
            SERVER_ERROR("5xx"),
            UNAVAILABLE("unavailable");

            private final String wire;

            Kind(String wire) {
                this.wire = wire;
            }

            public String wire() {
                return wire;
            }
        }
    }

    /** What the totals show (03 F-055). */
    public record SessionTotals(int sessions, long speakingMs) {}

    /**
     * A session an analysis may run for (C-52): its topic and style, the scored attempts so far (the new take is
     * attempt {@code scoredAttempts + 1} if it is scored), and whether the session is already committed.
     */
    public record AnalysisStart(UUID sessionId, String topicId, String style, int scoredAttempts, boolean committed) {}

    private final AccountCalendar calendar;
    private final TopicCatalog topics;
    private final PlanPort plans;
    private final Settings settings;
    private final IdentifierHasher hasher;
    private final DailyCountersRepository counters;
    private final SessionsRepository sessions;
    private final QuotaLedgerRepository ledger;
    private final DeviceFlagsRepository deviceFlags;
    private final SpendGate spendGate;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    Practice(
            AccountCalendar calendar,
            TopicCatalog topics,
            PlanPort plans,
            Settings settings,
            IdentifierHasher hasher,
            DailyCountersRepository counters,
            SessionsRepository sessions,
            QuotaLedgerRepository ledger,
            DeviceFlagsRepository deviceFlags,
            SpendGate spendGate,
            TransactionTemplate tx,
            Clock clock,
            ApplicationEventPublisher events) {
        this.calendar = calendar;
        this.topics = topics;
        this.plans = plans;
        this.settings = settings;
        this.hasher = hasher;
        this.counters = counters;
        this.sessions = sessions;
        this.ledger = ledger;
        this.deviceFlags = deviceFlags;
        this.spendGate = spendGate;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Reserves one session for the first take of a topic, after the recorder opened (D-66). Safe to repeat:
     * the same {@code takeKey} returns the stored session and counts nothing again.
     *
     * @param deviceId the phone's {@code X-Device-Id}, or null when it sent none (then only the account
     *     decides whether this is the ad-free first session)
     * @throws BusinessRefusal {@code validation_failed} for a topic id the catalog does not know
     */
    public Reservation reserve(UserId user, String takeKey, String topicId, @Nullable String deviceId) {
        try {
            return reserveOnce(user, takeKey, topicId, deviceId);
        } catch (BusinessRefusal refusal) {
            throw refusal;
        } catch (RuntimeException failure) {
            refusedByUs(user, StartRefused.Kind.SERVER_ERROR); // our fault: the outage freeze reads this (F-053)
            throw failure;
        }
    }

    private Reservation reserveOnce(UserId user, String takeKey, String topicId, @Nullable String deviceId) {
        Optional<StoredSession> stored = sessions.findByTakeKey(user, takeKey);
        if (stored.isPresent()) {
            return repeated(user, stored.get(), deviceId);
        }
        Topic topic = topics.get(topicId).orElseThrow(() -> new BusinessRefusal(Reason.VALIDATION_FAILED));
        // The AI-cost throttle is asked BEFORE anything is reserved (03 F-087): a paused free person costs no session,
        // no counter row and no ledger row. The plan is read here only to say which ceiling applies.
        Plan plan = plans.planOf(user);
        SpendDecision spend = spendGate.decide(tierOf(plan));
        if (spend.kind() == SpendDecision.Kind.FREE_PAUSED) {
            LocalDate day = quotaDay(user);
            plans.creditBackAfterThrottle(user, day); // "ads offset cost; they do not bypass the ceiling"
            announce(new StartRefused(user, day, StartRefused.Kind.THROTTLED, clock.instant()));
            return new Reservation(
                    QuotaState.THROTTLED, null, day, SpendGate.pauseEnds(clock.instant()), null, false, false);
        }
        Reservation reservation = tx.execute(status -> {
            Optional<Reservation> made = tryReserve(user, takeKey, topic, deviceId, plan, spend);
            if (made.isEmpty()) {
                status.setRollbackOnly(); // the same take won a parallel call: undo our count
            }
            return made.orElse(null);
        });
        if (reservation != null) {
            return reservation;
        }
        return repeated(user, sessions.findByTakeKey(user, takeKey).orElseThrow(), deviceId);
    }

    /**
     * What {@code GET /me} shows about today's sessions (F-049, F-051). It counts on the same quota day, the
     * same cap and the same words as {@link #reserve}, so the two can never disagree, and it writes nothing.
     * The ad fields are filled for the Free plan only: for Pro an ad adds nothing and none is ever shown.
     */
    public Quota quota(UserId user) {
        LocalDate day = quotaDay(user);
        Plan plan = plans.planOf(user);
        SpendDecision spend = spendGate.decide(tierOf(plan));
        if (spend.kind() == SpendDecision.Kind.FREE_PAUSED) {
            // 03 F-087: the calm "Feedback is paused" board, no ad row, until the next UTC midnight.
            return new Quota(QuotaState.THROTTLED, 0, null, null, SpendGate.pauseEnds(clock.instant()));
        }
        int adCredits = plans.adCreditsToday(user, day);
        AllowanceRule rule = allowanceRule();
        int left = Math.max(0, capOf(rule, plan, adCredits, spend) - counters.sessionsUsed(user, day));
        QuotaState state = left > 0 ? QuotaState.OK : rule.refusal(plan);
        boolean free = plan == Plan.FREE;
        return new Quota(
                state,
                left,
                free ? Math.max(0, adCredits) : null,
                free ? settings.get(SettingKey.REWARD_GRANTS_PER_DAY) : null,
                resetsAfter(user, day, spend));
    }

    /**
     * Commits a session when its first scored result is delivered (F-048), with a {@code commit} ledger row
     * of 0 (AD-107). Doing it again changes nothing. A result for a session that was already released still
     * counts: the session is taken again with no cap, because the analysis was done (AD-109). The ad-free
     * first session is used up here, so a failed first session keeps it (founder, 2026-10-28).
     *
     * @return true only for the commit that used up the ad-free first session
     */
    public boolean commit(UserId user, UUID sessionId, @Nullable String deviceId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            Optional<LocalDate> day = sessions.commitReserved(user, sessionId, now);
            if (day.isEmpty()) {
                day = sessions.commitReleased(user, sessionId, now);
                if (day.isEmpty()) {
                    return false; // already committed, unknown, or someone else's
                }
                counters.addSession(user, day.get());
                ledger.append(
                        user, day.get(), QuotaEventKind.RESERVE, LEDGER_RESERVE, sessionId, RESULT_AFTER_RELEASE, now);
            }
            ledger.append(user, day.get(), QuotaEventKind.COMMIT, LEDGER_COMMIT, sessionId, null, now);
            return useFirstSession(user, sessionId, deviceId, day.get(), now);
        }));
    }

    /**
     * Releases a reserved session the person or the app ended before a scored result (06-API section 5): the
     * session goes back to today's count with a {@code release} ledger row of -1. A release after commit, or
     * a second release, changes nothing.
     *
     * @return true when the session was released by this call
     */
    public boolean release(UserId user, UUID sessionId, ReleaseReason reason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            Optional<LocalDate> day = sessions.releaseReserved(user, sessionId, reason.wire(), now, false);
            if (day.isEmpty()) {
                return false;
            }
            counters.returnSession(user, day.get());
            ledger.append(user, day.get(), QuotaEventKind.RELEASE, LEDGER_RETURN, sessionId, reason.wire(), now);
            return true;
        }));
    }

    /**
     * Gives a session back after a failure (D-38, D-72): a server-confirmed failure always, an unconfirmable
     * one only while the day has fewer than {@code unconfirmed_give_backs_per_day}, a user-side one never.
     * A given-back session is released with a {@code give_back} ledger row of -1. A second call changes
     * nothing and uses no cap.
     *
     * @return true when the session was given back by this call
     */
    public boolean giveBack(UserId user, UUID sessionId, Failure failure) {
        if (failure.cause() == Failure.Cause.USER_SIDE) {
            return false;
        }
        LocalDate given = tx.execute(status -> {
            Instant now = clock.instant();
            Optional<LocalDate> day = sessions.releaseReserved(user, sessionId, failure.wire(), now, true);
            if (day.isEmpty()) {
                return null;
            }
            if (failure.cause() == Failure.Cause.UNCONFIRMED
                    && !counters.tryUseUnconfirmedGiveBack(
                            user, day.get(), settings.get(SettingKey.UNCONFIRMED_GIVE_BACKS_PER_DAY))) {
                status.setRollbackOnly(); // over the cap: the session stays reserved and counted
                return null;
            }
            counters.returnSession(user, day.get());
            ledger.append(user, day.get(), QuotaEventKind.GIVE_BACK, LEDGER_RETURN, sessionId, failure.wire(), now);
            return day.get();
        });
        if (given != null && failure == Failure.THROTTLED) {
            plans.creditBackAfterThrottle(user, given); // a rewarded credit is not used up by a refusal (F-087)
        }
        if (given != null) {
            kindOf(failure).ifPresent(kind -> announce(new StartRefused(user, given, kind, clock.instant())));
        }
        return given != null;
    }

    /**
     * The start dates of the sessions that count for the streak (03 F-053), oldest first: a delivered result, or a
     * session given back after a failure that was ours (server-confirmed, D-38). A late upload keeps its start date.
     */
    public List<LocalDate> practiceDays(UserId user) {
        return sessions.countedDays(user, OUR_FAILURES);
    }

    /** Sessions with a delivered result and the time spoken in them (03 F-055); a deleted report never lowers them. */
    public SessionTotals totals(UserId user) {
        SessionsRepository.Totals totals = sessions.totals(user);
        return new SessionTotals(totals.sessions(), totals.speakingMs());
    }

    /**
     * Starts an analysis of a take (C-52): one conditional update marks the session as being analysed, and only a
     * session still reserved, or committed with an attempt left, can be marked. While it is marked, a release the
     * person sends changes nothing ({@link #release}), so a released take can never be committed over the day's cap.
     *
     * @throws BusinessRefusal {@code not_found} for an unknown session or someone else's; {@code attempt_not_allowed}
     *     with {@code next_attempt} when the session was released ({@code session_ended}) or has used its attempts
     *     ({@code attempt_cap}); {@code rate_limited} while another analysis of it runs
     */
    public AnalysisStart beginAnalysis(UserId user, UUID sessionId) {
        SessionForAnalysis session =
                sessions.findForAnalysis(user, sessionId).orElseThrow(() -> new BusinessRefusal(Reason.NOT_FOUND));
        if (session.scoredAttempts() >= AttemptSettings.LAUNCH_DEFAULT.maxAttempts()) {
            throw attemptNotAllowed(NextAttemptReason.ATTEMPT_CAP.name().toLowerCase(Locale.ROOT));
        }
        Instant now = clock.instant();
        Instant staleBefore = now.minus(Duration.ofSeconds(settings.get(SettingKey.ANALYSIS_STALE_SECONDS)));
        if (!sessions.markAnalysing(user, sessionId, now, staleBefore)) {
            String state = sessions.findForAnalysis(user, sessionId)
                    .map(SessionForAnalysis::state)
                    .orElse(RELEASED);
            if (RELEASED.equals(state)) {
                throw attemptNotAllowed(SESSION_ENDED);
            }
            throw new BusinessRefusal(Reason.RATE_LIMITED, Map.of("retry_after_s", RETRY_AFTER_S));
        }
        return new AnalysisStart(
                session.id(),
                session.topicId(),
                session.style(),
                session.scoredAttempts(),
                "committed".equals(session.state()));
    }

    /**
     * Ends an analysis that gave no score (F-037): the mark goes, and a session still reserved is released with the
     * {@code no_score} reason (a no-score take uses no session). A committed session stays committed.
     */
    public void endAnalysisWithNoScore(UserId user, UUID sessionId) {
        sessions.clearAnalysing(user, sessionId);
        release(user, sessionId, ReleaseReason.NO_SCORE);
    }

    /** Ends an analysis that was refused before it ran (a take over the time limit): only the mark goes. */
    public void endAnalysis(UserId user, UUID sessionId) {
        sessions.clearAnalysing(user, sessionId);
    }

    /**
     * Records a scored attempt (F-047, F-048): the first one commits the session ({@link #commit}), every one adds to
     * the attempt count and the speaking time, and the analysis mark goes. Runs in the caller's transaction when there
     * is one, so the report and the commit are saved together.
     *
     * @return true only for the commit that used up the ad-free first session
     */
    public boolean recordScoredAttempt(UserId user, UUID sessionId, @Nullable String deviceId, int speakingMs) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            boolean firstSession = commit(user, sessionId, deviceId);
            sessions.recordScoredAttempt(user, sessionId, speakingMs);
            return firstSession;
        }));
    }

    private static BusinessRefusal attemptNotAllowed(String reason) {
        return new BusinessRefusal(
                Reason.ATTEMPT_NOT_ALLOWED, Map.of("next_attempt", Map.of("allowed", false, "reason", reason)));
    }

    /**
     * Releases every reservation with no result {@code reservation_timeout_hours} after it was made (D-72),
     * each with an {@code expire} ledger row of -1. Called by the 48 h job; answers how many. A timeout under
     * one hour is a slip in the settings, not a choice: it releases nothing (fails closed), because freeing
     * every reservation on each run would hand out sessions.
     */
    public int releaseStaleReservations() {
        int hours = settings.get(SettingKey.RESERVATION_TIMEOUT_HOURS);
        if (hours < 1) {
            return 0;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofHours(hours));
        return sessions.releaseReservedBefore(cutoff, TIMEOUT_REASON, now);
    }

    private static Optional<StartRefused.Kind> kindOf(Failure failure) {
        return switch (failure) {
            case SERVER_ERROR, SERVER_TIMEOUT -> Optional.of(StartRefused.Kind.SERVER_ERROR);
            case ANALYSIS_UNAVAILABLE -> Optional.of(StartRefused.Kind.UNAVAILABLE);
            case THROTTLED -> Optional.of(StartRefused.Kind.THROTTLED);
            default -> Optional.empty(); // unconfirmed or the person's own: not ours to freeze
        };
    }

    /** Tells {@code progress} we failed this person; never lets a problem in that hide the real error. */
    private void refusedByUs(UserId user, StartRefused.Kind kind) {
        try {
            announce(new StartRefused(user, calendar.today(user), kind, clock.instant()));
        } catch (RuntimeException ignored) {
            // the original failure is what the caller must see
        }
    }

    /** A problem in a listener must never break the person's own request; it is counted by event name only. */
    private void announce(StartRefused refusal) {
        try {
            events.publishEvent(refusal);
        } catch (RuntimeException e) {
            log.warn("start_refused_listener_failed kind={}", refusal.kind().wire());
        }
    }

    /** True when this commit is the account's first and the device has not had one; marks the device. */
    private boolean useFirstSession(
            UserId user, UUID sessionId, @Nullable String deviceId, LocalDate day, Instant now) {
        if (sessions.hasCommittedSessionOtherThan(user, sessionId)) {
            return false;
        }
        if (deviceId == null || deviceId.isBlank()) {
            return true;
        }
        byte[] device = hasher.hash(deviceId);
        if (deviceFlags.firstSessionUsed(device)) {
            return false;
        }
        deviceFlags.markFirstSessionUsed(device, day, now);
        return true;
    }

    /** Empty when another call stored a session for this take first; the caller rolls back. */
    private Optional<Reservation> tryReserve(
            UserId user, String takeKey, Topic topic, @Nullable String deviceId, Plan plan, SpendDecision spend) {
        LocalDate day = quotaDay(user);
        AllowanceRule rule = allowanceRule();
        boolean firstSession = isFirstSession(user, null, deviceId);

        counters.ensureRow(user, day);
        OptionalInt used = counters.tryUseSession(user, day, capOf(rule, plan, plans.adCreditsToday(user, day), spend));
        if (used.isEmpty()) {
            return Optional.of(
                    new Reservation(rule.refusal(plan), null, day, resetsAfter(user, day, spend), null, false, false));
        }
        Reservation.Source source = rule.sourceOf(plan, used.getAsInt());
        UUID id = Uuids.v7(clock);
        Instant now = clock.instant();
        if (!sessions.insertReserved(
                id,
                user,
                takeKey,
                topic.id(),
                topic.style().wire(),
                topic.level().wire(),
                day,
                source.wire(),
                now)) {
            return Optional.empty();
        }
        ledger.append(user, day, QuotaEventKind.RESERVE, LEDGER_RESERVE, id, null, now);
        return Optional.of(new Reservation(QuotaState.OK, id, day, resetsAt(user, day), source, firstSession, false));
    }

    /** The throttle's question is about the plan only to choose a ceiling; the score never calls this. */
    private static SpendGate.Tier tierOf(Plan plan) {
        return plan == Plan.PRO ? SpendGate.Tier.PRO : SpendGate.Tier.FREE;
    }

    /** Today's cap, lowered to the Pro ceiling while the AI-cost throttle is at its second stage (D-58). */
    private static int capOf(AllowanceRule rule, Plan plan, int adCredits, SpendDecision spend) {
        int cap = rule.cap(plan, adCredits);
        return spend.kind() == SpendDecision.Kind.PRO_REDUCED ? Math.min(cap, spend.proCeiling()) : cap;
    }

    /**
     * When the person may try again: the end of the quota day, or, while the Pro ceiling is lowered, the UTC midnight
     * if that comes first (the lowered ceiling lifts with the UTC day, 03 F-087).
     */
    private Instant resetsAfter(UserId user, LocalDate day, SpendDecision spend) {
        Instant dayEnds = resetsAt(user, day);
        if (spend.kind() != SpendDecision.Kind.PRO_REDUCED) {
            return dayEnds;
        }
        Instant pauseEnds = SpendGate.pauseEnds(clock.instant());
        return pauseEnds.isBefore(dayEnds) ? pauseEnds : dayEnds;
    }

    private AllowanceRule allowanceRule() {
        return new AllowanceRule(
                settings.get(SettingKey.FREE_SESSIONS_PER_DAY), settings.get(SettingKey.PRO_SESSIONS_PER_DAY));
    }

    /**
     * The account day the quota counts on: today in the stored zone, but never earlier than the latest day
     * already counted, so a zone change west cannot bring back a day that was used (F-050 "a zone change
     * never creates two dates").
     */
    private LocalDate quotaDay(UserId user) {
        LocalDate today = calendar.today(user);
        return counters.lastDay(user).filter(last -> last.isAfter(today)).orElse(today);
    }

    /**
     * The ad-free first session (D-31, EC-008): no other session of this account is reserved or committed
     * and this device has never had one. It is used up at commit (#56), so a failed first session keeps it.
     */
    private boolean isFirstSession(UserId user, @Nullable UUID ownSession, @Nullable String deviceId) {
        if (sessions.hasLiveSessionOtherThan(user, ownSession)) {
            return false;
        }
        return deviceId == null || deviceId.isBlank() || !deviceFlags.firstSessionUsed(hasher.hash(deviceId));
    }

    private Instant resetsAt(UserId user, LocalDate day) {
        return calendar.startOf(user, day.plusDays(1));
    }

    /** The stored session for a take sent again; its first-session answer is the same as the first time. */
    private Reservation repeated(UserId user, StoredSession session, @Nullable String deviceId) {
        Reservation.Source source = Reservation.Source.valueOf(session.source().toUpperCase(Locale.ROOT));
        boolean firstSession = isFirstSession(user, session.id(), deviceId);
        return new Reservation(
                QuotaState.OK, session.id(), session.day(), resetsAt(user, session.day()), source, firstSession, true);
    }
}
