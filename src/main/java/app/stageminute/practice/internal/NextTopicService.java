package app.stageminute.practice.internal;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The next topic for a person (03 F-014, AD-102, AD-113). {@code topics} cannot see sessions, so this is where
 * the count of finished sessions and the topics of the last sessions are read and handed to the pure pick,
 * together with the ids the phone shows lately. It only reads.
 */
@Service
public class NextTopicService {

    private final TopicCatalog topics;
    private final SessionsRepository sessions;
    private final Settings settings;
    private final RandomGenerator random;

    @Autowired
    public NextTopicService(TopicCatalog topics, SessionsRepository sessions, Settings settings) {
        this(topics, sessions, settings, RandomGenerator.getDefault());
    }

    /** For a test that needs to choose the random. */
    public NextTopicService(
            TopicCatalog topics, SessionsRepository sessions, Settings settings, RandomGenerator random) {
        this.topics = topics;
        this.sessions = sessions;
        this.settings = settings;
        this.random = random;
    }

    /**
     * Picks the next topic of one style and level on the difficulty ramp, leaving out recent topics.
     *
     * @param shownOnPhone topics the phone showed lately, oldest first (topics skipped with "Another topic" never
     *     become a session, so only the phone knows them)
     * @return a topic, or empty when this style and level have no live topic
     * @throws BusinessRefusal {@code validation_failed} when the phone sent more ids than {@code topics_exclude_max}
     */
    public Optional<Topic> next(UserId user, Style style, Level level, List<String> shownOnPhone) {
        if (shownOnPhone.size() > settings.get(SettingKey.TOPICS_EXCLUDE_MAX)) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED);
        }
        int completed = sessions.completedCount(user);
        List<String> fromSessions = sessions.recentTopicIds(user, settings.get(SettingKey.TOPICS_RECENT_SESSIONS));
        // Oldest first, as the pick expects: the sessions' topics, then the phone's. A repeat keeps its first place.
        List<String> exclude = Stream.concat(fromSessions.stream(), shownOnPhone.stream())
                .distinct()
                .toList();
        return topics.pick(style, level, completed, exclude, random);
    }
}
