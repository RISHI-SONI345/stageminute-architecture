package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Settings and flags fail closed (root CLAUDE.md "Numbers", "Money rules"; 06 section 1 launch defaults). */
class SettingsAndFlagsTest {

    /** A store held in memory, so these tests need no database. */
    static class FakeStore implements ConfigStore {
        final Map<String, String> settings = new HashMap<>();
        final Map<String, Boolean> flags = new HashMap<>();
        boolean broken;

        @Override
        public Optional<String> settingValue(String key) {
            if (broken) {
                throw new IllegalStateException("database is down");
            }
            return Optional.ofNullable(settings.get(key));
        }

        @Override
        public Optional<Boolean> flagEnabled(String key) {
            if (broken) {
                throw new IllegalStateException("database is down");
            }
            return Optional.ofNullable(flags.get(key));
        }
    }

    private final FakeStore store = new FakeStore();
    private final Settings settings = new Settings(store);
    private final Flags flags = new Flags(store);

    @Test
    void theCarriedFixListAndAgeReadTheirLaunchDefaultsFromF034() {
        assertThat(settings.list(SettingList.CARRY_FIX_PORTABLE_CODES))
                .containsExactly(
                        "add_closing",
                        "say_result",
                        "open_point",
                        "set_scene",
                        "filler_cluster",
                        "no_freeze",
                        "steady_pace");
        assertThat(settings.get(SettingKey.CARRY_FIX_MAX_AGE_DAYS)).isEqualTo(7);
    }

    @Test
    void aListSettingIsReadByNameAndABadValueGivesTheDefault() {
        store.settings.put("carry_fix_portable_codes", "[\"add_closing\", \"no_freeze\"]");
        assertThat(settings.list(SettingList.CARRY_FIX_PORTABLE_CODES)).containsExactly("add_closing", "no_freeze");

        store.settings.put("carry_fix_portable_codes", "[]");
        assertThat(settings.list(SettingList.CARRY_FIX_PORTABLE_CODES)).isEmpty(); // carry nothing

        for (String bad : new String[] {"\"add_closing\"", "[add_closing]", "[\"Add Closing\"]", "7", "[\"a\",]"}) {
            store.settings.put("carry_fix_portable_codes", bad);
            assertThat(settings.list(SettingList.CARRY_FIX_PORTABLE_CODES))
                    .as(bad)
                    .hasSize(7);
        }
        store.broken = true;
        assertThat(settings.list(SettingList.CARRY_FIX_PORTABLE_CODES)).hasSize(7);
    }

