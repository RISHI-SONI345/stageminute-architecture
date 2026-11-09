package app.stageminute.topics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Small builders for topic tests. Topics are written by hand here, never read from content/topics. */
final class Topics {

    private Topics() {}

    /** A live PREP topic. */
    static Topic prep(String id, Level level, int difficulty) {
        return new Topic(id, Style.PREP, level, difficulty, "Question " + id + "?", null, 30, 60, false, false);
    }

    /** A live STAR topic. */
    static Topic star(String id, Level level, int difficulty) {
        return new Topic(
                id,
                Style.STAR,
                level,
                difficulty,
                "Tell me about " + id + ".",
                "Your team lead asks:",
                30,
                90,
                false,
                false);
    }

    static Topic seed(Topic topic) {
        return new Topic(
                topic.id(),
                topic.style(),
                topic.level(),
                topic.difficulty(),
                topic.text(),
                topic.roleContext(),
                topic.thinkS(),
                topic.speakS(),
                true,
                topic.retired());
    }

    static Topic retired(Topic topic) {
        return new Topic(
                topic.id(),
                topic.style(),
                topic.level(),
                topic.difficulty(),
                topic.text(),
                topic.roleContext(),
                topic.thinkS(),
                topic.speakS(),
                topic.seed(),
                true);
    }

    static Topic withText(Topic topic, String text) {
        return new Topic(
                topic.id(),
                topic.style(),
                topic.level(),
                topic.difficulty(),
                text,
                topic.roleContext(),
                topic.thinkS(),
                topic.speakS(),
                topic.seed(),
                topic.retired());
    }

    /** One PREP topic per difficulty 1 to 5 at this level: ids {@code p1} to {@code p5}. */
    static List<Topic> ladder(Level level) {
        List<Topic> topics = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            topics.add(prep("p" + d, level, d));
        }
        return topics;
    }

    static TopicCatalog catalog(List<Topic> topics) {
        return TopicCatalog.of(topics, TopicSettings.LAUNCH_DEFAULT);
    }

    static Set<String> ids(List<Topic> topics) {
        return Set.copyOf(topics.stream().map(Topic::id).toList());
    }
}
