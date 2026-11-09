package app.stageminute.analysis.internal;

import app.stageminute.analysis.SpeechDataView;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Holds {@code speech_data} in memory for the retry window only (03 F-091: "held in memory only for the same 10
 * minutes"), so "Try again" gets the words too. Never written to the database, a file, a log or Sentry; after a
 * restart it is empty and the replayed report goes out without {@code speech_data}. Bounded, so a burst of takes
 * cannot fill the memory.
 */
@Component
public class SpeechDataCache {

    private static final int MAX_ENTRIES = 500;

    private record Key(UserId user, String takeKey) {}

    private record Entry(SpeechDataView speech, Instant expires) {}

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Settings settings;
    private final Clock clock;

    public SpeechDataCache(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public void put(UserId user, String takeKey, SpeechDataView speech) {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expires().isAfter(now));
        if (entries.size() >= MAX_ENTRIES) {
            return; // full: the retry gets the report without the words, as after a restart
        }
        Duration life = Duration.ofMinutes(settings.get(SettingKey.IDEMPOTENCY_TTL_MINUTES));
        entries.put(new Key(user, takeKey), new Entry(speech, now.plus(life)));
    }

    public Optional<SpeechDataView> get(UserId user, String takeKey) {
        Entry entry = entries.get(new Key(user, takeKey));
        if (entry == null || !entry.expires().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.speech());
    }

    /** Drops every entry of this person at once (their account was deleted, 05 F-082). */
    public void forget(UserId user) {
        entries.keySet().removeIf(key -> key.user().equals(user));
    }

    /** Forgets everything (sign-out of the whole server: a restart, or a test). */
    public void clear() {
        entries.clear();
    }
}
