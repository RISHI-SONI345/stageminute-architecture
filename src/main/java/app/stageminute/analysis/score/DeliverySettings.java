package app.stageminute.analysis.score;

/**
 * Settings of the Delivery part (03 F-030). Values are in hundredths of a point ("Cp"), so 2.7 is 270 and no
 * penalty has a fractional part. {@link #LAUNCH_DEFAULT} holds the approved launch values (D-55).
 *
 * @param flowMaxCp most the flow half can earn (10 points)
 * @param paceMaxCp most the pace half can earn (10 points)
 * @param pausePenaltyCp taken off flow for each mid-answer pause of 5 s or more (2.7)
 * @param clusterPenaltyCp taken off flow for each confirmed filler cluster (2)
 * @param fillerRatePenaltyCp taken off flow once when confirmed fillers reach the limit a minute (1.3)
 * @param fillersPerMinuteLimit confirmed fillers a minute at which the rate penalty applies (8)
 */
public record DeliverySettings(
        int flowMaxCp,
        int paceMaxCp,
        int pausePenaltyCp,
        int clusterPenaltyCp,
        int fillerRatePenaltyCp,
        int fillersPerMinuteLimit) {

    public static final DeliverySettings LAUNCH_DEFAULT = new DeliverySettings(1000, 1000, 270, 200, 130, 8);

    public DeliverySettings {
        if (flowMaxCp <= 0 || paceMaxCp <= 0) {
            throw new IllegalArgumentException("flow and pace must each be worth more than 0 points");
        }
        if (pausePenaltyCp < 0 || clusterPenaltyCp < 0 || fillerRatePenaltyCp < 0) {
            throw new IllegalArgumentException("a penalty cannot be negative");
        }
        if (fillersPerMinuteLimit <= 0) {
            throw new IllegalArgumentException("fillersPerMinuteLimit must be positive");
        }
    }
}
