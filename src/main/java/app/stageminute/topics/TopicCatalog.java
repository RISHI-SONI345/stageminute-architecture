package app.stageminute.topics;

import app.stageminute.topics.internal.TopicPicker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * The read-only topic catalog (AD-15, AD-102). It serves the live topics of one style and level, still finds a
 * retired topic by id, and has a version that changes exactly when what a phone would see changes.
 */
// Not final: Spring wraps module beans in a proxy for observability, and a final class cannot be wrapped.
public class TopicCatalog {

    private static final int VERSION_HEX_LENGTH = 12;

    private final List<Topic> topics;
    private final Map<String, Topic> byId;
    private final TopicSettings settings;
    private final String version;

    TopicCatalog(List<Topic> topics, TopicSettings settings) {
        this.topics = topics.stream().sorted(Comparator.comparing(Topic::id)).toList();
        this.byId = this.topics.stream().collect(Collectors.toUnmodifiableMap(Topic::id, Function.identity()));
        this.settings = settings;
        this.version = versionOf(this.topics);
    }

    /** Builds the catalog from topics that were already checked against the schema. Ids must be unique. */
    public static TopicCatalog of(List<Topic> topics, TopicSettings settings) {
        return new TopicCatalog(topics, settings);
    }

    /** The live topics of this style and level, in id order. Retired topics are never listed. */
    public List<Topic> pool(Style style, Level level) {
        return topics.stream()
                .filter(t -> !t.retired() && t.style() == style && t.level() == level)
                .toList();
    }

    /**
     * The next topic for a user (AD-102, pure). The caller counts the completed sessions and lists the ids to avoid.
     *
     * @param exclude ids to avoid, oldest first; when the whole pool is excluded the one excluded longest ago comes
     *     back, so a pick never fails because the pool is used up
     * @return a topic, or empty when this style and level have no live topic (the spin never invents one)
     */
    public Optional<Topic> pick(
            Style style, Level level, int completedSessions, List<String> exclude, RandomGenerator random) {
        return TopicPicker.pick(pool(style, level), completedSessions, exclude, settings.rampSessions(), random);
    }

    /** A topic by id, retired ones included. */
    public Optional<Topic> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Twelve hex characters; the ETag of {@code GET /topics/pool}. Same content gives the same version. */
    public String version() {
        return version;
    }

    private static String versionOf(List<Topic> sorted) {
        StringBuilder canonical = new StringBuilder();
        for (Topic t : sorted) {
            canonical
                    .append(t.id())
                    .append('|')
                    .append(t.style().wire())
                    .append('|')
                    .append(t.level().wire())
                    .append('|')
                    .append(t.difficulty())
                    .append('|')
                    .append(t.text())
                    .append('|')
                    .append(t.roleContext() == null ? "" : t.roleContext())
                    .append('|')
                    .append(t.thinkS())
                    .append('|')
                    .append(t.speakS())
                    .append('|')
                    .append(t.seed())
                    .append('|')
                    .append(t.retired())
                    .append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, VERSION_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
