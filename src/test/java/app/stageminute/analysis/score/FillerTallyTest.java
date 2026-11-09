package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 03 F-031: the sounds that always count are confirmed by code; the in-context words count only when the one call
 * labelled them a filler; hedges are counted and never confirmed. Clusters are 3 or more confirmed fillers in 10 s.
 * Expected values are worked by hand.
 */
class FillerTallyTest {

    static final MetricsSettings DEFAULT = MetricsSettings.LAUNCH_DEFAULT;

    private static FillerCandidate sound(int id, int startMs) {
        return new FillerCandidate(id, FillerKind.ALWAYS_COUNTED, id, id, startMs);
    }

    private static FillerCandidate inContext(int id, int startMs) {
        return new FillerCandidate(id, FillerKind.IN_CONTEXT, id, id, startMs);
    }

    private static FillerCandidate hedge(int id, int startMs) {
        return new FillerCandidate(id, FillerKind.HEDGE, id, id, startMs);
    }

    @Test
    void alwaysCountedSoundsAreConfirmedWithoutAnyLabel() {
        FillerFacts facts = FillerTally.of(List.of(sound(0, 1000), sound(1, 9000)), Map.of(), 60_000, DEFAULT);

        assertThat(facts.confirmed()).isEqualTo(2);
        assertThat(facts.confirmedIds()).containsExactly(0, 1);
    }

    @Test
    void theCallCannotTurnAnAlwaysCountedSoundIntoAMeaningfulWord() {
        FillerFacts facts = FillerTally.of(List.of(sound(0, 1000)), Map.of(0, FillerLabel.MEANINGFUL), 60_000, DEFAULT);

        assertThat(facts.confirmed()).isEqualTo(1);
    }

    @Test
    void anInContextWordCountsOnlyWhenTheCallLabelledItAFiller() {
        // ids 0 (labelled filler), 1 (labelled meaningful), 2 (no label), 3 (labelled hedge)
        List<FillerCandidate> candidates =
                List.of(inContext(0, 1000), inContext(1, 2000), inContext(2, 3000), inContext(3, 4000));
        Map<Integer, FillerLabel> labels =
                Map.of(0, FillerLabel.FILLER, 1, FillerLabel.MEANINGFUL, 3, FillerLabel.HEDGE);

        FillerFacts facts = FillerTally.of(candidates, labels, 60_000, DEFAULT);

        assertThat(facts.confirmedIds()).containsExactly(0);
        assertThat(facts.confirmed()).isEqualTo(1);
        assertThat(facts.hedges()).isEqualTo(1);
    }

    @Test
    void hedgesAreCountedAndNeverConfirmedEvenIfLabelledAFiller() {
        FillerFacts facts =
                FillerTally.of(List.of(hedge(0, 1000), hedge(1, 2000)), Map.of(0, FillerLabel.FILLER), 60_000, DEFAULT);

        assertThat(facts.confirmed()).isZero();
        assertThat(facts.hedges()).isEqualTo(2);
    }

    @Test
    void aLabelForAnIdThatIsNotACandidateChangesNothing() {
        FillerFacts facts = FillerTally.of(List.of(sound(0, 1000)), Map.of(7, FillerLabel.FILLER), 60_000, DEFAULT);

        assertThat(facts.confirmed()).isEqualTo(1);
        assertThat(facts.hedges()).isZero();
    }

    @Test
    void ratePerMinuteIsRoundedHalfUp() {
        // 6 confirmed in 45 s = 8.0 a minute; 7 in 45 s = 9.33 -> 9; 1 in 40 s = 1.5 -> 2; none = 0
        assertThat(rate(6, 45_000)).isEqualTo(8);
        assertThat(rate(7, 45_000)).isEqualTo(9);
        assertThat(rate(1, 40_000)).isEqualTo(2);
        assertThat(rate(0, 45_000)).isZero();
    }

    @Test
    void aTakeWithNoLengthHasNoRate() {
        assertThat(rate(3, 0)).isZero();
    }

    private static int rate(int count, int durationMs) {
        List<FillerCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(sound(i, i * 1000));
        }
        return FillerTally.of(candidates, Map.of(), durationMs, DEFAULT).perMinute();
    }

    @Test
    void threeConfirmedFillersInTenSecondsMakeOneCluster() {
        FillerFacts facts =
                FillerTally.of(List.of(sound(0, 1000), sound(1, 2000), sound(2, 3000)), Map.of(), 60_000, DEFAULT);

        assertThat(facts.clusters()).containsExactly(List.of(0, 1, 2));
        assertThat(facts.clusterCount()).isEqualTo(1);
    }

    @Test
    void twoConfirmedFillersAreNotACluster() {
        FillerFacts facts = FillerTally.of(List.of(sound(0, 1000), sound(1, 2000)), Map.of(), 60_000, DEFAULT);

        assertThat(facts.clusters()).isEmpty();
    }

    @Test
    void anUnconfirmedCandidateBetweenTwoFillersDoesNotCountTowardACluster() {
        // starts 1000 (sound), 2000 (in context, no label), 3000 (sound), 4000 (sound): confirmed are ids 0, 2, 3
        List<FillerCandidate> candidates = List.of(sound(0, 1000), inContext(1, 2000), sound(2, 3000), sound(3, 4000));

        FillerFacts facts = FillerTally.of(candidates, Map.of(), 60_000, DEFAULT);

        assertThat(facts.clusters()).containsExactly(List.of(0, 2, 3));
    }

    @Test
    void confirmedFillersAndClustersCarryTheirTimes() {
        // F-031: items and clusters "with times". Confirmed are ids 0, 2, 3 at 1000, 3000 and 4000 ms; the cluster
        // starts at its first filler, 1000 ms.
        List<FillerCandidate> candidates = List.of(sound(0, 1000), inContext(1, 2000), sound(2, 3000), sound(3, 4000));

        FillerFacts facts = FillerTally.of(candidates, Map.of(), 60_000, DEFAULT);

        assertThat(facts.confirmedStartsMs()).containsExactly(1000, 3000, 4000);
        assertThat(facts.clusterStartsMs()).containsExactly(1000);
    }

    @Test
    void aLaterClusterStartsAtItsOwnFirstFiller() {
        List<FillerCandidate> candidates = List.of(
                sound(0, 0),
                sound(1, 20_000),
                sound(2, 21_000),
                sound(3, 22_000),
                sound(4, 40_000),
                sound(5, 41_000),
                sound(6, 42_000));

        FillerFacts facts = FillerTally.of(candidates, Map.of(), 60_000, DEFAULT);

        assertThat(facts.clusterStartsMs()).containsExactly(20_000, 40_000);
    }

    @Test
    void aLoneFillerBeforeAClusterIsNotInIt() {
        // 0 s alone, then 20 s, 21 s, 22 s -> one cluster of ids 1, 2, 3
        List<FillerCandidate> candidates = List.of(sound(0, 0), sound(1, 20_000), sound(2, 21_000), sound(3, 22_000));

        FillerFacts facts = FillerTally.of(candidates, Map.of(), 60_000, DEFAULT);

        assertThat(facts.clusters()).containsExactly(List.of(1, 2, 3));
        assertThat(facts.confirmed()).isEqualTo(4);
    }
}
