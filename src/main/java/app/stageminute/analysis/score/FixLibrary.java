package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The Fix Library (03 F-032): the code owns the choice of fix, and the model only words the Try line. Triggers are
 * checked in priority order and the first that fires wins. Pure: no clock, no user, no tier, no Spring.
 */
public final class FixLibrary {

    private static final long MS_PER_MINUTE = 60_000L;

    /** Point first, then Closing, Example and Reason (the order of P2 to P5). */
    private static final int[] MISSING_STEP_ORDER = {0, 3, 2, 1};

    private static final int CLOSING = 3;

    private FixLibrary() {}

    /**
     * The fix for a take, given the baseline attempt of the same session if there is one (03 F-032, F-033). On attempts
     * 2 and 3 the previous fix stays unless its focus check says Done; a fix that is Done is not given again on the
     * same report, even if its trigger still fires (C-43). Two fixes are swapped on purpose: {@code wrap_up_in_time}
     * replaces a carried closing fix when the take hit the cap, and the closing fix comes back once a take ends
     * naturally. A different style than the baseline starts fresh with no focus check (EC-055).
     *
     * @param previous the attempt that set the fix: its fix and its facts, read from the server's own {@code
     *     facts_record}
     */
    public static FixSelection choose(TakeFacts current, Optional<PreviousTake> previous, FixSettings settings) {
        if (previous.isEmpty() || previous.get().facts().style() != current.style()) {
            return new FixSelection(firstFix(current, settings), Optional.empty());
        }
        PreviousTake before = previous.get();
        FocusCheckResult focus = FocusCheck.check(before, current, settings);
        Optional<FocusCheckResult> check = Optional.of(focus);
        if (focus.result() == FocusResult.DONE) {
            return new FixSelection(pick(current, settings, Optional.of(before.fix())), check);
        }
        boolean closingMissing = current.steps().get(CLOSING) == StepStatus.MISSING;
        FixId closingFix = FixId.forMissingStep(current.style(), CLOSING);
        if (before.fix().id() == closingFix && current.hitCap() && closingMissing) {
            return new FixSelection(FixChoice.ofStep(FixId.WRAP_UP_IN_TIME, CLOSING), check);
        }
        if (before.fix().id() == FixId.WRAP_UP_IN_TIME && !current.hitCap()) {
            return new FixSelection(FixChoice.ofStep(closingFix, CLOSING), check);
        }
        return new FixSelection(before.fix().carried(), check);
    }

    /**
     * The first fix whose trigger fires for this take, with no earlier take to carry over. When nothing fires the take
     * is strong and gets one polish, the lowest Language criterion, marked {@code nothingMajor}.
     */
    public static FixChoice firstFix(TakeFacts take, FixSettings settings) {
        return pick(take, settings, Optional.empty());
    }

    /**
     * The first fix that fires, leaving out {@code done}: the fix the person has just finished. A fix is left out by
     * its id, except {@code strengthen_step}, which is one id for four steps: it is left out for the step that was
     * finished only, so another weak step still gets its turn.
     */
    private static FixChoice pick(TakeFacts take, FixSettings settings, Optional<FixChoice> done) {
        // P0 speak_longer
        if (take.wordCount() < settings.speakLongerMinWords() || take.speechMs() < settings.speakLongerMinSpeechMs()) {
            if (open(done, FixId.SPEAK_LONGER)) {
                return FixChoice.of(FixId.SPEAK_LONGER);
            }
        }
        // P1 answer_question
        if (take.relevanceLevel() <= settings.offTopicLevel() && open(done, FixId.ANSWER_QUESTION)) {
            return FixChoice.of(FixId.ANSWER_QUESTION);
        }
        // P2 to P5: a missing step, in the order Point, Closing, Example, Reason (wrap_up_in_time replaces the closing
        // fix when the take hit the cap)
        for (int step : MISSING_STEP_ORDER) {
            if (take.steps().get(step) == StepStatus.MISSING) {
                FixId fix = step == CLOSING && take.hitCap()
                        ? FixId.WRAP_UP_IN_TIME
                        : FixId.forMissingStep(take.style(), step);
                if (open(done, fix)) {
                    return FixChoice.ofStep(fix, step);
                }
            }
        }
        // P6 no_freeze
        if (take.midAnswerPauses() >= settings.noFreezeMinPauses() && open(done, FixId.NO_FREEZE)) {
            return FixChoice.of(FixId.NO_FREEZE);
        }
        // P7 filler_cluster
        if ((take.fillerClusters() >= settings.fillerClustersTrigger() || fillersPerMinuteReached(take, settings))
                && open(done, FixId.FILLER_CLUSTER)) {
            return FixChoice.of(FixId.FILLER_CLUSTER);
        }
        // P8 start_sooner
        if (take.firstWordMs() > settings.startSoonerOverMs() && open(done, FixId.START_SOONER)) {
            return FixChoice.of(FixId.START_SOONER);
        }
        // P9 strengthen_step
        for (int step = 0; step < take.steps().size(); step++) {
            if (take.steps().get(step) == StepStatus.WEAK && !strengthenDone(done, step)) {
                return FixChoice.ofStep(FixId.STRENGTHEN_STEP, step);
            }
        }
        // P10 split_sentence
        if (take.runOnLabelled()
                && take.longestSentenceWords() > settings.splitSentenceOverWords()
                && open(done, FixId.SPLIT_SENTENCE)) {
            return FixChoice.of(FixId.SPLIT_SENTENCE);
        }
        // P11 steady_pace
        if (take.outsideBandMs(settings.windowMs()) >= settings.steadyPaceOutsideMs()
                && open(done, FixId.STEADY_PACE)) {
            return FixChoice.of(FixId.STEADY_PACE);
        }
        // P12 the lowest Language level, or the polish of a strong take
        return lowestLanguageFix(take.language(), settings, done);
    }

    private static boolean open(Optional<FixChoice> done, FixId fix) {
        return done.isEmpty() || done.get().id() != fix;
    }

    private static boolean strengthenDone(Optional<FixChoice> done, int step) {
        return done.isPresent()
                && done.get().id() == FixId.STRENGTHEN_STEP
                && done.get().target() == step;
    }

    private static boolean fillersPerMinuteReached(TakeFacts take, FixSettings settings) {
        return take.confirmedFillers() * MS_PER_MINUTE >= (long) settings.fillersPerMinuteTrigger() * take.durationMs();
    }

    /**
     * The fix for the lowest Language level that is not {@code done}; a tie goes in the order clarity, conciseness,
     * accuracy. It is a polish ({@code nothingMajor}) when that level is above the fix line.
     */
    private static FixChoice lowestLanguageFix(
            LanguageLevels language, FixSettings settings, Optional<FixChoice> done) {
        record Criterion(FixId fix, int level) {}
        List<Criterion> criteria = new ArrayList<>(List.of(
                new Criterion(FixId.SAY_IT_PLAINLY, language.clarity()),
                new Criterion(FixId.SAY_IT_SHORTER, language.conciseness()),
                new Criterion(FixId.MORE_STANDARD, language.accuracy())));
        // a stable sort keeps the tie order (clarity, conciseness, accuracy)
        criteria.sort(Comparator.comparingInt(Criterion::level));
        Criterion chosen = criteria.stream()
                .filter(criterion -> open(done, criterion.fix()))
                .findFirst()
                .orElseThrow();
        return new FixChoice(chosen.fix(), FixChoice.NO_TARGET, false, chosen.level() > settings.languageFixMaxLevel());
    }
}
