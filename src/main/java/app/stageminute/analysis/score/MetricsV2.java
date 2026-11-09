package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Speech facts from a transcript, by code only (03 F-027, F-031 and F-036, "Metrics v2", requirements version 1.7).
 * Pure: no clock, no user, no Spring, and no model. Times are whole milliseconds and every threshold and word list
 * comes from {@link MetricsSettings}.
 *
 * <p>Not here: {@code hit_cap} (the pipeline knows the cap) and the personal pace band (see {@link PersonalPaceBand}).
 * It is checked against the research script (step2/research/scripts/metrics_v2.py) on 42 real clips by
 * ResearchFixtureParityTest, where the two define a measure the same way (AD-98).
 */
public final class MetricsV2 {

    private static final long MS_PER_MINUTE = 60_000L;

    private MetricsV2() {}

    public static SpeechFacts compute(Transcript transcript, MetricsSettings settings) {
        List<TimedWord> words = transcript.words();
        int pausesRecorded = 0;
        int midAnswerPauses = 0;
        int longestPause = 0;
        int recordedPauseMs = 0;
        for (int i = 1; i < words.size(); i++) {
            int gap = words.get(i).startMs() - words.get(i - 1).endMs();
            if (gap >= settings.pauseRecordMs()) {
                pausesRecorded++;
                recordedPauseMs += gap;
                longestPause = Math.max(longestPause, gap);
            }
            if (gap >= settings.pauseCountMs()) {
                midAnswerPauses++;
            }
        }
        int lowConfidence = (int) words.stream()
                .filter(w -> w.confidence() < settings.lowConfidenceBelow())
                .count();
        int habitCount = habitMarkerCount(transcript, settings);

        int spanMs = words.isEmpty()
                ? 0
                : Math.max(0, words.get(words.size() - 1).endMs() - words.get(0).startMs());
        int speakingMs = spanMs - recordedPauseMs;
        Windows windows = paceWindows(transcript, settings);

        return new SpeechFacts(
                words.size(),
                transcript.durationMs(),
                words.isEmpty() ? -1 : words.get(0).startMs(),
                pausesRecorded,
                midAnswerPauses,
                longestPause,
                windows.wpm(),
                lowConfidence,
                habitCount,
                habitCount >= settings.habitMarkerMinCount(),
                runOnSentences(transcript, settings),
                wordsPerMinute(words.size(), speakingMs),
                wordsPerMinute(words.size(), spanMs),
                windows.outsideBandMs(),
                windows.outsideBandMs() >= settings.paceFlagMs(),
                fillerCandidates(words, settings),
                misheardWords(words, settings),
                spanMs,
                longestSentenceWords(transcript));
    }

    /**
     * Confirmed fillers that make clusters. Sorted by time; a cluster starts at a filler and takes every filler less
     * than the window after it. It counts when it holds at least the minimum, and the next cluster starts after it.
     */
    public static int fillerClusters(List<Integer> confirmedFillerStartsMs, MetricsSettings settings) {
        return fillerClusterGroups(confirmedFillerStartsMs, settings).size();
    }

    /**
     * The confirmed fillers of each cluster, as start times in time order. Same rule as {@link #fillerClusters}: a
     * cluster starts at a filler and takes every filler less than the window after it; it counts when it holds at
     * least the minimum, and the next cluster starts after it.
     */
    public static List<List<Integer>> fillerClusterGroups(
            List<Integer> confirmedFillerStartsMs, MetricsSettings settings) {
        List<Integer> times = new ArrayList<>(confirmedFillerStartsMs);
        Collections.sort(times);
        List<List<Integer>> groups = new ArrayList<>();
        int i = 0;
        while (i < times.size()) {
            int j = i;
            while (j < times.size() && times.get(j) - times.get(i) < settings.clusterWindowMs()) {
                j++;
            }
            if (j - i >= settings.clusterMinFillers()) {
                groups.add(List.copyOf(times.subList(i, j)));
                i = j;
            } else {
                i++;
            }
        }
        return groups;
    }

    private static int longestSentenceWords(Transcript transcript) {
        return transcript.sentences().stream()
                .mapToInt(SentenceSpan::wordCount)
                .max()
                .orElse(0);
    }

