package app.stageminute.analysis.spend;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.vendor.UsageSink;
import app.stageminute.analysis.vendor.Vendor;
import app.stageminute.analysis.vendor.VendorUsage;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate;
import app.stageminute.platform.SpendGate.Tier;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The meter as the server wires it (#19.2): the real database counter, the settings read by name with the requirement
 * values, one bean for both the usage seam and the question {@code practice} asks, and the throttle lifting at the
 * next UTC day with no job.
 */
class SpendWiringTest extends DatabaseTest {

    @Autowired
    SpendMeter meter;

    @Autowired
    SpendGate gate;

    @Autowired
    UsageSink sink;

    @Autowired
    Settings settings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM ai_spend_daily").update();
        jdbc.sql("DELETE FROM app_settings WHERE key LIKE 'ai\\_%'").update();
    }

    @Test
    void oneBeanIsTheUsageSinkOfTheVendorClientsAndTheGatePracticeAsks() {
        assertThat(sink).isSameAs(meter);
        assertThat(gate).isSameAs(meter);
    }

    @Test
    void withNoRowsEverySettingIsTheValueOfTheRequirements() {
        SpendLimits limits = SpendConfig.limits(settings);

        assertThat(limits.levels()).isEqualTo(new ThrottlePolicy.Levels(15_000_000L, 70, 100, 150, 10)); // D-58
        assertThat(limits.override()).isEqualTo(ThrottleOverride.AUTO);
        assertThat(limits.usable()).isTrue();
        assertThat(settings.get(SettingKey.PRO_ANALYSES_PER_DAY)).isEqualTo(40); // the normal Pro ceiling
        assertThat(limits.callStopMicros()).isEqualTo(24_000_000L); // under the USD 25 caps of the vendor consoles
    }

    @Test
    void aDayAtTheBudgetPausesFreeUsersInTheDatabaseAndTheNextUtcDayLiftsIt() {
        sink.starting(Vendor.GEMINI, 0);
        sink.used(VendorUsage.tokens(0, 2_000_000)); // USD 15.00 at USD 7.50 per million
        assertThat(gate.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(gate.decide(Tier.PRO)).isEqualTo(SpendDecision.ALLOW);
        assertThat(meter.today()).isEqualTo(new SpendToday(15_000_000L, 1, 15_000_000L));

        clock.advance(Duration.ofHours(15)); // the clock starts at 10:00 UTC, so this is the next UTC date

        assertThat(gate.decide(Tier.FREE)).isEqualTo(SpendDecision.ALLOW);
    }

    @Test
    void theOverrideSettingIsReadByNameAndAnythingUnknownPausesEveryone() {
        setOverride("[\"force_open\"]");
        assertThat(gate.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);

        setOverride("[\"force_closed\"]");
        assertThat(gate.decide(Tier.FREE)).isEqualTo(SpendDecision.ALLOW);

        setOverride("[\"typo\"]");
        assertThat(gate.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(gate.decide(Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));
    }

    @Test
    void aBudgetOfZeroInTheSettingsPausesEveryone() {
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('ai_daily_budget_usd', '0'::jsonb, 1, now(), 'test')")
                .update();

        assertThat(gate.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(gate.decide(Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));
    }

    private void setOverride(String json) {
        jdbc.sql("DELETE FROM app_settings WHERE key = 'ai_throttle_override'").update();
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('ai_throttle_override', :v::jsonb, 1, now(), 'test')")
                .param("v", json)
                .update();
    }
}