    @Test
    void aMissingSettingReadsItsLaunchDefaultFrom06Section1() {
        assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY)).isEqualTo(1);
        assertThat(settings.get(SettingKey.REWARD_GRANTS_PER_DAY)).isEqualTo(2);
        assertThat(settings.get(SettingKey.PRO_SESSIONS_PER_DAY)).isEqualTo(15);
        assertThat(settings.get(SettingKey.FREE_ANALYSES_PER_DAY)).isEqualTo(30);
        assertThat(settings.get(SettingKey.PRO_ANALYSES_PER_DAY)).isEqualTo(40);
        assertThat(settings.get(SettingKey.ANALYZE_CALLS_PER_MINUTE)).isEqualTo(6);
        assertThat(settings.get(SettingKey.DISPUTES_PER_DAY)).isEqualTo(5);
        assertThat(settings.get(SettingKey.NO_SCORE_TAKES_FREE_PER_DAY)).isEqualTo(5);
        assertThat(settings.get(SettingKey.UNCONFIRMED_GIVE_BACKS_PER_DAY)).isEqualTo(2);
        assertThat(settings.get(SettingKey.EXIT_ADS_PER_DAY)).isEqualTo(1);
        assertThat(settings.get(SettingKey.NEW_ACCOUNTS_PER_DEVICE_30_DAYS)).isEqualTo(3);
        assertThat(settings.get(SettingKey.NEW_ACCOUNTS_PER_IP_PER_DAY)).isEqualTo(5);
        assertThat(settings.get(SettingKey.UPLOAD_MAX_BYTES)).isEqualTo(8 * 1024 * 1024);
        assertThat(settings.get(SettingKey.SOFT_SHEET_INTERVAL_DAYS)).isEqualTo(3);
        assertThat(settings.get(SettingKey.SOFT_SHEET_PAUSE_DAYS)).isEqualTo(14);
        assertThat(settings.get(SettingKey.SOFT_SHEET_DISMISSALS_BEFORE_PAUSE)).isEqualTo(2);
        assertThat(settings.get(SettingKey.TIMEZONE_CHANGE_INTERVAL_DAYS)).isEqualTo(7);
        assertThat(settings.get(SettingKey.IDEMPOTENCY_TTL_MINUTES)).isEqualTo(10);
        assertThat(settings.get(SettingKey.GEMINI_THINKING_BUDGET_TOKENS)).isEqualTo(512);
    }

    @Test
    void theScoreWeightsDefaultTo45And35And20() {
        assertThat(settings.get(SettingKey.SCORE_WEIGHT_STRUCTURE)).isEqualTo(45);
        assertThat(settings.get(SettingKey.SCORE_WEIGHT_LANGUAGE)).isEqualTo(35);
        assertThat(settings.get(SettingKey.SCORE_WEIGHT_DELIVERY)).isEqualTo(20);
    }

    @Test
    void aStoredSettingWinsOverTheDefault() {
        store.settings.put("free_sessions_per_day", "3");

        assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY)).isEqualTo(3);
    }

    @Test
    void aSettingThatIsNotAWholeNonNegativeNumberFallsBackToTheDefault() {
        for (String bad : new String[] {"\"3\"", "-1", "2.5", "null", "true", "[1]", "", "99999999999"}) {
            store.settings.put("free_sessions_per_day", bad);

            assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY))
                    .as("stored value %s", bad)
                    .isEqualTo(1);
        }
    }

    @Test
    void whenTheStoreCannotBeReadSettingsReadTheirDefaults() {
        store.settings.put("free_sessions_per_day", "9");
        store.broken = true;

        assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY)).isEqualTo(1);
    }

    @Test
    void everyFlagIsOffWhenMissing() {
        for (FlagKey key : FlagKey.values()) {
            assertThat(flags.isOn(key)).as(key.name()).isFalse();
        }
    }

    @Test
    void theFlagsThatMatterForMoneyAndPrivacyExistUnderTheirRowKeys() {
        assertThat(java.util.Arrays.stream(FlagKey.values()).map(FlagKey::key))
                .contains("exit_ad_enabled", "soft_sheet_enabled", "carry_fix_enabled", "privacy_strong_copy");
    }

    @Test
    void aFlagIsOnOnlyWhenItsRowSaysEnabled() {
        store.flags.put("privacy_strong_copy", true);
        store.flags.put("exit_ad_enabled", false);

        assertThat(flags.isOn(FlagKey.PRIVACY_STRONG_COPY)).isTrue();
        assertThat(flags.isOn(FlagKey.EXIT_AD_ENABLED)).isFalse();
        assertThat(flags.isOn(FlagKey.SOFT_SHEET_ENABLED)).isFalse();
    }

    @Test
    void whenTheStoreCannotBeReadEveryFlagIsOff() {
        store.flags.put("privacy_strong_copy", true);
        store.broken = true;

        assertThat(flags.isOn(FlagKey.PRIVACY_STRONG_COPY)).isFalse();
    }

    @Test
    void settingKeysAreUniqueAndNamedInLowerSnakeCase() {
        assertThat(java.util.Arrays.stream(SettingKey.values()).map(SettingKey::key))
                .doesNotHaveDuplicates()
                .allMatch(k -> k.matches("[a-z][a-z0-9_]*"));
    }
}
