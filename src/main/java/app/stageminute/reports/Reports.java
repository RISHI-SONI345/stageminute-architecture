package app.stageminute.reports;

import app.stageminute.account.AccountCalendar;
import app.stageminute.platform.IdempotencyStore;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.reports.internal.PatternsCalculator;
import app.stageminute.reports.internal.ReportJson;
import app.stageminute.reports.internal.ReportsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports the server keeps (02-SERVER-LLD.md, reports): a scored take's Report v2 (without {@code speech_data})
 * and its numbers-only facts. {@code analysis} saves through here inside the same transaction as the session commit
 * (04-FLOWS.md "Analyze"); a take with no score is never saved (F-037: counters only, no content kept).
 */
@Service
public class Reports {

    private final ReportsRepository repository;
    private final ReportJson json;
    private final Settings settings;
    private final Clock clock;
    private final CarriedFocusService carriedFocus;
    private final IdempotencyStore idempotency;
    private final AccountCalendar calendar;

    Reports(
            ReportsRepository repository,
            ReportJson json,
            Settings settings,
            Clock clock,
            CarriedFocusService carriedFocus,
            IdempotencyStore idempotency,
            AccountCalendar calendar) {
        this.repository = repository;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
        this.carriedFocus = carriedFocus;
        this.idempotency = idempotency;
        this.calendar = calendar;
    }

    private Instant windowStart() {
        return clock.instant().minus(Duration.ofDays(settings.get(SettingKey.REPORT_WINDOW_DAYS)));
    }

    /**
     * Saves a scored report and its facts, with the carried focus (03 F-034): the report gets the focus it started
     * with ({@code carried_focus}, when it is the first scored take of another topic) and the one it leaves ({@code
     * next_focus}), and the person's {@code next_focus} row is updated. Joins the caller's transaction.
     *
     * @return the report as kept, which is what goes to the phone
     * @throws IllegalArgumentException for a report with no score, or facts of another report
     */
    public ReportV2 save(UserId user, ReportV2 report, FactsRecord facts, String topicId, Instant at) {
        return save(user, report, facts, topicId, at, null, null);
    }

    /**
     * As {@link #save(UserId, ReportV2, FactsRecord, String, Instant)}, and keeps the model ids the two vendors
     * answered with beside the pinned name (AD-151). They are stored for tracing a score change and are not part of
     * the report the app reads.
     *
     * @param answeredJudgeModel the id Gemini says served the labels, or null
     * @param answeredSpeechModel the model name Deepgram says it used, or null
     */
    public ReportV2 save(
            UserId user,
            ReportV2 report,
            FactsRecord facts,
            String topicId,
            Instant at,
            @Nullable String answeredJudgeModel,
            @Nullable String answeredSpeechModel) {
        if (report.score() == null) {
            throw new IllegalArgumentException("a report with no score is not kept");
        }
        if (!report.analysisId().equals(facts.reportId()) || !report.sessionId().equals(facts.sessionId())) {
            throw new IllegalArgumentException("the facts belong to another report");
        }
        ReportV2 kept = carriedFocus.carryInto(user, report, at);
        repository.insert(user, kept, json.write(kept), facts, topicId, at, answeredJudgeModel, answeredSpeechModel);
        carriedFocus.remember(user, kept, at); // after the insert: the row points at this report
        return kept;
    }

    /**
     * The person's reports of the last {@code report_window_days} (7), newest first (03 F-057: the history window;
     * older reports exist but cannot be opened at launch). A row says {@code can_continue} while it is its session's
     * last attempt and the account day it was made on is today: the session is open until the end of that day.
     */
    public List<ReportSummary> recent(UserId user) {
        var today = calendar.today(user);
        return repository.since(user, windowStart()).stream()
                .map(row -> row.withCanContinue(row.canContinue()
                        && calendar.dayOf(user, row.createdAt()).equals(today)))
                .toList();
    }

    /** One of the person's reports inside the window; empty for an older one, an unknown id or someone else's. */
    public Optional<ReportV2> get(UserId user, UUID id) {
        return repository.reportSince(user, id, windowStart()).map(json::read);
    }

    /**
     * Deletes one of the person's reports (02b F-046), whatever its age (D-60: a person can delete any report). The
     * database removes its facts, its disputes and the carried focus that came from it (DB-2); the 10-minute Try again
     * copy of the answer goes too, so no copy outlives the delete. The session, the day's counters and the quota ledger
     * are never touched, so the streak, the totals and today's quota stay as they were (EC-113). An unknown id or
     * someone else's deletes nothing, and the caller cannot tell the difference.
     */
    @Transactional
    public void delete(UserId user, UUID id) {
        if (repository.delete(user, id)) {
            idempotency.forgetAnswersNaming(user.value(), id);
        }
    }

    /**
     * The patterns cards of one style (03 F-056): the last {@code pattern_window_takes} (5) scored first takes, and the
     * second attempts of those sessions that ended Done. Numbers and codes only.
     */
    public Patterns patterns(UserId user, String style) {
        int window = settings.get(SettingKey.PATTERN_WINDOW_TAKES);
        List<ReportsRepository.FirstTake> takes = repository.latestFirstTakes(user, style, window);
        List<Instant> doneAt = repository.secondAttemptsDone(
                user, takes.stream().map(ReportsRepository.FirstTake::sessionId).toList());
        List<LocalDate> days =
                doneAt.stream().map(at -> calendar.dayOf(user, at)).toList();
        return PatternsCalculator.compute(
                style,
                takes.stream().map(ReportsRepository.FirstTake::steps).toList(),
                window,
                settings.get(SettingKey.PATTERN_STRONG_MIN),
                settings.get(SettingKey.PATTERN_OPPORTUNITY_MIN),
                new Patterns.FixedOnSecond(days.size(), days));
    }

    /** The facts of every scored attempt of a session, first attempt first (the focus check reads them, F-033). */
    public List<FactsRecord> attempts(UserId user, UUID sessionId) {
        return repository.attempts(user, sessionId);
    }

    /** The average pace of the person's latest scored takes, oldest first (the personal pace band, F-027). */
    public List<Integer> recentAverageWpm(UserId user, int limit) {
        List<Integer> newestFirst = new ArrayList<>(repository.recentAverageWpm(user, limit));
        Collections.reverse(newestFirst);
        return newestFirst;
    }
}
