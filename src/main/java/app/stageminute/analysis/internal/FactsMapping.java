package app.stageminute.analysis.internal;

import app.stageminute.analysis.score.FixChoice;
import app.stageminute.analysis.score.FixId;
import app.stageminute.analysis.score.LanguageLevels;
import app.stageminute.analysis.score.PaceBand;
import app.stageminute.analysis.score.PreviousTake;
import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.score.TakeFacts;
import app.stageminute.reports.FactsRecord;
import app.stageminute.reports.ReportV2;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a scored take into the numbers the server keeps ({@code facts_records}), and back into the score code's
 * {@link TakeFacts} when the next attempt is checked (03 F-033: the previous facts come from the server, never the
 * phone). Also picks the baseline attempt of a focus check (C-43). Pure.
 */
final class FactsMapping {

    private FactsMapping() {}

    static FactsRecord toRecord(ReportV2 report, Pipeline.Scored scored) {
        TakeFacts facts = scored.facts();
        FixChoice fix = scored.fix();
        return new FactsRecord(
                report.analysisId(),
                report.sessionId(),
                report.attempt(),
                facts.style().name().toLowerCase(Locale.ROOT),
                facts.durationMs(),
                facts.wordCount(),
                facts.speechMs(),
                facts.firstWordMs(),
                facts.midAnswerPauses(),
                facts.longestPauseMs(),
                facts.confirmedFillers(),
                report.facts().fillers().perMin(),
                facts.fillerClusters(),
                facts.longestSentenceWords(),
                facts.runOnLabelled(),
                facts.windowWpm(),
                facts.paceBand().lowWpm(),
                facts.paceBand().highWpm(),
                facts.hitCap(),
                facts.steps().stream()
                        .map(s -> s.name().toLowerCase(Locale.ROOT))
                        .toList(),
                facts.relevanceLevel(),
                facts.language().clarity(),
                facts.language().conciseness(),
                facts.language().accuracy(),
                fix.id().code(),
                fix.target(),
                fix.carriedOver(),
                fix.nothingMajor(),
                scored.focus().map(f -> f.name().toLowerCase(Locale.ROOT)).orElse(null),
                scored.averageWpm(),
                report.score() == null ? 0 : report.score().total(),
                report.nextAttempt() != null && report.nextAttempt().allowed());
    }

    static TakeFacts toTakeFacts(FactsRecord record) {
        return new TakeFacts(
                Style.valueOf(record.style().toUpperCase(Locale.ROOT)),
                record.steps().stream()
                        .map(s -> StepStatus.valueOf(s.toUpperCase(Locale.ROOT)))
                        .toList(),
                record.relevance(),
                new LanguageLevels(record.clarity(), record.conciseness(), record.accuracy()),
                record.wordCount(),
                record.speechMs(),
                record.durationMs(),
                record.firstWordMs(),
                record.midAnswerPauses(),
                record.longestPauseMs(),
                record.fillersConfirmed(),
                record.fillerClusters(),
                record.longestSentenceWords(),
                record.runOnLabelled(),
                record.windowWpm(),
                new PaceBand(record.paceBandLow(), record.paceBandHigh()),
                record.hitCap());
    }

    /**
     * The baseline of the next attempt's focus check (C-43): the latest attempt's fix, measured against the attempt
     * that first put that fix first. While the fix was carried from attempt to attempt, the baseline stays where it
     * started, so a level that goes 2, then 1, then 2 is not reported as an improvement.
     *
     * @param attempts the session's scored attempts, first attempt first
     */
    static Optional<PreviousTake> baseline(List<FactsRecord> attempts) {
        if (attempts.isEmpty()) {
            return Optional.empty();
        }
        FactsRecord latest = attempts.get(attempts.size() - 1);
        int start = attempts.size() - 1;
        while (start > 0 && attempts.get(start).fixCarried() && sameFix(attempts.get(start - 1), latest)) {
            start--;
        }
        FixChoice fix = new FixChoice(
                fixId(latest.fixCode()), latest.fixTarget(), latest.fixCarried(), latest.fixNothingMajor());
        return Optional.of(new PreviousTake(fix, toTakeFacts(attempts.get(start))));
    }

    /** The latest scored attempt and its total, for {@code delta}. */
    static Optional<Pipeline.PreviousScore> previous(List<FactsRecord> attempts) {
        if (attempts.isEmpty()) {
            return Optional.empty();
        }
        FactsRecord latest = attempts.get(attempts.size() - 1);
        return Optional.of(new Pipeline.PreviousScore(latest.attempt(), latest.scoreTotal()));
    }

    private static boolean sameFix(FactsRecord a, FactsRecord b) {
        return a.fixCode().equals(b.fixCode()) && a.fixTarget() == b.fixTarget();
    }

    private static FixId fixId(String code) {
        for (FixId id : FixId.values()) {
            if (id.code().equals(code)) {
                return id;
            }
        }
        throw new IllegalStateException("a stored fix code the Fix Library does not know");
    }
}
