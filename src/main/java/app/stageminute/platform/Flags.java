package app.stageminute.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reads a feature flag. Missing row, or a store that cannot be read: off (fail closed). */
@Component
public class Flags {

    private static final Logger log = LoggerFactory.getLogger(Flags.class);

    private final ConfigStore store;

    Flags(ConfigStore store) {
        this.store = store;
    }

    public boolean isOn(FlagKey key) {
        try {
            return store.flagEnabled(key.key()).orElse(false);
        } catch (RuntimeException e) {
            log.warn("Flag store unreadable; treating {} as off", key.key());
            return false;
        }
    }
}
