package app.stageminute.trust.gates;

import app.stageminute.trust.measures.ExcellentShare;
import app.stageminute.trust.measures.Fairness;
import app.stageminute.trust.measures.FillerPrecision;
import app.stageminute.trust.measures.GrammarOnAsrErrors;
import app.stageminute.trust.measures.LabelFlip;
import app.stageminute.trust.measures.LengthBias;
import app.stageminute.trust.measures.PaceAccuracy;
import app.stageminute.trust.measures.PaceBands;
import app.stageminute.trust.measures.PauseMatch;
import app.stageminute.trust.measures.RunToRun;
import app.stageminute.trust.measures.ScoreSpread;
import app.stageminute.trust.measures.StepAgreement;
import app.stageminute.trust.measures.TakeLength;
import app.stageminute.trust.measures.WaitTimes;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Bootstrap;
import app.stageminute.trust.stats.Interval;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Builds every gate line of {@code 11} section 1, and the extra measurements of issue #19, from the runs. Each number
 * that depends on the speakers gets a 95 % bootstrap range from resampling the speakers (a fixed seed, so the report
 * is repeatable): the set has only 10 to 15 speakers.
 */
public final class GateTable {

    /** The fixed random seed of every range in the report. */
    public static final long SEED = 20_261_102L;

    private static final double MICROS_PER_DOLLAR = 1_000_000.0;

    private GateTable() {}

    /**
     * The extra measurements printed under the gate table.
     *
     * @param speakers how many speakers ran
     * @param labelledTakes labelled takes run
     * @param clips replay clips run
     * @param runsPerClip how many runs each clip had
     * @param paceSpeakers the pace-band comparison per speaker
     * @param paceTotals the same over all speakers
     * @param labelFlip the label-flip noise result
     * @param waits p50 and p95 of the real wait
     * @param takeLength server length against the phone's
     * @param annotatorAgreement how often the two annotators agree on a step, or NaN
     * @param firstFixAgreement how often the pipeline's first fix is the annotators' (ties count as a miss), or NaN
     * @param lengthBias correlation of length and strong steps, or NaN
     * @param fillerRule which annotators had to mark a filler
     * @param fairnessGroups the three fairness measures by accent group
     */
    public record Extras(
            int speakers,
            int labelledTakes,
            int clips,
            int runsPerClip,
            List<PaceBands.Speaker> paceSpeakers,
            PaceBands.Totals paceTotals,
            LabelFlip.Result labelFlip,
            WaitTimes.Result waits,
            TakeLength.Result takeLength,
            double annotatorAgreement,
            double firstFixAgreement,
            double lengthBias,
            FillerPrecision.Rule fillerRule,
            Map<String, Map<String, Double>> fairnessGroups) {}

    /** The gate lines and the extras. */
    public record Table(List<Gate> gates, Extras extras) {}

