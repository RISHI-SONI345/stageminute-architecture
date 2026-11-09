package app.stageminute.progress.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MilestonesTest {

    private static final List<Integer> LIST = List.of(3, 7, 14, 30, 50, 100);

    @Test
    void nothingIsPendingBelowTheFirstMilestone() {
        assertThat(Milestones.pending(2, 2, LIST, Set.of())).isNull();
        assertThat(Milestones.pending(0, 5, LIST, Set.of())).isNull();
    }

    @Test
    void theLargestUnseenMilestoneReachedIsPending() {
        assertThat(Milestones.pending(3, 3, LIST, Set.of())).isEqualTo(3);
        assertThat(Milestones.pending(8, 8, LIST, Set.of())).isEqualTo(7);
        assertThat(Milestones.pending(8, 8, LIST, Set.of(3))).isEqualTo(7);
        assertThat(Milestones.pending(8, 8, LIST, Set.of(3, 7))).isNull();
    }

    @Test
    void aStreakBelowTheBestIsNotCelebratedAgain() {
        assertThat(Milestones.pending(3, 10, LIST, Set.of())).isNull();
    }

    @Test
    void markersRoundTripAndOtherWordsAreNotMilestones() {
        assertThat(Milestones.marker(7)).isEqualTo("milestone_7");
        assertThat(Milestones.daysOf("milestone_7")).isEqualTo(7);
        assertThat(Milestones.daysOf("milestone_x")).isNull();
        assertThat(Milestones.daysOf("first_ad_notice")).isNull();
    }

    @Test
    void markingSeenCoversEveryMilestoneUpToThatDay() {
        assertThat(Milestones.markersUpTo(14, LIST))
                .containsExactlyInAnyOrder("milestone_3", "milestone_7", "milestone_14");
    }
}
