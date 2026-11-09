package app.stageminute.account;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.IdentifierHasher;
import app.stageminute.platform.UserId;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** First authenticated call creates the account row, once, and the new-account limits apply then (F-010, F-086). */
class AccountServiceTest extends DatabaseTest {

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    IdentifierHasher hasher;

    private static String clerkId() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static NewAccountContext from(String device, String ip) {
        return new NewAccountContext(device, "android", ip);
    }

    private int count(String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.param((String) params[i], params[i + 1]);
        }
        return spec.query(Integer.class).single();
    }

    private int signals(UserId user, String kind) {
        return count(
                "SELECT count(*) FROM abuse_signals WHERE user_id = :u AND kind = :k", "u", user.value(), "k", kind);
    }

    private OffsetDateTime lastSignIn(String clerk) {
        return jdbc.sql("SELECT last_sign_in_at FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query(OffsetDateTime.class)
                .single();
    }

    @Test
    void theFirstCallCreatesTheRowWithSafeDefaults() {
        String clerk = clerkId();

        UserId id = accounts.resolve(clerk, from("device-" + UUID.randomUUID(), "10.1.0.1"));

        assertThat(id.value().version()).isEqualTo(7);
        var row = jdbc.sql(
                        "SELECT level, style, timezone, ai_consent, onboarded_at FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("level", "starter")
                .containsEntry("style", "prep")
                .containsEntry("timezone", "UTC")
                .containsEntry("ai_consent", false)
                .containsEntry("onboarded_at", null);
        assertThat(lastSignIn(clerk).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void theSecondCallFindsTheSameUserAndMakesNoNewRow() {
        String clerk = clerkId();
        NewAccountContext ctx = from("device-" + UUID.randomUUID(), "10.1.0.2");

        UserId first = accounts.resolve(clerk, ctx);
        UserId second = accounts.resolve(clerk, ctx);

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM users WHERE clerk_user_id = :c", "c", clerk))
                .isEqualTo(1);
        assertThat(accounts.find(clerk)).contains(first);
        assertThat(accounts.find(clerkId())).isEmpty();
    }

    @Test
    void parallelFirstCallsCreateExactlyOneRowAndCountTheDeviceOnce() throws Exception {
        String clerk = clerkId();
        String device = "device-" + UUID.randomUUID();
        int callers = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<UserId>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<UserId> call = () -> {
                    go.await();
                    return accounts.resolve(clerk, from(device, "10.1.0.3"));
                };
                results.add(pool.submit(call));
            }
            go.countDown();

            List<UserId> ids = new ArrayList<>();
            for (Future<UserId> result : results) {
                ids.add(result.get());
            }
            assertThat(ids).doesNotContainNull().containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM users WHERE clerk_user_id = :c", "c", clerk))
                .isEqualTo(1);
        assertThat(count("SELECT accounts_created FROM device_flags WHERE device_hash = :h", "h", hasher.hash(device)))
                .isEqualTo(1);
    }

    @Test
    void theFourthAccountOnOneDeviceStillSignsInButLeavesASignal() {
        String device = "device-" + UUID.randomUUID();
        List<UserId> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            users.add(accounts.resolve(clerkId(), from(device, "10.2.0." + i)));
        }

        assertThat(signals(users.get(2), "velocity_device")).isZero();
        assertThat(signals(users.get(3), "velocity_device")).isEqualTo(1);
    }

    @Test
    void theDeviceCountStartsAgainAfterThirtyDays() {
        String device = "device-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            accounts.resolve(clerkId(), from(device, "10.3.0." + i));
        }
        clock.advance(Duration.ofDays(31));

        UserId fresh = accounts.resolve(clerkId(), from(device, "10.3.1.1"));

        assertThat(signals(fresh, "velocity_device")).isZero();
        assertThat(count("SELECT accounts_created FROM device_flags WHERE device_hash = :h", "h", hasher.hash(device)))
                .isEqualTo(1);
    }

    @Test
    void theSixthAccountFromOneAddressInADayLeavesASignal() {
        String ip = "203.0.113." + (int) (Math.random() * 200 + 20);
        List<UserId> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            users.add(accounts.resolve(clerkId(), from("device-" + UUID.randomUUID(), ip)));
        }

        assertThat(signals(users.get(4), "velocity_ip")).isZero();
        assertThat(signals(users.get(5), "velocity_ip")).isEqualTo(1);
    }

    @Test
    void aMissingDeviceIdIsASignalNeverARefusal() {
        UserId user = accounts.resolve(clerkId(), new NewAccountContext(null, null, "10.4.0.1"));

        assertThat(user).isNotNull();
        assertThat(signals(user, "device_missing")).isEqualTo(1);
    }

    @Test
    void onlyKeyedHashesAreStoredNeverTheDeviceIdOrTheAddress() {
        String device = "device-" + UUID.randomUUID();
        String ip = "198.51.100.77";
        accounts.resolve(clerkId(), from(device, ip));

        byte[] deviceHash = hasher.hash(device);
        assertThat(deviceHash).hasSize(32);
        assertThat(count("SELECT count(*) FROM device_flags WHERE device_hash = :h", "h", deviceHash))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ip_new_accounts WHERE ip_hash = :h", "h", hasher.hash(ip)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM device_flags WHERE platform = :d", "d", device))
                .isZero();
    }

    @Test
    void signInTimeMovesForwardOncePerDayNotOnEveryCall() {
        String clerk = clerkId();
        NewAccountContext ctx = from("device-" + UUID.randomUUID(), "10.5.0.1");
        accounts.resolve(clerk, ctx);
        OffsetDateTime first = lastSignIn(clerk);

        clock.advance(Duration.ofHours(2));
        accounts.resolve(clerk, ctx);
        OffsetDateTime sameDay = lastSignIn(clerk);
        clock.advance(Duration.ofHours(25));
        accounts.resolve(clerk, ctx);
        OffsetDateTime nextDay = lastSignIn(clerk);

        assertThat(sameDay).isEqualTo(first);
        assertThat(nextDay).isAfter(first);
    }

    @Test
    void oldAddressCountsArePurged() {
        accounts.resolve(clerkId(), from("device-" + UUID.randomUUID(), "198.51.100.88"));
        clock.advance(Duration.ofDays(3));

        int removed = accounts.purgeOldAddressCounts();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM ip_new_accounts WHERE day < :limit",
                        "limit",
                        java.time.LocalDate.now(clock).minusDays(2)))
                .isZero();
    }
}
