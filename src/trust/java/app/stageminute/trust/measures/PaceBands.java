package app.stageminute.trust.measures;

import app.stageminute.analysis.score.PaceBand;
import app.stageminute.analysis.score.PersonalBandSettings;
import app.stageminute.analysis.score.PersonalPaceBand;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Stats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pace-band comparison (issue #19, C-35). For each speaker: the standard deviation of the take paces, and how many
 * takes get FULL pace points (every 10 s window inside the band) under three bands: the launch band (85 to 240), the
 * personal band (median of the other takes plus or minus 25, the product's rule) and a mean plus or minus 1 SD band of
 * the other takes. The take being scored is left out of its own band, as the product does. With too few other takes the
 * launch band is used, so a short history never counts against a band. It only measures: any change of the band is a
 * new {@code formula_version} and a row in {@code 01}.
 */
public final class PaceBands {

    private static final PersonalBandSettings PERSONAL = PersonalBandSettings.LAUNCH_DEFAULT;
    private static final int MIN_OTHERS_FOR_SD = 2;

    private PaceBands() {}

    /**
     * One speaker's row.
     *
     * @param speakerId the anonymous id
     * @param takes scored takes with pace windows
     * @param paceSd sample SD of the takes' average pace (0 for one take)
     * @param fullLaunch takes with full pace points under the launch band
     * @param fullPersonal takes with full pace points under the personal band
     * @param fullMeanSd takes with full pace points under the mean plus or minus 1 SD band
     */
    public record Speaker(
            String speakerId, int takes, double paceSd, int fullLaunch, int fullPersonal, int fullMeanSd) {}

    /** The counts over every speaker. */
    public record Totals(int takes, int fullLaunch, int fullPersonal, int fullMeanSd) {}

    public static List<Speaker> compare(List<TakeCase> cases) {
        Map<String, List<Observation>> bySpeaker = new LinkedHashMap<>();
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isPresent() && !run.get().windowWpm().isEmpty()) {
                bySpeaker
                        .computeIfAbsent(take.speakerId(), key -> new ArrayList<>())
                        .add(run.get());
            }
        }
        List<Speaker> speakers = new ArrayList<>();
        bySpeaker.forEach((id, takes) -> speakers.add(row(id, takes)));
        return speakers;
    }

    public static Totals totals(List<Speaker> speakers) {
        return new Totals(
                speakers.stream().mapToInt(Speaker::takes).sum(),
                speakers.stream().mapToInt(Speaker::fullLaunch).sum(),
                speakers.stream().mapToInt(Speaker::fullPersonal).sum(),
                speakers.stream().mapToInt(Speaker::fullMeanSd).sum());
    }

    private static Speaker row(String id, List<Observation> takes) {
        List<Double> paces =
                takes.stream().map(take -> (double) take.averageWpm()).toList();
        int launch = 0;
        int personal = 0;
        int meanSd = 0;
        for (int i = 0; i < takes.size(); i++) {
            List<Integer> others = new ArrayList<>();
            for (int j = 0; j < takes.size(); j++) {
                if (j != i) {
                    others.add(takes.get(j).averageWpm());
                }
            }
            List<Integer> windows = takes.get(i).windowWpm();
            launch += full(windows, PaceBand.LAUNCH_DEFAULT);
            personal += full(windows, personalBand(others));
            meanSd += full(windows, meanSdBand(others));
        }
        return new Speaker(id, takes.size(), Stats.sampleSd(paces), launch, personal, meanSd);
    }

    private static PaceBand personalBand(List<Integer> others) {
        if (others.isEmpty()) {
            return PaceBand.LAUNCH_DEFAULT;
        }
        PersonalBandSettings settings = new PersonalBandSettings(
                1, Math.max(PERSONAL.lastTakes(), others.size()), PERSONAL.halfWidthWpm(), PERSONAL.limits());
        return PersonalPaceBand.choose(others, settings);
    }

    private static PaceBand meanSdBand(List<Integer> others) {
        if (others.size() < MIN_OTHERS_FOR_SD) {
            return PaceBand.LAUNCH_DEFAULT;
        }
        List<Double> values = others.stream().map(Integer::doubleValue).toList();
        double mean = Stats.mean(values);
        double sd = Stats.sampleSd(values);
        PaceBand limits = PERSONAL.limits();
        int low = clamp((int) Math.round(mean - sd), limits);
        int high = clamp((int) Math.round(mean + sd), limits);
        return new PaceBand(low, high);
    }

    private static int clamp(int wpm, PaceBand limits) {
        return Math.min(Math.max(wpm, limits.lowWpm()), limits.highWpm());
    }

    /** 1 when every window is inside the band (both edges count as inside), else 0. */
    private static int full(List<Integer> windows, PaceBand band) {
        return windows.stream().allMatch(wpm -> wpm >= band.lowWpm() && wpm <= band.highWpm()) ? 1 : 0;
    }
}