    public static Table build(
            List<TakeCase> labelled,
            List<TakeCase> replay,
            FillerPrecision.Rule rule,
            boolean real,
            OptionalLong costBaselineMicros) {
        List<TakeCase> all = new ArrayList<>(labelled);
        all.addAll(replay);
        int runsPerClip =
                replay.stream().mapToInt(clip -> clip.scoredRuns().size()).min().orElse(0);

        StepAgreement.Result steps = StepAgreement.pipelineVsAnnotators(labelled);
        RunToRun.Result runToRun = RunToRun.of(replay);
        ToDoubleFunction<List<TakeCase>> filler = group -> FillerPrecision.of(group, rule);

        List<Gate> gates = new ArrayList<>();
        gates.add(Gates.fillerPrecision(FillerPrecision.of(labelled, rule), bySpeaker(labelled, filler)));
        gates.add(Gates.stepAgreement(
                steps.agreement(),
                steps.kappa(),
                steps.cells(),
                bySpeaker(
                        labelled,
                        group -> StepAgreement.pipelineVsAnnotators(group).agreement())));
        gates.add(Gates.runToRun(
                runToRun.agreement(),
                runToRun.kappa(),
                runsPerClip,
                byClip(replay, group -> RunToRun.of(group).agreement())));
        gates.add(
                Gates.grammarOnAsrErrors(GrammarOnAsrErrors.of(labelled), bySpeaker(labelled, GrammarOnAsrErrors::of)));
        Map<String, ToDoubleFunction<List<TakeCase>>> fairness = new LinkedHashMap<>();
        fairness.put("pace", PaceAccuracy::of);
        fairness.put("pause", PauseMatch::f1);
        fairness.put("filler", filler);
        Map<String, Map<String, Double>> groups = new LinkedHashMap<>();
        fairness.forEach((name, measure) -> {
            groups.put(name, Fairness.byGroup(labelled, measure));
            gates.add(Gates.fairness(
                    name,
                    Fairness.spreadPoints(labelled, measure),
                    bySpeaker(labelled, cases -> Fairness.spreadPoints(cases, measure))));
        });
        gates.add(Gates.scoreSpread(ScoreSpread.of(replay)));
        gates.add(Gates.excellentShare(ExcellentShare.of(labelled), bySpeaker(labelled, ExcellentShare::of)));
        WaitTimes.Result wait = WaitTimes.of(all);
        gates.add(Gates.latency(wait));
        gates.add(Gates.cost(meanCostMicros(all, real), costBaselineMicros));

        Extras extras = new Extras(
                (int) labelled.stream().map(TakeCase::speakerId).distinct().count(),
                labelled.size(),
                replay.size(),
                runsPerClip,
                PaceBands.compare(labelled),
                PaceBands.totals(PaceBands.compare(labelled)),
                LabelFlip.noiseOf(all, PassLines.NOISE_BAND_POINTS),
                wait,
                TakeLength.of(all),
                StepAgreement.annotatorsVsEachOther(labelled),
                firstFixAgreement(labelled),
                LengthBias.of(labelled),
                rule,
                groups);
        return new Table(gates, extras);
    }

    /** The mean cost of one run in micros, or NaN when the vendors were fakes (a fake costs nothing to measure). */
    private static double meanCostMicros(List<TakeCase> cases, boolean real) {
        if (!real) {
            return Double.NaN;
        }
        long total = 0;
        int runs = 0;
        for (TakeCase take : cases) {
            for (Observation run : take.runs()) {
                total += run.costMicros();
                runs++;
            }
        }
        return runs == 0 ? Double.NaN : (double) total / runs;
    }

    /** A share of takes where the pipeline's fix is the one every annotator named; takes without a named fix skipped. */
    private static double firstFixAgreement(List<TakeCase> labelled) {
        int judged = 0;
        int same = 0;
        for (TakeCase take : labelled) {
            var run = take.firstScored();
            if (run.isEmpty()
                    || run.get().fixCode().isEmpty()
                    || take.references().isEmpty()) {
                continue;
            }
            boolean named = take.references().stream()
                    .allMatch(reference -> reference.firstFix().isPresent());
            if (!named) {
                continue;
            }
            judged++;
            if (take.references().stream()
                    .allMatch(reference -> reference.firstFix().equals(run.get().fixCode()))) {
                same++;
            }
        }
        return judged == 0 ? Double.NaN : (double) same / judged;
    }

    /** The bootstrap range of a measure, resampling speakers (takes of one speaker are not independent). */
    private static Interval bySpeaker(List<TakeCase> cases, ToDoubleFunction<List<TakeCase>> measure) {
        return resample(cases, TakeCase::speakerId, measure);
    }

    /** The bootstrap range of a measure over replay clips, resampling clips. */
    private static Interval byClip(List<TakeCase> cases, ToDoubleFunction<List<TakeCase>> measure) {
        return resample(cases, TakeCase::takeName, measure);
    }

    private static Interval resample(
            List<TakeCase> cases, Function<TakeCase, String> unit, ToDoubleFunction<List<TakeCase>> measure) {
        Map<String, List<TakeCase>> units = new LinkedHashMap<>();
        for (TakeCase take : cases) {
            units.computeIfAbsent(unit.apply(take), ignored -> new ArrayList<>())
                    .add(take);
        }
        return Bootstrap.interval(
                new ArrayList<>(units.values()),
                sample -> measure.applyAsDouble(
                        sample.stream().flatMap(List::stream).toList()),
                SEED);
    }

    /** Dollars from micros, for the report. */
    public static double dollars(long micros) {
        return micros / MICROS_PER_DOLLAR;
    }
}
