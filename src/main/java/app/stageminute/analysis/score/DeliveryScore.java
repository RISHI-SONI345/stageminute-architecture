package app.stageminute.analysis.score;

/**
 * Delivery points (03 F-030). Pure: no clock, no user, no Spring. Points are in hundredths ("Cp") until the part is
 * rounded once at the end.
 */
public final class DeliveryScore {

    private static final long MS_PER_MINUTE = 60_000L;

    private DeliveryScore() {}

    /**
     * Delivery points: flow plus pace, added exactly and rounded once, half up. Pace is the maximum times the share of
     * 10 s windows inside the band. With {@code flow} in hundredths and {@code in/total} the share, the points are
     * {@code (flow x total + paceMax x in) / (100 x total)}, all in whole numbers.
     */
    public static int points(DeliveryFacts facts, PaceBand band, DeliverySettings settings) {
        long flowCp = flowCp(
                facts.midAnswerPauses(),
                facts.fillerClusters(),
                facts.confirmedFillers(),
                facts.durationMs(),
                settings);
        long total = facts.windowWpm().size();
        long inBand = facts.windowWpm().stream().filter(band::contains).count();
        long numerator = flowCp * total + settings.paceMaxCp() * inBand;
        long denominator = 100 * total;
        return (int) ((2 * numerator + denominator) / (2 * denominator));
    }

    /**
     * Flow half in hundredths of a point: the maximum, minus a penalty per mid-answer pause of 5 s or more, minus a
     * penalty per confirmed filler cluster, minus one penalty when confirmed fillers reach the limit a minute; never
     * below 0. The rate compares whole numbers ({@code fillers x 60 000 >= limit x durationMs}), so exactly 8 a minute
     * counts.
     */
    public static int flowCp(
            int midAnswerPauses, int fillerClusters, int confirmedFillers, int durationMs, DeliverySettings settings) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive, was " + durationMs);
        }
        if (midAnswerPauses < 0 || fillerClusters < 0 || confirmedFillers < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
        boolean fastFillers = confirmedFillers * MS_PER_MINUTE >= (long) settings.fillersPerMinuteLimit() * durationMs;
        long flow = settings.flowMaxCp()
                - (long) midAnswerPauses * settings.pausePenaltyCp()
                - (long) fillerClusters * settings.clusterPenaltyCp()
                - (fastFillers ? settings.fillerRatePenaltyCp() : 0);
        return (int) Math.max(0, flow);
    }
}
