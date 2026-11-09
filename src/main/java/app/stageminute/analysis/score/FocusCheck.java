package app.stageminute.analysis.score;

import java.util.Optional;

/**
 * The focus check (03 F-033): was the fix from the previous attempt Done, Better or Not yet on this one? It applies
 * the "Done when" column of F-032 to the previous facts and the current facts. Pure: no clock, no user, no tier. A
 * fix whose line defines only "Done when" returns Done or Not yet (step3/CONFLICTS.md C-38). Both takes must be in the
 * same style; FixLibrary.choose makes sure of it.
 */
public final class FocusCheck {

    private static final long MS_PER_MINUTE = 60_000L;
    private static final int MAX_LANGUAGE_LEVEL = 4;

    private FocusCheck() {}

    public static FocusCheckResult check(PreviousTake previous, TakeFacts current, FixSettings settings) {
        FixChoice fix = previous.fix();
        TakeFacts before = previous.facts();
        return switch (fix.id()) {
            case SPEAK_LONGER -> speakLonger(fix, before, current, settings);
            case ANSWER_QUESTION -> answerQuestion(fix, before, current, settings);
            case OPEN_POINT, SET_SCENE, ADD_CLOSING, SAY_RESULT, ADD_EXAMPLE, NAME_ACTION, ADD_REASON, NAME_TASK ->
                missingStep(fix, before, current);
            case STRENGTHEN_STEP -> strengthenStep(fix, before, current);
            case WRAP_UP_IN_TIME -> wrapUpInTime(fix, before, current);
            case NO_FREEZE -> noFreeze(fix, before, current, settings);
            case FILLER_CLUSTER -> fillerCluster(fix, before, current, settings);
            case START_SOONER -> startSooner(fix, before, current, settings);
            case SPLIT_SENTENCE -> splitSentence(fix, before, current, settings);
            case STEADY_PACE -> steadyPace(fix, before, current, settings);
            case SAY_IT_PLAINLY, SAY_IT_SHORTER, MORE_STANDARD -> languageFix(fix, before, current, settings);
        };
    }

    private static FocusCheckResult speakLonger(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        boolean done = now.wordCount() >= s.speakLongerMinWords() && now.speechMs() >= s.speakLongerDoneMinSpeechMs();
        return result(fix, doneOrNotYet(done), FocusMeasure.SPEECH_MS, before.speechMs(), now.speechMs());
    }

    private static FocusCheckResult answerQuestion(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        boolean done = now.relevanceLevel() >= s.relevanceDoneLevel();
        return result(
                fix, doneOrNotYet(done), FocusMeasure.RELEVANCE_LEVEL, before.relevanceLevel(), now.relevanceLevel());
    }

    /** Strong is Done, weak is Better, still missing is Not yet. */
    private static FocusCheckResult missingStep(FixChoice fix, TakeFacts before, TakeFacts now) {
        StepStatus status = now.steps().get(fix.target());
        FocusResult result = switch (status) {
            case STRONG -> FocusResult.DONE;
            case WEAK -> FocusResult.BETTER;
            case MISSING -> FocusResult.NOT_YET;
        };
        return stepResult(fix, result, before, now);
    }

    private static FocusCheckResult strengthenStep(FixChoice fix, TakeFacts before, TakeFacts now) {
        boolean done = now.steps().get(fix.target()) == StepStatus.STRONG;
        return stepResult(fix, doneOrNotYet(done), before, now);
    }

    /**
     * Done once there is a closing (the take got there in time); Better when the take ended naturally but still has no
     * closing (so {@code add_closing} returns); Not yet when it hit the cap again with no closing.
     */
    private static FocusCheckResult wrapUpInTime(FixChoice fix, TakeFacts before, TakeFacts now) {
        FocusResult result;
        if (now.steps().get(fix.target()) != StepStatus.MISSING) {
            result = FocusResult.DONE;
        } else if (!now.hitCap()) {
            result = FocusResult.BETTER;
        } else {
            result = FocusResult.NOT_YET;
        }
        return stepResult(fix, result, before, now);
    }

    private static FocusCheckResult stepResult(FixChoice fix, FocusResult result, TakeFacts before, TakeFacts now) {
        StepStatus previousStatus = before.steps().get(fix.target());
        return new FocusCheckResult(
                fix.id(),
                result,
                new FocusEvidence(
                        FocusMeasure.STEP_STATUS,
                        rank(previousStatus),
                        rank(now.steps().get(fix.target()))),
                Optional.of(previousStatus));
    }

