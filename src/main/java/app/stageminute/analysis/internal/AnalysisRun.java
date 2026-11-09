package app.stageminute.analysis.internal;

import app.stageminute.account.AccountService;
import app.stageminute.analysis.AnalysisOutcome;
import app.stageminute.analysis.AnalyzeCommand;
import app.stageminute.analysis.SpeechDataView;
import app.stageminute.analysis.score.GateThresholds;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.vendor.SpendRefused;
import app.stageminute.analysis.vendor.VendorUnavailable;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.SpendGate;
import app.stageminute.platform.UserId;
import app.stageminute.platform.Uuids;
import app.stageminute.practice.Practice;
import app.stageminute.reports.FactsRecord;
import app.stageminute.reports.ReportV2;
import app.stageminute.reports.Reports;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One analysis after the request is known to be new (04-FLOWS.md "Analyze"; 03 F-091, F-048): the server measures the
 * take (C-69), refuses a take under 20 s or past the topic's time plus the margin (C-60), starts the analysis on the
 * session (C-52), runs the {@link Pipeline} outside any transaction, and then, in one transaction, saves the report and
 * its facts, records the scored attempt (the commit) and stores the answer for a retry. A take with no score releases
 * the session and is not kept (F-037). A vendor failure gives the session back (server-confirmed, no cap, D-38) and
 * answers {@code analysis_unavailable}. If the account is deleted while the vendors work, the save finds nothing to attach
 * to and the run ends as {@code account_deleted} with no trace left (EC-133). Logs carry event names and numbers only.
 */
