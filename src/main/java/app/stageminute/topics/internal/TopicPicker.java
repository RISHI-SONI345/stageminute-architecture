package app.stageminute.topics.internal;

import app.stageminute.topics.Topic;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The pure pick (AD-102): no database, no clock, no user. The caller passes the pool, the number of completed
 * sessions, the ids to leave out (oldest first) and the random to use.
 */
public final class TopicPicker {

    private TopicPicker() {}

    /**
     * Picks the next topic from one pool.
     *
     * @param livePool the live topics of one style and level
     * @param exclude topic ids to avoid, oldest first; ids that are not in the pool are ignored
     * @return a topic of the pool, or empty when the pool is empty (the spin never invents a topic)
     */
    public static Optional<Topic> pick(
            List<Topic> livePool,
            int completedSessions,
            List<String> exclude,
            int rampSessions,
            RandomGenerator random) {
        if (completedSessions < 0) {
            throw new IllegalArgumentException("completed sessions cannot be negative");
        }
        Set<String> excluded = Set.copyOf(exclude);
        List<Topic> candidates =
                livePool.stream().filter(t -> !excluded.contains(t.id())).toList();
        if (candidates.isEmpty()) {
            return leastRecentlyExcluded(livePool, exclude);
        }
        if (completedSessions == 0) {
            List<Topic> seeds = candidates.stream().filter(Topic::seed).toList();
            if (!seeds.isEmpty()) {
                return Optional.of(oneOf(seeds, random));
            }
        }
        int target = DifficultyRamp.target(completedSessions, rampSessions);
        // Nearest difficulty to the target; when two are equally near, the easier one.
        int best = candidates.stream()
                .map(Topic::difficulty)
                .min(Comparator.<Integer>comparingInt(d -> Math.abs(d - target)).thenComparingInt(d -> d))
                .orElseThrow();
        return Optional.of(
                oneOf(candidates.stream().filter(t -> t.difficulty() == best).toList(), random));
    }

    /** Every topic was shown lately: start over with the one that was excluded longest ago. */
    private static Optional<Topic> leastRecentlyExcluded(List<Topic> livePool, List<String> exclude) {
        for (String id : exclude) {
            for (Topic topic : livePool) {
                if (topic.id().equals(id)) {
                    return Optional.of(topic);
                }
            }
        }
        return Optional.empty();
    }

    private static Topic oneOf(List<Topic> topics, RandomGenerator random) {
        return topics.get(random.nextInt(topics.size()));
    }
}
