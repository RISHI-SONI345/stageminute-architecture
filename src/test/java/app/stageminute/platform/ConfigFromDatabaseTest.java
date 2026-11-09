package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Settings and flags read from the real tables; empty tables give the safe values. */
class ConfigFromDatabaseTest extends DatabaseTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-19T10:00:00Z");

    @Autowired
    Settings settings;

    @Autowired
    Flags flags;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void emptyTables() {
        jdbc.sql("DELETE FROM app_settings").update();
        jdbc.sql("DELETE FROM feature_flags").update();
    }

    @Test
    void emptyTablesGiveLaunchDefaultsAndFlagsOff() {
        assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY)).isEqualTo(1);
        assertThat(settings.get(SettingKey.PRO_SESSIONS_PER_DAY)).isEqualTo(15);
        for (FlagKey key : FlagKey.values()) {
            assertThat(flags.isOn(key)).as(key.name()).isFalse();
        }
    }

    @Test
    void aSettingRowIsReadByItsName() {
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('free_sessions_per_day', '2'::jsonb, 1, :t, 'test')")
                .param("t", T)
                .update();

        assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY)).isEqualTo(2);
        assertThat(settings.get(SettingKey.PRO_SESSIONS_PER_DAY)).isEqualTo(15);
    }

    @Test
    void aSettingRowThatIsNotANumberIsIgnored() {
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('free_sessions_per_day', '\"lots\"'::jsonb, 1, :t, 'test')")
                .param("t", T)
                .update();

        assertThat(settings.get(SettingKey.FREE_SESSIONS_PER_DAY)).isEqualTo(1);
    }

    @Test
    void aFlagIsOnOnlyWhenItsRowIsEnabled() {
        jdbc.sql("INSERT INTO feature_flags (key, enabled, updated_at, updated_by) VALUES"
                        + " ('privacy_strong_copy', true, :t, 'test'), ('exit_ad_enabled', false, :t, 'test')")
                .param("t", T)
                .update();

        assertThat(flags.isOn(FlagKey.PRIVACY_STRONG_COPY)).isTrue();
        assertThat(flags.isOn(FlagKey.EXIT_AD_ENABLED)).isFalse();
        assertThat(flags.isOn(FlagKey.SOFT_SHEET_ENABLED)).isFalse();
    }

    @Test
    void aNewFlagRowIsOffUntilSomeoneTurnsItOn() {
        jdbc.sql("INSERT INTO feature_flags (key, updated_at, updated_by) VALUES ('soft_sheet_enabled', :t, 'test')")
                .param("t", T)
                .update();

        assertThat(flags.isOn(FlagKey.SOFT_SHEET_ENABLED)).isFalse();
    }
}
