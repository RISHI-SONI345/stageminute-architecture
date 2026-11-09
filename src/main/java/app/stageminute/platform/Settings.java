package app.stageminute.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a number by its {@link SettingKey}, or a list of codes by its {@link SettingList}. A missing row, a
 * value of the wrong shape, or a store that cannot be read all give the launch default (fail closed). Callers
 * never hold a number of their own.
 */
@Component
public class Settings {

    private static final Logger log = LoggerFactory.getLogger(Settings.class);
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,9}");
    private static final Pattern CODE_LIST =
            Pattern.compile("\\[\\s*(\"[a-z0-9_]{1,40}\"\\s*(,\\s*\"[a-z0-9_]{1,40}\"\\s*)*)?]");

    private final ConfigStore store;

    Settings(ConfigStore store) {
        this.store = store;
    }

    public int get(SettingKey key) {
        Optional<String> stored;
        try {
            stored = store.settingValue(key.key());
        } catch (RuntimeException e) {
            log.warn("Settings store unreadable; using the launch default for {}", key.key());
            return key.launchDefault();
        }
        return stored.map(String::trim)
                .filter(text -> WHOLE_NUMBER.matcher(text).matches())
                .map(Integer::parseInt)
                .orElse(key.launchDefault());
    }

    /** A list of codes, stored as a JSON array of lower-case codes such as {@code ["add_closing"]}. */
    public List<String> list(SettingList key) {
        Optional<String> stored;
        try {
            stored = store.settingValue(key.key());
        } catch (RuntimeException e) {
            log.warn("Settings store unreadable; using the launch default for {}", key.key());
            return key.launchDefault();
        }
        return stored.map(String::trim)
                .filter(text -> CODE_LIST.matcher(text).matches())
                .map(Settings::codes)
                .orElse(key.launchDefault());
    }

    private static List<String> codes(String jsonList) {
        String inside = jsonList.substring(1, jsonList.length() - 1).trim();
        if (inside.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(inside.split(",", -1))
                .map(code -> code.trim().replace("\"", ""))
                .toList();
    }
}
