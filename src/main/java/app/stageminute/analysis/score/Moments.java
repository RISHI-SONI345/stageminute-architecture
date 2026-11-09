package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chooses the up-to-3 moments on the take map (glossary `moment`; 03 F-026, F-027). The requirements name the kinds
 * (fillers, pause, how you ended) and the maximum, and nothing else, so the choice is a reading (C-39): a recorded
 * pause (1.2 s or more) is a pause moment, a filler cluster is a filler moment, and the last sentence is the ending
 * moment when the take hit the cap or has no closing. The fix's own place comes first; the rest take turns by kind,
 * the best of each (longest pause, biggest cluster) before the second best. Pure: no clock, no user, no words.
 */
public final class Moments {

    private static final int CLOSING = 3;

    private Moments() {}

    /**
     * Picks the moments of a take.
     *
     * @param candidates the filler candidates, where the id is the position in the list
     * @param fillers the filler numbers built from those candidates
     * @param take the take's facts (whether it hit the cap, and whether the closing is missing)
     * @param fix the fix the report puts first, if any
     */
    public static TakeMoments pick(
            Transcript transcript,
            List<FillerCandidate> candidates,
            FillerFacts fillers,
            TakeFacts take,
            Optional<FixId> fix,
            MetricsSettings metrics,
            MomentSettings settings) {
        Map<MomentKind, List<Moment>> byKind = new EnumMap<>(MomentKind.class);
        byKind.put(MomentKind.PAUSE, pauses(transcript, metrics));
        byKind.put(MomentKind.FILLER_CLUSTER, clusters(transcript, candidates, fillers));
        byKind.put(MomentKind.ENDING, ending(transcript, take));

        Optional<Moment> fixMoment = fix.flatMap(id -> placeOf(id, byKind));
        Set<Moment> chosen = new LinkedHashSet<>();
        fixMoment.ifPresent(chosen::add);
        int deepest = byKind.values().stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < deepest && chosen.size() < settings.maxMoments(); rank++) {
            for (MomentKind kind : settings.kindOrder()) {
                List<Moment> ranked = byKind.get(kind);
                if (rank < ranked.size() && chosen.size() < settings.maxMoments()) {
                    chosen.add(ranked.get(rank));
                }
            }
        }
        List<Moment> inTimeOrder = new ArrayList<>(chosen);
        inTimeOrder.sort(Comparator.comparingInt(Moment::startMs).thenComparingInt(Moment::endMs));
        return new TakeMoments(inTimeOrder, fixMoment);
    }

    /** The place a fix is about: the longest pause, the biggest cluster, or the ending. */
    private static Optional<Moment> placeOf(FixId fix, Map<MomentKind, List<Moment>> byKind) {
        MomentKind kind = switch (fix) {
            case NO_FREEZE -> MomentKind.PAUSE;
            case FILLER_CLUSTER -> MomentKind.FILLER_CLUSTER;
            case ADD_CLOSING, SAY_RESULT, WRAP_UP_IN_TIME -> MomentKind.ENDING;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        return byKind.get(kind).stream().findFirst();
    }

    /** Recorded pauses, the longest first, the earlier one on a tie. The silence before the first word is not one. */
    private static List<Moment> pauses(Transcript transcript, MetricsSettings metrics) {
        List<TimedWord> words = transcript.words();
        List<Moment> pauses = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            int from = words.get(i - 1).endMs();
            int to = words.get(i).startMs();
            if (to - from >= metrics.pauseRecordMs()) {
                pauses.add(new Moment(MomentKind.PAUSE, from, to));
            }
        }
        pauses.sort(Comparator.comparingInt((Moment m) -> m.endMs() - m.startMs())
                .reversed()
                .thenComparingInt(Moment::startMs));
        return pauses;
    }

    /** Filler clusters, the biggest first, the earlier one on a tie; from the first filler's start to the last's end. */
    private static List<Moment> clusters(Transcript transcript, List<FillerCandidate> candidates, FillerFacts fillers) {
        Map<Integer, FillerCandidate> byId =
                candidates.stream().collect(Collectors.toMap(FillerCandidate::id, Function.identity(), (a, b) -> a));
        List<List<Integer>> ranked = new ArrayList<>(fillers.clusters());
        ranked.sort(Comparator.comparingInt((List<Integer> ids) -> ids.size())
                .reversed()
                .thenComparingInt(ids -> byId.get(ids.get(0)).startMs()));
        List<Moment> moments = new ArrayList<>();
        for (List<Integer> ids : ranked) {
            FillerCandidate first = byId.get(ids.get(0));
            FillerCandidate last = byId.get(ids.get(ids.size() - 1));
            moments.add(new Moment(
                    MomentKind.FILLER_CLUSTER,
                    first.startMs(),
                    transcript.words().get(last.lastWord()).endMs()));
        }
        return moments;
    }

    /** The last sentence, when the take hit the cap or has no closing; otherwise there is no ending moment. */
    private static List<Moment> ending(Transcript transcript, TakeFacts take) {
        boolean notable = take.hitCap() || take.steps().get(CLOSING) == StepStatus.MISSING;
        if (!notable || transcript.words().isEmpty()) {
            return List.of();
        }
        List<TimedWord> words = transcript.words();
        TimedWord lastWord = words.get(words.size() - 1);
        int startMs = transcript.sentences().isEmpty()
                ? lastWord.startMs()
                : words.get(transcript
                                .sentences()
                                .get(transcript.sentences().size() - 1)
                                .firstWord())
                        .startMs();
        return List.of(new Moment(MomentKind.ENDING, startMs, lastWord.endMs()));
    }
}