    /** Words a minute over a time, rounded half up; 0 when there is no time. */
    private static int wordsPerMinute(int wordCount, int timeMs) {
        if (timeMs <= 0) {
            return 0;
        }
        return (int) ((2L * wordCount * MS_PER_MINUTE + timeMs) / (2L * timeMs));
    }

    /** Pace of each window and the total time of the windows outside the pace-flag band. */
    private record Windows(List<Integer> wpm, int outsideBandMs) {}

    /**
     * Words a minute in each window. Full windows hold words x 60 000 / window length. A last partial window counts
     * when it is at least the minimum long and is scaled to its own length; a shorter one is left out.
     */
    private static Windows paceWindows(Transcript transcript, MetricsSettings settings) {
        int windowMs = settings.windowMs();
        int fullWindows = transcript.durationMs() / windowMs;
        int remainderMs = transcript.durationMs() % windowMs;
        int windows = fullWindows + (remainderMs >= settings.minFinalWindowMs() ? 1 : 0);
        int[] counts = new int[windows];
        for (TimedWord word : transcript.words()) {
            int index = word.startMs() / windowMs;
            if (index < windows) {
                counts[index]++;
            }
        }
        List<Integer> wpm = new ArrayList<>();
        int outsideMs = 0;
        for (int i = 0; i < windows; i++) {
            int lengthMs = i < fullWindows ? windowMs : remainderMs;
            int windowWpm = wordsPerMinute(counts[i], lengthMs);
            wpm.add(windowWpm);
            if (!settings.paceFlagBand().contains(windowWpm)) {
                outsideMs += lengthMs;
            }
        }
        return new Windows(wpm, outsideMs);
    }

    private static int habitMarkerCount(Transcript transcript, MetricsSettings settings) {
        int count = 0;
        for (SentenceSpan sentence : transcript.sentences()) {
            String first = transcript.words().get(sentence.firstWord()).text();
            String normalised = first.replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
            if (settings.habitMarkers().contains(normalised)) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> runOnSentences(Transcript transcript, MetricsSettings settings) {
        List<Integer> runOns = new ArrayList<>();
        for (int i = 0; i < transcript.sentences().size(); i++) {
            if (transcript.sentences().get(i).wordCount() > settings.runOnOverWords()) {
                runOns.add(i);
            }
        }
        return runOns;
    }

    /**
     * Filler candidates in order. At each word the longest matching phrase wins and candidates never overlap, so "you
     * know" is one candidate and not "you" then "know".
     */
    private static List<FillerCandidate> fillerCandidates(List<TimedWord> words, MetricsSettings settings) {
        List<String> normalised =
                words.stream().map(w -> WordLists.normalise(w.text())).toList();
        List<WordLists.Phrase> phrases = settings.wordLists().phrases();
        List<FillerCandidate> found = new ArrayList<>();
        int position = 0;
        while (position < words.size()) {
            WordLists.Phrase match = null;
            for (WordLists.Phrase phrase : phrases) {
                if (startsWith(normalised, position, phrase.words())) {
                    match = phrase;
                    break;
                }
            }
            if (match == null) {
                position++;
            } else {
                int last = position + match.words().size() - 1;
                found.add(new FillerCandidate(
                        found.size(),
                        match.kind(),
                        position,
                        last,
                        words.get(position).startMs()));
                position = last + 1;
            }
        }
        return found;
    }

    private static boolean startsWith(List<String> words, int position, List<String> phrase) {
        if (position + phrase.size() > words.size()) {
            return false;
        }
        return words.subList(position, position + phrase.size()).equals(phrase);
    }

    /**
     * Positions of up to the maximum count of content words Deepgram was unsure of (confidence under the line, not a
     * function word): the least sure first, the earlier word on a tie, then listed by position. Never quoted.
     */
    private static List<Integer> misheardWords(List<TimedWord> words, MetricsSettings settings) {
        return IntStream.range(0, words.size())
                .filter(i -> words.get(i).confidence() < settings.lowConfidenceBelow())
                .filter(i -> isContentWord(words.get(i).text(), settings))
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> words.get(i).confidence())
                        .thenComparingInt(i -> i))
                .limit(settings.maxMisheardWords())
                .sorted()
                .toList();
    }

    private static boolean isContentWord(String text, MetricsSettings settings) {
        String normalised = WordLists.normalise(text);
        return !normalised.isEmpty() && !settings.wordLists().isFunctionWord(normalised);
    }
}
