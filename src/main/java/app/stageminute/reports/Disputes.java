package app.stageminute.reports;

import app.stageminute.account.AccountCalendar;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.DailyCaps;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.platform.Uuids;
import app.stageminute.reports.internal.DisputesRepository;
import app.stageminute.reports.internal.ReportJson;
import app.stageminute.reports.internal.ReportsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Score disputes, "What looks wrong?" and the one-tap "This fix felt wrong" (02b F-042, C-67; D-18; AD-147). A dispute
 * is kept for the founder's weekly read and marks the report "Sent for review"; it never changes the score, the parts
 * or the report (D-84: no second opinion, nothing automatic). It reads no tier.
 */
@Service
public class Disputes {

    private final ReportsRepository reports;
    private final DisputesRepository disputes;
    private final ReportJson json;
    private final DailyCaps caps;
    private final AccountCalendar calendar;
    private final Settings settings;
    private final Clock clock;

    Disputes(
            ReportsRepository reports,
            DisputesRepository disputes,
            ReportJson json,
            DailyCaps caps,
            AccountCalendar calendar,
            Settings settings,
            Clock clock) {
        this.reports = reports;
        this.disputes = disputes;
        this.json = json;
        this.caps = caps;
        this.calendar = calendar;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Keeps one dispute about one of the person's reports.
     *
     * @throws BusinessRefusal {@code validation_failed} (with {@code fields}) for a body that breaks a rule; {@code
     *     not_found} for a report that is someone else's, unknown, deleted or older than {@code report_window_days}
     *     (the same answer as {@code GET /v1/analyses/{id}}); {@code rate_limited} (with {@code retry_after_s}, the
     *     seconds until the person's next account day) after {@code disputes_per_day} disputes today
     */
    @Transactional
    public void send(UserId user, UUID reportId, DisputeRequest request) {
        DisputeArea area = request.area();
        if (area == null) {
            throw invalid("area");
        }
        if (area.takesIndex() != (request.index() != null)) {
            throw invalid("index");
        }
        String note = blankToNull(request.note());
        String transcript = blankToNull(request.transcript());
        if (area == DisputeArea.FIX_FIRST && request.note() != null) {
            throw invalid("note"); // the one-tap holds no words (C-67)
        }
        if (area == DisputeArea.FIX_FIRST && request.transcript() != null) {
            throw invalid("transcript");
        }
        if (note != null && note.codePointCount(0, note.length()) > settings.get(SettingKey.DISPUTE_NOTE_MAX_CHARS)) {
            throw invalid("note");
        }
        if (transcript != null
                && transcript.codePointCount(0, transcript.length())
                        > settings.get(SettingKey.DISPUTE_TRANSCRIPT_MAX_CHARS)) {
            throw invalid("transcript");
        }

        Instant now = clock.instant();
        Instant windowStart = now.minus(Duration.ofDays(settings.get(SettingKey.REPORT_WINDOW_DAYS)));
        ReportV2 report = reports.reportSince(user, reportId, windowStart)
                .map(json::read)
                .orElseThrow(() -> new BusinessRefusal(Reason.NOT_FOUND));
        if (request.index() != null && !namesAnItem(report, area, request.index())) {
            throw invalid("index");
        }
        String fixCode = null;
        if (area == DisputeArea.FIX_FIRST) {
            if (report.fixFirst() == null) {
                throw invalid("area");
            }
            fixCode = report.fixFirst().id(); // from the stored report, never from the phone
        }

        if (!disputes.markSent(user, reportId)) { // locks the report: a delete in between waits or has won
            throw new BusinessRefusal(Reason.NOT_FOUND);
        }
        boolean added = disputes.insert(
                user,
                new DisputesRepository.NewDispute(
                        Uuids.v7(clock),
                        reportId,
                        area.wire(),
                        request.index(),
                        note,
                        transcript,
                        fixCode,
                        report.formulaVersion(),
                        report.promptVersion(),
                        now.plus(Duration.ofDays(settings.get(SettingKey.DISPUTE_RETENTION_DAYS))),
                        now));
        if (!added) {
            return; // the same one-tap again: already kept, nothing more to count
        }
        if (!caps.tryCount(
                user, calendar.today(user), DailyCaps.Counter.DISPUTES, settings.get(SettingKey.DISPUTES_PER_DAY))) {
            long retryAfter =
                    Math.max(1, Duration.between(now, calendar.resetsAt(user)).toSeconds());
            throw new BusinessRefusal(Reason.RATE_LIMITED, Map.of("retry_after_s", retryAfter)); // rolls the row back
        }
    }

    private static boolean namesAnItem(ReportV2 report, DisputeArea area, int index) {
        ReportV2.Findings findings = report.findings();
        if (findings == null || index < 0) {
            return false;
        }
        if (area == DisputeArea.STRUCTURE_STEP) {
            return index < findings.steps().size();
        }
        return Stream.of(findings.languageItems(), findings.swaps())
                .filter(list -> list != null)
                .flatMap(List::stream)
                .anyMatch(item -> item.id() == index);
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static BusinessRefusal invalid(String field) {
        return new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of(field)));
    }
}