    private static int rank(StepStatus status) {
        return switch (status) {
            case MISSING -> 0;
            case WEAK -> 1;
            case STRONG -> 2;
        };
    }

    /** No mid-answer pause left is Done; the longest gap 30% shorter is Better. */
    private static FocusCheckResult noFreeze(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        FocusResult result;
        if (now.midAnswerPauses() < s.noFreezeMinPauses()) {
            result = FocusResult.DONE;
        } else if ((long) now.longestPauseMs() * 100
                <= (long) before.longestPauseMs() * (100 - s.pauseShorterPercent())) {
            result = FocusResult.BETTER;
        } else {
            result = FocusResult.NOT_YET;
        }
        return result(fix, result, FocusMeasure.LONGEST_PAUSE_MS, before.longestPauseMs(), now.longestPauseMs());
    }

    /**
     * Done when there is no cluster (under 3 in 10 s) and the filler rate is down by the drop share; Better when either
     * holds. Rates are compared as fractions, so a shorter or longer take does not matter.
     */
    private static FocusCheckResult fillerCluster(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        boolean noCluster = now.fillerClusters() < s.fillerClustersTrigger();
        // now.fillers / now.duration <= before.fillers / before.duration x (100 - drop) / 100, cross-multiplied
        boolean rateDown = (long) now.confirmedFillers() * before.durationMs() * 100
                <= (long) before.confirmedFillers() * now.durationMs() * (100 - s.fillerRateDropPercent());
        FocusResult result;
        if (noCluster && rateDown) {
            result = FocusResult.DONE;
        } else if (noCluster || rateDown) {
            result = FocusResult.BETTER;
        } else {
            result = FocusResult.NOT_YET;
        }
        return result(fix, result, FocusMeasure.FILLERS_PER_MINUTE, perMinute(before), perMinute(now));
    }

    private static int perMinute(TakeFacts take) {
        return (int) ((2L * take.confirmedFillers() * MS_PER_MINUTE + take.durationMs()) / (2L * take.durationMs()));
    }

    private static FocusCheckResult startSooner(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        boolean done = now.firstWordMs() >= 0 && now.firstWordMs() <= s.startSoonerDoneWithinMs();
        return result(fix, doneOrNotYet(done), FocusMeasure.FIRST_WORD_MS, before.firstWordMs(), now.firstWordMs());
    }

    private static FocusCheckResult splitSentence(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        boolean done = now.longestSentenceWords() <= s.splitSentenceDoneMaxWords();
        return result(
                fix,
                doneOrNotYet(done),
                FocusMeasure.LONGEST_SENTENCE_WORDS,
                before.longestSentenceWords(),
                now.longestSentenceWords());
    }

    /** Done when the share of windows inside the take's own band reaches the Done share. */
    private static FocusCheckResult steadyPace(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        int windows = now.windowWpm().size();
        boolean done =
                windows > 0 && (long) now.windowsInBand() * 100 >= (long) s.steadyPaceDoneInBandPercent() * windows;
        return result(
                fix, doneOrNotYet(done), FocusMeasure.WINDOWS_IN_BAND_PERCENT, bandPercent(before), bandPercent(now));
    }

    private static int bandPercent(TakeFacts take) {
        int windows = take.windowWpm().size();
        if (windows == 0) {
            return 0;
        }
        return (int) ((200L * take.windowsInBand() + windows) / (2L * windows));
    }

    /** Done when the level rose by the set rise; a level already at the top stays Done. */
    private static FocusCheckResult languageFix(FixChoice fix, TakeFacts before, TakeFacts now, FixSettings s) {
        int was = level(fix.id(), before.language());
        int is = level(fix.id(), now.language());
        boolean done = is >= Math.min(MAX_LANGUAGE_LEVEL, was + s.languageDoneRise());
        return result(fix, doneOrNotYet(done), FocusMeasure.LANGUAGE_LEVEL, was, is);
    }

    private static int level(FixId fix, LanguageLevels language) {
        return switch (fix) {
            case SAY_IT_PLAINLY -> language.clarity();
            case SAY_IT_SHORTER -> language.conciseness();
            default -> language.accuracy();
        };
    }

    private static FocusResult doneOrNotYet(boolean done) {
        return done ? FocusResult.DONE : FocusResult.NOT_YET;
    }

    private static FocusCheckResult result(
            FixChoice fix, FocusResult result, FocusMeasure measure, int before, int after) {
        return new FocusCheckResult(fix.id(), result, new FocusEvidence(measure, before, after), Optional.empty());
    }
}