@Component
public class AnalysisRun {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisRun.class);
    private static final int MS_PER_SECOND = 1000;
    private static final int PAST_TAKES = 10;

    private final Pipeline pipeline;
    private final Practice practice;
    private final Reports reports;
    private final TopicCatalog topics;
    private final Settings settings;
    private final SpeechDataCache speechCache;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AccountService accounts;

    AnalysisRun(
            Pipeline pipeline,
            Practice practice,
            Reports reports,
            TopicCatalog topics,
            Settings settings,
            SpeechDataCache speechCache,
            TransactionTemplate tx,
            Clock clock,
            AccountService accounts) {
        this.pipeline = pipeline;
        this.practice = practice;
        this.reports = reports;
        this.topics = topics;
        this.settings = settings;
        this.speechCache = speechCache;
        this.tx = tx;
        this.clock = clock;
        this.accounts = accounts;
    }

    /**
     * Analyses the take.
     *
     * @param storeAnswer called inside the saving transaction with the answer to keep for a retry
     */
    public AnalysisOutcome analyze(AnalyzeCommand command, Consumer<AnalysisOutcome> storeAnswer) {
        UserId user = command.user();
        UUID sessionId = command.sessionId();
        OptionalInt measured = M4aAudio.durationMs(command.audio());
        if (measured.isEmpty()) {
            throw new BusinessRefusal(Reason.UNSUPPORTED_FORMAT);
        }
        int durationMs = measured.getAsInt();
        compareWithThePhone(command.phoneDurationMs(), durationMs);
        if (durationMs < GateThresholds.LAUNCH_DEFAULT.minDurationMs()) {
            practice.release(user, sessionId, Practice.ReleaseReason.NO_SCORE); // a take under 20 s uses no session
            throw new BusinessRefusal(Reason.TOO_SHORT);
        }

        Practice.AnalysisStart start = practice.beginAnalysis(user, sessionId);
        Topic topic = topics.get(start.topicId()).orElse(null);
        int capMs = topic == null ? 0 : topic.speakS() * MS_PER_SECOND;
        if (topic == null || durationMs > capMs + settings.get(SettingKey.ANALYZE_CAP_MARGIN_MS)) {
            practice.endAnalysis(user, sessionId);
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("audio")));
        }
        List<FactsRecord> attempts = reports.attempts(user, sessionId);
        if (start.committed()
                && !attempts.isEmpty()
                && !attempts.get(attempts.size() - 1).nextAttemptAllowed()) {
            practice.endAnalysis(user, sessionId);
            throw new BusinessRefusal(
                    Reason.ATTEMPT_NOT_ALLOWED,
                    Map.of("next_attempt", Map.of("allowed", false, "reason", "focus_done")));
        }

        Pipeline.Result result;
        try {
            result = pipeline.run(new Pipeline.Input(
                    command.audio(),
                    durationMs,
                    capMs,
                    Style.valueOf(start.style().toUpperCase(Locale.ROOT)),
                    topic.text(),
                    Uuids.v7(clock),
                    sessionId,
                    start.scoredAttempts() + 1,
                    FactsMapping.baseline(attempts),
                    FactsMapping.previous(attempts),
                    reports.recentAverageWpm(user, PAST_TAKES)));
        } catch (VendorUnavailable e) {
            boolean givenBack = practice.giveBack(user, sessionId, Practice.Failure.ANALYSIS_UNAVAILABLE);
            practice.endAnalysis(user, sessionId);
            LOG.info(
                    "analyze.vendor_unavailable code={} given_back={}",
                    e.getMessage(),
                    givenBack); // a fixed code, no text
            throw new BusinessRefusal(Reason.ANALYSIS_UNAVAILABLE, Map.of("session_given_back", givenBack));
        } catch (SpendRefused e) {
            // 03 F-087, v8-15h: the AI-cost throttle began after the session was reserved and the meter refused a
            // vendor
            // call. The take is saved on the phone, the session goes back ("This doesn't use a free session"), and a
            // rewarded credit is returned through the same give-back. No amount, no id: the code is fixed.
            boolean givenBack = practice.giveBack(user, sessionId, Practice.Failure.THROTTLED);
            practice.endAnalysis(user, sessionId);
            LOG.info("analyze.throttled code={} given_back={}", e.getMessage(), givenBack);
            throw SpendGate.throttled(SpendGate.pauseEnds(clock.instant()), Map.of("session_given_back", givenBack));
        } catch (RuntimeException e) {
            practice.giveBack(user, sessionId, Practice.Failure.SERVER_ERROR);
            practice.endAnalysis(user, sessionId);
            throw e;
        }

        AnalysisOutcome outcome;
        try {
            outcome = save(command, start, result, storeAnswer);
        } catch (RuntimeException e) {
            if (e instanceof DataIntegrityViolationException && !accounts.exists(user)) {
                // EC-133: the account was deleted while the vendors worked, so the save found no session to attach the
                // report to. The cascade already removed everything of the person, so there is nothing to give back,
                // nothing to clear, and no words to keep: the run ends as "the account is gone".
                LOG.info("analyze.account_deleted_during_run");
                throw new BusinessRefusal(Reason.ACCOUNT_DELETED);
            }
            // the vendors ran but nothing was kept: a server-side failure, so the session goes back (D-38)
            practice.giveBack(user, sessionId, Practice.Failure.SERVER_ERROR);
            practice.endAnalysis(user, sessionId);
            throw e;
        }
        speechCache.put(user, command.takeKey(), result.speech());
        return outcome;
    }

    /**
     * One transaction: the report and its facts (with the carried focus, F-034), the scored attempt (the commit), and
     * the answer for a retry. Returns the answer as kept.
     */
    private AnalysisOutcome save(
            AnalyzeCommand command,
            Practice.AnalysisStart start,
            Pipeline.Result result,
            Consumer<AnalysisOutcome> storeAnswer) {
        UserId user = command.user();
        UUID sessionId = command.sessionId();
        return Objects.requireNonNull(tx.execute(status -> {
            Optional<Pipeline.Scored> scored = result.scored();
            ReportV2 report = result.report();
            if (scored.isPresent()) {
                report = reports.save(
                        user,
                        result.report(),
                        FactsMapping.toRecord(result.report(), scored.get()),
                        start.topicId(),
                        clock.instant(),
                        scored.get().answeredJudgeModel(),
                        scored.get().answeredSpeechModel());
                practice.recordScoredAttempt(
                        user,
                        sessionId,
                        command.deviceId(),
                        scored.get().facts().speechMs());
            } else {
                practice.endAnalysisWithNoScore(user, sessionId);
            }
            AnalysisOutcome outcome = new AnalysisOutcome(report, result.speech());
            storeAnswer.accept(outcome);
            return outcome;
        }));
    }

    /** The take's words kept for a retry, if they are still in memory. */
    public Optional<SpeechDataView> cachedSpeech(UserId user, String takeKey) {
        return speechCache.get(user, takeKey);
    }

    /** C-69: the phone's length is a hint; a large gap is an event with the two numbers, nothing else. */
    private void compareWithThePhone(int phoneMs, int measuredMs) {
        if (Math.abs(phoneMs - measuredMs) > settings.get(SettingKey.DURATION_MISMATCH_LOG_MS)) {
            LOG.info("analyze.duration_mismatch phone_ms={} measured_ms={}", phoneMs, measuredMs);
        }
    }
}
