package app.stageminute.analysis.score;

/**
 * Every number in the Fix Library and the focus check (03 F-032, F-033: "thresholds in config"). {@link
 * #LAUNCH_DEFAULT} holds the requirement values until settings override them. The trigger for a fix and its "Done
 * when" line sit side by side.
 *
 * @param speakLongerMinWords `speak_longer` fires under this many words, and is Done at this many (40)
 * @param speakLongerMinSpeechMs `speak_longer` fires under this much speech (20 s)
 * @param speakLongerDoneMinSpeechMs `speak_longer` is Done at this much speech (25 s)
 * @param offTopicLevel `answer_question` fires at this relevance level or lower (1)
 * @param relevanceDoneLevel `answer_question` is Done at this relevance level or higher (3)
 * @param noFreezeMinPauses `no_freeze` fires at this many mid-answer pauses of 5 s or more (1)
 * @param pauseShorterPercent `no_freeze` is Better when the longest gap is this much shorter (30)
 * @param fillersPerMinuteTrigger `filler_cluster` fires at this many confirmed fillers a minute (8)
 * @param fillerClustersTrigger `filler_cluster` fires at this many filler clusters (1: any cluster of 3 or more
 *     confirmed fillers in 10 s; the 3 and the 10 s are in {@link MetricsSettings}), and its cluster half is Done
 *     under this many
 * @param fillerRateDropPercent `filler_cluster` needs the filler rate down this much for Done, and Better on its own
 *     (30)
 * @param startSoonerOverMs `start_sooner` fires when the first word starts after this (4 s)
 * @param startSoonerDoneWithinMs `start_sooner` is Done when the first word starts within this (2 s)
 * @param splitSentenceOverWords `split_sentence` fires for a labelled run-on over this many words (40)
 * @param splitSentenceDoneMaxWords `split_sentence` is Done when the longest sentence has at most this many (30)
 * @param steadyPaceOutsideMs `steady_pace` fires at this much time outside the pace band (20 s, D-86)
 * @param steadyPaceDoneInBandPercent `steady_pace` is Done when this share of windows is inside the band (80)
 * @param windowMs length of a pace window (10 s); the same value as {@link MetricsSettings#windowMs()}
 * @param languageFixMaxLevel a Language fix fires when the lowest level is at or under this (2)
 * @param languageDoneRise a Language fix is Done when its level has risen by this much (1)
 */
public record FixSettings(
        int speakLongerMinWords,
        int speakLongerMinSpeechMs,
        int speakLongerDoneMinSpeechMs,
        int offTopicLevel,
        int relevanceDoneLevel,
        int noFreezeMinPauses,
        int pauseShorterPercent,
        int fillersPerMinuteTrigger,
        int fillerClustersTrigger,
        int fillerRateDropPercent,
        int startSoonerOverMs,
        int startSoonerDoneWithinMs,
        int splitSentenceOverWords,
        int splitSentenceDoneMaxWords,
        int steadyPaceOutsideMs,
        int steadyPaceDoneInBandPercent,
        int windowMs,
        int languageFixMaxLevel,
        int languageDoneRise) {

    public static final FixSettings LAUNCH_DEFAULT =
            new FixSettings(40, 20_000, 25_000, 1, 3, 1, 30, 8, 1, 30, 4000, 2000, 40, 30, 20_000, 80, 10_000, 2, 1);

    public FixSettings {
        if (speakLongerMinWords <= 0 || speakLongerMinSpeechMs <= 0) {
            throw new IllegalArgumentException("speak_longer needs positive words and speech time");
        }
        if (speakLongerDoneMinSpeechMs < speakLongerMinSpeechMs) {
            throw new IllegalArgumentException("speak_longer Done cannot ask for less speech than its trigger");
        }
        if (offTopicLevel < 1 || relevanceDoneLevel <= offTopicLevel || relevanceDoneLevel > 4) {
            throw new IllegalArgumentException("relevance levels need 1 <= offTopic < done <= 4");
        }
        if (noFreezeMinPauses <= 0 || fillersPerMinuteTrigger <= 0 || fillerClustersTrigger <= 0) {
            throw new IllegalArgumentException("pause, cluster and filler-rate triggers must be positive");
        }
        if (!isPercent(pauseShorterPercent) || !isPercent(fillerRateDropPercent)) {
            throw new IllegalArgumentException("the shorter and drop shares are percentages from 1 to 100");
        }
        if (startSoonerOverMs <= 0 || startSoonerDoneWithinMs <= 0 || startSoonerDoneWithinMs > startSoonerOverMs) {
            throw new IllegalArgumentException("start_sooner needs 0 < done-within <= trigger");
        }
        if (splitSentenceOverWords <= 0
                || splitSentenceDoneMaxWords <= 0
                || splitSentenceDoneMaxWords > splitSentenceOverWords) {
            throw new IllegalArgumentException("split_sentence needs 0 < done-max <= trigger");
        }
        if (steadyPaceOutsideMs <= 0 || !isPercent(steadyPaceDoneInBandPercent) || windowMs <= 0) {
            throw new IllegalArgumentException("steady_pace needs a positive time, a percentage and a window");
        }
        if (languageFixMaxLevel < 1 || languageFixMaxLevel > 3 || languageDoneRise < 1) {
            throw new IllegalArgumentException("Language fix needs a max level of 1 to 3 and a rise of at least 1");
        }
    }

    private static boolean isPercent(int value) {
        return value >= 1 && value <= 100;
    }
}
