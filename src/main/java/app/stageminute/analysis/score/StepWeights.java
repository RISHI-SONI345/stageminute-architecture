package app.stageminute.analysis.score;

import java.util.List;

/**
 * Points each of the four steps of a style can earn, in hundredths of a point so no weight has a fractional part
 * (13.5 points is 1350). Order is the style's order: PREP is Point, Reason, Example, Closing; STAR is Situation,
 * Task, Action, Result.
 */
public record StepWeights(int firstCp, int secondCp, int thirdCp, int fourthCp) {

    public StepWeights {
        if (firstCp <= 0 || secondCp <= 0 || thirdCp <= 0 || fourthCp <= 0) {
            throw new IllegalArgumentException("every step must be worth more than 0 points");
        }
    }

    /** Weights as a list in step order. */
    public List<Integer> asList() {
        return List.of(firstCp, secondCp, thirdCp, fourthCp);
    }

    public int totalCp() {
        return firstCp + secondCp + thirdCp + fourthCp;
    }
}
