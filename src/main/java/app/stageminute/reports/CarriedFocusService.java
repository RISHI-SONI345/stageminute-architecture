package app.stageminute.reports;

import app.stageminute.account.StyleChanged;
import app.stageminute.platform.FlagKey;
import app.stageminute.platform.Flags;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.SettingList;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.reports.internal.NextFocusRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The carried focus (03 F-034, AD-138): the one fix a topic leaves for the first take of the next topic, one row per
 * person. Written when a scored take is saved (the session's last scored take decides the row), portable fixes only
 * ({@code carry_fix_portable_codes}); taken by the first scored take of another session, which keeps it on its report
 * as {@code carried_focus}; ignored after {@code carry_fix_max_age_days}; cleared by "Not today", by a new style when
 * it is a fix about one step (F-009, style-bound; a delivery fix keeps no style, AD-140) and with its source report
 * (the foreign key). It never reaches the score: it is added to a report that is already built. With the flag {@code
 * carry_fix_enabled} off or missing nothing is carried, written or sent; "Not today" and a new style still clear a
 * row, which only removes.
 */
@Service
public class CarriedFocusService {

    /**
     * The focus waiting for the next topic, as {@code GET /me} sends it.
     *
     * @param fixCode the fix
     * @param label the Fix Library label (C-70)
     * @param fromSessionId the session it came from; the app does not show it while that session is still open
     */
    public record Next(String fixCode, String label, UUID fromSessionId) {}

    private final NextFocusRepository repository;
    private final FixTexts texts;
    private final Flags flags;
    private final Settings settings;
    private final Clock clock;

    CarriedFocusService(NextFocusRepository repository, FixTexts texts, Flags flags, Settings settings, Clock clock) {
        this.repository = repository;
        this.texts = texts;
        this.flags = flags;
        this.settings = settings;
        this.clock = clock;
    }

    /** The focus for the person's next topic, if the flag is on and it is younger than the limit. */
    public Optional<Next> forNextTopic(UserId user) {
        if (!flags.isOn(FlagKey.CARRY_FIX_ENABLED)) {
            return Optional.empty();
        }
        return repository
                .find(user)
                .filter(row -> fresh(row, clock.instant()))
                .flatMap(row -> texts.text(row.fixCode())
                        .map(text -> new Next(row.fixCode(), text.label(), row.fromSessionId())));
    }

    /**
     * "Not today": clears the focus that came from this session. Another session's focus stays, and a focus that is
     * already gone is fine, so the call is safe to repeat (and harmless from a second phone).
     */
    @Transactional
    public void dismiss(UserId user, UUID fromSessionId) {
        repository.deleteFromSession(user, fromSessionId);
    }

    /** F-009, EC-055: a new style clears a step fix set in the other style. Runs in the profile change's transaction. */
    @EventListener
    void onStyleChanged(StyleChanged event) {
        repository.deleteOtherStyle(event.user(), event.style());
    }

    /**
     * Takes the person's focus if this report is the first scored take of another topic, and decides the focus this
     * report leaves. Call inside the saving transaction, before the report row is written.
     */
    ReportV2 carryInto(UserId user, ReportV2 report, Instant at) {
        if (!flags.isOn(FlagKey.CARRY_FIX_ENABLED)) {
            return report.withFocus(null, null);
        }
        ReportV2.CarriedFocus carried = null;
        Optional<NextFocusRepository.Row> row = repository.lock(user);
        if (row.isPresent() && !row.get().fromSessionId().equals(report.sessionId())) {
            repository.delete(user); // the next topic's first take uses it up, whatever comes of it
            NextFocusRepository.Row found = row.get();
            if (fresh(found, at)
                    && (found.style() == null
                            || found.style().equals(report.style().wire()))) {
                carried = texts.text(found.fixCode())
                        .map(text -> new ReportV2.CarriedFocus(found.fixCode(), text.label(), found.fromSessionId()))
                        .orElse(null);
            }
        }
        return report.withFocus(carried, leaves(report));
    }

    /** Writes the row this report leaves, or removes its own session's row. Call after the report row is written. */
    void remember(UserId user, ReportV2 report, Instant at) {
        if (!flags.isOn(FlagKey.CARRY_FIX_ENABLED)) {
            return;
        }
        if (report.nextFocus() == null) {
            repository.deleteFromSession(user, report.sessionId()); // the session now ends on a fix not carried
            return;
        }
        repository.put(user, report.nextFocus().fixCode(), report.analysisId(), boundStyle(report), at);
    }

    /** The style a carried fix is bound to (F-009, AD-140): a step fix keeps its take's style, a delivery fix none. */
    static @Nullable String boundStyle(ReportV2 report) {
        ReportV2.FixFirst fix = report.fixFirst();
        return fix != null && fix.step() != null ? report.style().wire() : null;
    }

    /** F-034: the unfinished fix, else the new top fix, is the report's {@code fix_first}; carried if portable. */
    private ReportV2.@Nullable NextFocus leaves(ReportV2 report) {
        ReportV2.FixFirst fix = report.fixFirst();
        if (fix == null || !settings.list(SettingList.CARRY_FIX_PORTABLE_CODES).contains(fix.id())) {
            return null;
        }
        return new ReportV2.NextFocus(fix.id());
    }

    private boolean fresh(NextFocusRepository.Row row, Instant now) {
        return row.setAt()
                .plus(Duration.ofDays(settings.get(SettingKey.CARRY_FIX_MAX_AGE_DAYS)))
                .isAfter(now);
    }
}
