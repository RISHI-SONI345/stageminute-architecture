package app.stageminute.analysis.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.Deletion;
import app.stageminute.analysis.internal.M4aFiles;
import app.stageminute.analysis.internal.SpeechDataCache;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.vendor.FakeSpeechToText;
import app.stageminute.analysis.vendor.SpeechResult;
import app.stageminute.analysis.vendor.SpeechToText;
import app.stageminute.analysis.vendor.SttRequest;
import app.stageminute.platform.UserId;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EC-133 (05 F-082): the account is deleted while an analysis runs. The run finishes its vendor calls outside any
 * transaction and then tries to save, so the order is: delete first, save second. Expected: no orphan report or facts
 * row, no words kept in memory, no 500, and the session's quota step ends without a trace. The other order (the save is
 * open when the delete arrives) is proved too: the delete waits for the save, then removes it.
 */
@Import(AnalyzeDuringDeletionTest.Doubles.class)
@org.springframework.test.context.TestPropertySource(
        properties = {"stageminute.analysis.fake-vendors=true", "spring.profiles.active=test"})
class AnalyzeDuringDeletionTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";

    /** The fake speech to text; a test can hang one action on the moment the pipeline asks it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean
        @Primary
        TopicCatalog deletionCatalog() {
            Topic topic = new Topic(
                    TOPIC, Style.PREP, Level.ELEMENTARY, 1, "What made your week?", null, 30, 60, true, false);
            return TopicCatalog.of(List.of(topic), TopicSettings.LAUNCH_DEFAULT);
        }

        @Bean
        @Primary
        Hooked hookedSpeechToText() {
            return new Hooked();
        }
    }

    static final class Hooked implements SpeechToText {
        volatile Runnable whileTheVendorIsBusy = () -> {};

        @Override
        public SpeechResult transcribe(byte[] audio, int durationMs, SttRequest request) {
            whileTheVendorIsBusy.run();
            return new FakeSpeechToText(FakeScenario.OK, 0).transcribe(audio, durationMs, request);
        }
    }

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Hooked stt;

    @Autowired
    Deletion deletion;

    @Autowired
    SpeechDataCache speechCache;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void reset() {
        stt.whileTheVendorIsBusy = () -> {};
    }

    private Api api(String clerk) {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
    }

    private record Take(String key, UUID session) {}

    private Take reserve(Api api) {
        String key = "t_" + UUID.randomUUID().toString().replace("-", "");
        Api.Reply reply = api.post("/v1/sessions", "{\"take_key\":\"" + key + "\",\"topic_id\":\"" + TOPIC + "\"}");
        assertThat(reply.status()).isEqualTo(201);
        return new Take(key, UUID.fromString(reply.text("session_id")));
    }

    private Api.Reply analyze(Api api, Take take) {
        String meta = "{\"session_id\":\"" + take.session() + "\",\"take_key\":\"" + take.key()
                + "\",\"duration_ms\":55000,\"hit_cap\":false,\"interrupted\":false,\"quiet_hint\":false}";
        return api.with("Idempotency-Key", take.key()).postAudio("/v1/analyze", M4aFiles.ofLength(55_000), meta);
    }

    private int count(String table, String where, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    /** A signed-in person with consent and one reserved session. */
    private record Setup(String clerk, Api api, UserId user, Take take) {}

    private Setup setUp() {
        String clerk = "user_" + UUID.randomUUID().toString().replace("-", "");
        Api api = api(clerk);
        Api.Reply me = api.get("/v1/me");
        assertThat(api.put(
                                "/v1/me/consent",
                                "{\"ai\":true,\"policy_version\":\"" + me.text("consent.policy_version") + "\"}")
                        .status())
                .isEqualTo(204);
        UserId user = new UserId(jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query(UUID.class)
                .single());
        return new Setup(clerk, api, user, reserve(api));
    }

    @Test
    void anAccountDeletedWhileTheVendorsWorkLeavesNoReportNoWordsAndNoServerError() {
        Setup s = setUp();
        stt.whileTheVendorIsBusy = () -> deletion.delete(s.user()); // the person deletes the account mid-run

        Api.Reply reply = analyze(s.api(), s.take());

        assertThat(reply.status())
                .as("the run ends as 'the account is gone', not as a 500")
                .isEqualTo(401);
        assertThat(reply.text("$.reason")).isEqualTo("account_deleted");
        assertThat(count("reports", "session_id = :id", s.take().session())).isZero();
        assertThat(count("facts_records", "user_id = :id", s.user().value())).isZero();
        assertThat(count("practice_sessions", "id = :id", s.take().session())).isZero();
        assertThat(count("quota_events", "user_id = :id", s.user().value())).isZero();
        assertThat(count("idempotency_entries", "user_id = :id", s.user().value()))
                .as("no stored answer of the deleted person")
                .isZero();
        assertThat(speechCache.get(s.user(), s.take().key()))
                .as("the words were not kept in memory")
                .isEmpty();
    }

    @Test
    void aDeleteThatArrivesWhileTheSaveIsOpenWaitsForItAndThenRemovesTheReport() throws Exception {
        Setup s = setUp();
        UUID reportId = UUID.randomUUID();
        CompletableFuture<Void> saveOpen = new CompletableFuture<>();
        CompletableFuture<Void> mayCommit = new CompletableFuture<>();

        CompletableFuture<Void> saver = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO reports (id, user_id, session_id, attempt_no, style, status, score_total,"
                            + " formula_version, model_version, report, created_at)"
                            + " VALUES (:r, :u, :s, 1, 'prep', 'ok', 80, 'f1', 'm1', '{}'::jsonb, :t)")
                    .param("r", reportId)
                    .param("u", s.user().value())
                    .param("s", s.take().session())
                    .param("t", OffsetDateTime.parse("2026-10-29T10:00:00Z"))
                    .update();
            saveOpen.complete(null);
            mayCommit.join();
        }));
        saveOpen.get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> deleter = CompletableFuture.runAsync(() -> deletion.delete(s.user()));
        try {
            deleter.get(700, TimeUnit.MILLISECONDS);
            throw new AssertionError("the delete should wait for the open save");
        } catch (TimeoutException expected) {
            // still waiting: the save holds a lock on the session
        }
        mayCommit.complete(null);
        saver.get(10, TimeUnit.SECONDS);
        deleter.get(10, TimeUnit.SECONDS);

        assertThat(count("reports", "id = :id", reportId))
                .as("no orphan report")
                .isZero();
        assertThat(count("users", "id = :id", s.user().value())).isZero();
    }

    @Test
    void theWordsKeptForTheRetryLeaveServerMemoryWithTheAccount() {
        Setup s = setUp();
        assertThat(analyze(s.api(), s.take()).status()).isEqualTo(200);
        assertThat(speechCache.get(s.user(), s.take().key()))
                .as("the retry window holds the words after a normal take")
                .isPresent();

        assertThat(s.api().delete("/v1/me").status()).isEqualTo(204);

        assertThat(speechCache.get(s.user(), s.take().key()))
                .as("deleting the account drops the words at once, not when the window ends")
                .isEmpty();
    }
}
