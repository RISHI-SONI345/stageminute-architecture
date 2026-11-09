package app.stageminute.topics;

import org.jspecify.annotations.Nullable;

/**
 * One topic of the catalog (content/topics, F-014). The id is permanent: a topic that is taken away stays in its
 * file with {@code retired} set, so old takes and old phones still resolve it.
 *
 * @param roleContext STAR only: who asks, such as "Your team lead asks:"
 * @param seed the easy topic for the first spin ever
 * @param retired never served and never picked, but {@link TopicCatalog#get} still finds it
 */
public record Topic(
        String id,
        Style style,
        Level level,
        int difficulty,
        String text,
        @Nullable String roleContext,
        int thinkS,
        int speakS,
        boolean seed,
        boolean retired) {}
