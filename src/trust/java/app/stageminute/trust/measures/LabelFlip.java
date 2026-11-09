package app.stageminute.trust.measures;

import app.stageminute.analysis.score.DeliveryFacts;
import app.stageminute.analysis.score.LanguageLevels;
import app.stageminute.analysis.score.ScoreBuilder;
import app.stageminute.analysis.score.ScoreInput;
import app.stageminute.analysis.score.ScoreSettings;
import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.TakeFacts;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Label-flip noise (F-038 run-to-run, issue #19): how often ONE label changing moves the total score by the noise band
 * or more. The model's labels can wobble between runs; this counts how much a single wobble can cost. Each label of the
 * recorded take is changed in turn (a step to each other status; a level up or down by one), the score is rebuilt with
 * the real score code, and a flip crosses when the total moves by the band or more (the band is "same" below it, 02b
 * F-045). Filler labels are not flipped: the facts record keeps counts only, not the candidates. Nothing here changes a
 * weight, a threshold or a band: it only reads them.
 */
public final class LabelFlip {

    /** What kind of label was flipped. */
    public enum Kind {
        /** Strong and weak, or weak and missing. */
        STEP_ONE_LEVEL,
        /** Strong and missing. */
        STEP_TWO_LEVELS,
        RELEVANCE,
        CLARITY,
        CONCISENESS,
        ACCURACY
    }

    /** Flips made and flips that crossed the band, for one kind or for all. */
    public record Count(int flips, int crossings) {}

    /**
     * The result.
     *
     * @param flips every flip tried
     * @param crossings flips that moved the total by the band or more
     * @param byKind the same, by kind of label
     */
    public record Result(int flips, int crossings, Map<Kind, Count> byKind) {

        /** The share of flips that crossed, or NaN when none was tried. */
        public double share() {
            return flips == 0 ? Double.NaN : (double) crossings / flips;
        }
    }

    private static final ScoreSettings SETTINGS = ScoreSettings.LAUNCH_DEFAULT;
    private static final int LEVELS = 4;

    private LabelFlip() {}

    /** The facts of each scored take that has them, read from the first scored run. */
    public static Result noiseOf(List<TakeCase> cases, int bandPoints) {
        List<TakeFacts> facts = new ArrayList<>();
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            run.flatMap(Observation::facts).ifPresent(facts::add);
        }
        return noise(facts, bandPoints);
    }

    public static Result noise(List<TakeFacts> takes, int bandPoints) {
        Map<Kind, int[]> counts = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            counts.put(kind, new int[2]);
        }
        for (TakeFacts take : takes) {
            int base = total(take);
            for (int step = 0; step < take.steps().size(); step++) {
                StepStatus now = take.steps().get(step);
                for (StepStatus other : StepStatus.values()) {
                    if (other == now) {
                        continue;
                    }
                    List<StepStatus> steps = new ArrayList<>(take.steps());
                    steps.set(step, other);
                    Kind kind = twoLevels(now, other) ? Kind.STEP_TWO_LEVELS : Kind.STEP_ONE_LEVEL;
                    record(
                            counts.get(kind),
                            base,
                            input(take, steps, take.relevanceLevel(), take.language()),
                            bandPoints);
                }
            }
            for (int level : neighbours(take.relevanceLevel())) {
                record(counts.get(Kind.RELEVANCE), base, input(take, take.steps(), level, take.language()), bandPoints);
            }
            LanguageLevels language = take.language();
            for (int level : neighbours(language.clarity())) {
                LanguageLevels changed = new LanguageLevels(level, language.conciseness(), language.accuracy());
                record(
                        counts.get(Kind.CLARITY),
                        base,
                        input(take, take.steps(), take.relevanceLevel(), changed),
                        bandPoints);
            }
            for (int level : neighbours(language.conciseness())) {
                LanguageLevels changed = new LanguageLevels(language.clarity(), level, language.accuracy());
                record(
                        counts.get(Kind.CONCISENESS),
                        base,
                        input(take, take.steps(), take.relevanceLevel(), changed),
                        bandPoints);
            }
            for (int level : neighbours(language.accuracy())) {
                LanguageLevels changed = new LanguageLevels(language.clarity(), language.conciseness(), level);
                record(
                        counts.get(Kind.ACCURACY),
                        base,
                        input(take, take.steps(), take.relevanceLevel(), changed),
                        bandPoints);
            }
        }
        Map<Kind, Count> byKind = new EnumMap<>(Kind.class);
        int flips = 0;
        int crossings = 0;
        for (Map.Entry<Kind, int[]> entry : counts.entrySet()) {
            byKind.put(entry.getKey(), new Count(entry.getValue()[0], entry.getValue()[1]));
            flips += entry.getValue()[0];
            crossings += entry.getValue()[1];
        }
        return new Result(flips, crossings, byKind);
    }

    /** The total the real score code gives these facts. */
    public static int total(TakeFacts take) {
        return ScoreBuilder.build(input(take, take.steps(), take.relevanceLevel(), take.language()), SETTINGS)
                .total();
    }

    private static void record(int[] count, int base, ScoreInput changed, int bandPoints) {
        count[0]++;
        if (Math.abs(ScoreBuilder.build(changed, SETTINGS).total() - base) >= bandPoints) {
            count[1]++;
        }
    }

    /** Strong and missing are two levels apart; strong and weak, and weak and missing, are one. */
    private static boolean twoLevels(StepStatus a, StepStatus b) {
        boolean strongAndMissing = a == StepStatus.STRONG && b == StepStatus.MISSING;
        boolean missingAndStrong = a == StepStatus.MISSING && b == StepStatus.STRONG;
        return strongAndMissing || missingAndStrong;
    }

    private static List<Integer> neighbours(int level) {
        List<Integer> levels = new ArrayList<>();
        if (level > 1) {
            levels.add(level - 1);
        }
        if (level < LEVELS) {
            levels.add(level + 1);
        }
        return levels;
    }

    private static ScoreInput input(TakeFacts take, List<StepStatus> steps, int relevance, LanguageLevels language) {
        return new ScoreInput(
                take.style(),
                steps,
                relevance,
                language,
                new DeliveryFacts(
                        take.midAnswerPauses(),
                        take.fillerClusters(),
                        take.confirmedFillers(),
                        take.durationMs(),
                        take.windowWpm()),
                take.paceBand());
    }
}
