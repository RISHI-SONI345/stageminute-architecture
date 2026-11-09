package app.stageminute.reports.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.internal.M4aFiles;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.web.AnalyzeEndpointTest;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The carried focus (03 F-034, ticket #16.3, AD-138): the one fix a topic leaves for the next topic, over real HTTP on
 * the fake vendors. One test per rule: written at the end of each session (portable fixes only), shown once on the first
 * take of the next topic and kept on that take's report, ignored after 7 days, cleared by deleting its source report,
 * by a new style and by "Not today", never touching the score, and nothing at all with the flag off or missing.
 *
 * <p>The fake judge's fix is {@code strengthen_step}, which is not portable, so the tests that need a carried fix list
 * it in the setting {@code carry_fix_portable_codes} (which also proves the list is read by name).
 */
@Import(AnalyzeEndpointTest.Doubles.class)
@TestPropertySource(properties = {"stageminute.analysis.fake-vendors=true", "spring.profiles.active=test"})
class CarriedFocusEndpointTest extends DatabaseTest {

    private static final String FIX = "strengthen_step";
    private static final String LABEL = "Strengthen a step.";

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AnalyzeEndpointTest.Scripted stt;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-29T10:00:00Z")));
        stt.scenario = FakeScenario.OK;
        jdbc.sql("DELETE FROM feature_flags").update();
        jdbc.sql("DELETE FROM app_settings").update();
    }

    @Test
    void theFocusOfOneTopicIsShownOnceOnTheFirstTakeOfTheNextAndStaysOnThatReport() {
        carryOn();
        Person person = new Person();
        Take first = person.newTopic();
        JsonNode a = person.analyze(first);

        assertThat(a.path("report").path("next_focus").path("fix_code").asString())
                .isEqualTo(FIX);
        assertThat(a.path("report").path("carried_focus").isNull()).isTrue();
        JsonNode before = person.nextFocus();
        assertThat(before.path("fix_code").asString()).isEqualTo(FIX);
        assertThat(before.path("label").asString()).isEqualTo(LABEL);
        assertThat(before.path("from_session_id").asString())
                .isEqualTo(first.session().toString());

        Take second = person.newTopic();
        JsonNode b = person.analyze(second);

        JsonNode carried = b.path("report").path("carried_focus");
        assertThat(carried.path("fix_code").asString()).isEqualTo(FIX);
        assertThat(carried.path("label").asString()).isEqualTo(LABEL);
        assertThat(carried.path("from_session_id").asString())
                .isEqualTo(first.session().toString());
        JsonNode reopened = json.readTree(person.api()
                .get("/v1/analyses/" + b.path("report").path("analysis_id").asString())
                .body());
        assertThat(reopened.path("carried_focus")).isEqualTo(carried); // a later reopen still draws the strip
        assertThat(person.nextFocus().path("from_session_id").asString())
                .as("the next topic's own focus replaces the one it used")
                .isEqualTo(second.session().toString());

        JsonNode attempt2 = person.analyze(second.next());
        assertThat(attempt2.path("report").path("attempt").asInt()).isEqualTo(2);
        assertThat(attempt2.path("report").path("carried_focus").isNull())
                .as("shown once: only on the first take of the next topic")
                .isTrue();
    }

    @Test
    void aFixThatIsNotPortableWritesNoRow() { // the launch list: add a closing, point first, fillers, pause, pace
        flagOn();
        Person person = new Person();

        JsonNode a = person.analyze(person.newTopic());

        assertThat(a.path("report").path("fix_first").path("id").asString()).isEqualTo(FIX);
        assertThat(a.path("report").path("next_focus").isNull()).isTrue();
        assertThat(person.nextFocus().isNull()).isTrue();
        assertThat(rows(person)).isZero();
    }

    @Test
    void theLastTakeOfASessionDecidesTheRow() {
        carryOn();
        Person person = new Person();
        Take take = person.newTopic();
        person.analyze(take);
        assertThat(rows(person)).isOne();

        setPortable("[]"); // the session's last take now has a fix that is not carried
        person.analyze(take.next());

        assertThat(rows(person))
                .as("the session ended on a fix that is not carried")
                .isZero();
    }

    @Test
    void afterSevenDaysTheFocusIsIgnored() {
        carryOn();
        Person person = new Person();
        person.analyze(person.newTopic());

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        assertThat(person.nextFocus().path("fix_code").asString()).isEqualTo(FIX);

        clock.advance(Duration.ofMinutes(2));
        assertThat(person.nextFocus().isNull()).isTrue();
        JsonNode b = person.analyze(person.newTopic());
        assertThat(b.path("report").path("carried_focus").isNull()).isTrue();
    }

    @Test
    void deletingTheSourceReportClearsIt() { // the foreign key, ON DELETE CASCADE (02b F-046)
        carryOn();
        Person person = new Person();
        JsonNode a = person.analyze(person.newTopic());
        assertThat(rows(person)).isOne();

        jdbc.sql("DELETE FROM reports WHERE id = :id")
                .param(
                        "id",
                        UUID.fromString(a.path("report").path("analysis_id").asString()))
                .update();

        assertThat(rows(person)).isZero();
        assertThat(person.nextFocus().isNull()).isTrue();
    }

    @Test
    void aNewStyleClearsItAndTheSameStyleKeepsIt() { // 02a F-009, EC-055
        carryOn();
        Person person = new Person();
        person.analyze(person.newTopic());

        assertThat(person.api().patch("/v1/me", "{\"style\":\"prep\"}").status())
                .isEqualTo(200);
        assertThat(person.nextFocus().path("fix_code").asString()).isEqualTo(FIX);

        Api.Reply changed = person.api().patch("/v1/me", "{\"style\":\"star\"}");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(json.readTree(changed.body()).path("next_focus").isNull()).isTrue();
        assertThat(rows(person)).isZero();
    }

    @Test
    void aFixAboutOneStepKeepsItsStyleAndADeliveryFixHasNone() { // F-009: only a style-bound fix is cleared
        carryOn();
        Person person = new Person();
        person.analyze(person.newTopic());
        assertThat(rowStyle(person)).as("strengthen_step is about one step").isEqualTo("prep");

        jdbc.sql("UPDATE next_focus SET fix_code = 'no_freeze', style = NULL").update(); // what a pause fix writes
        assertThat(person.api().patch("/v1/me", "{\"style\":\"star\"}").status())
                .isEqualTo(200);

        assertThat(rows(person)).as("a pause fix is not bound to a style").isOne();
        assertThat(person.nextFocus().path("fix_code").asString()).isEqualTo("no_freeze");
    }

    @Test
    void notTodayClearsItAndIsSafeToRepeat() {
        carryOn();
        Person person = new Person();
        Take take = person.newTopic();
        person.analyze(take);

        Api.Reply other = person.dismiss(UUID.randomUUID().toString());
        assertThat(other.status()).isEqualTo(204);
        assertThat(rows(person))
                .as("Not today for another topic leaves this one")
                .isOne();

        Api.Reply first = person.dismiss(take.session().toString());
        Api.Reply again = person.dismiss(take.session().toString());

        assertThat(first.status()).isEqualTo(204);
        assertThat(first.body()).isEmpty();
        assertThat(again.status()).isEqualTo(204);
        assertThat(person.nextFocus().isNull()).isTrue();
        Api.Reply bad = person.api().post("/v1/me/next-focus/dismiss", "{}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.text("reason")).isEqualTo("validation_failed");
    }

    @Test
    void notTodayCannotReachAnotherPersonsFocusAndNeedsSignIn() {
        carryOn();
        Person owner = new Person();
        Take take = owner.newTopic();
        owner.analyze(take);
        Person other = new Person();

        assertThat(other.dismiss(take.session().toString()).status()).isEqualTo(204);
        assertThat(owner.nextFocus().path("fix_code").asString())
                .as("someone else's session id clears nothing of the owner")
                .isEqualTo(FIX);
        Api.Reply signedOut = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .post("/v1/me/next-focus/dismiss", "{\"from_session_id\":\"" + take.session() + "\"}");
        assertThat(signedOut.status()).isEqualTo(401);
        assertThat(owner.nextFocus().path("fix_code").asString()).isEqualTo(FIX);
    }

    @Test
    void theScoreIsTheSameWithOrWithoutACarriedFocus() { // F-034: never touches the score
        carryOn();
        Person person = new Person();
        JsonNode a = person.analyze(person.newTopic());
        JsonNode b = person.analyze(person.newTopic());

        assertThat(b.path("report").path("carried_focus").isNull()).isFalse();
        assertThat(b.path("report").path("score")).isEqualTo(a.path("report").path("score"));
        assertThat(b.path("report").path("fix_first"))
                .isEqualTo(a.path("report").path("fix_first"));
    }

    @Test
    void aTakeWithNoScoreLeavesTheFocusInPlace() { // F-037 keeps nothing for it, so it shows nothing either
        carryOn();
        Person person = new Person();
        Take first = person.newTopic();
        person.analyze(first);

        stt.scenario = FakeScenario.NOT_ENGLISH;
        Take second = person.newTopic();
        assertThat(person.analyze(second).path("report").path("status").asString())
                .isEqualTo("not_english");

        assertThat(person.nextFocus().path("from_session_id").asString())
                .isEqualTo(first.session().toString());
        stt.scenario = FakeScenario.OK; // the no-score take gave its session back: the next try is a new one
        assertThat(person.analyze(person.newTopic())
                        .path("report")
                        .path("carried_focus")
                        .path("fix_code")
                        .asString())
                .as("the first scored take of the next topic gets it")
                .isEqualTo(FIX);
    }

    @Test
    void withTheFlagMissingOrOffNothingIsWrittenOrSent() { // fails closed
        setPortable("[\"" + FIX + "\"]");
        Person person = new Person();
        JsonNode a = person.analyze(person.newTopic());

        assertThat(a.path("report").path("next_focus").isNull()).isTrue();
        assertThat(rows(person)).isZero();
        assertThat(person.nextFocus().isNull()).isTrue();

        flagOn();
        person.analyze(person.newTopic());
        assertThat(rows(person)).isOne();
        jdbc.sql("UPDATE feature_flags SET enabled = false WHERE key = 'carry_fix_enabled'")
                .update();

        assertThat(person.nextFocus().isNull()).isTrue();
        JsonNode c = person.analyze(person.newTopic());
        assertThat(c.path("report").path("carried_focus").isNull()).isTrue();
        assertThat(c.path("report").path("next_focus").isNull()).isTrue();
    }

    private void carryOn() {
        flagOn();
        setPortable("[\"" + FIX + "\"]");
    }

    private void flagOn() {
        jdbc.sql("INSERT INTO feature_flags (key, enabled, updated_at, updated_by)"
                        + " VALUES ('carry_fix_enabled', true, :t, 'test')")
                .param("t", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private void setPortable(String jsonList) {
        jdbc.sql("DELETE FROM app_settings WHERE key = 'carry_fix_portable_codes'")
                .update();
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('carry_fix_portable_codes', CAST(:v AS jsonb), 1, :t, 'test')")
                .param("v", jsonList)
                .param("t", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private String rowStyle(Person person) {
        return jdbc.sql("SELECT style::text FROM next_focus WHERE user_id ="
                        + " (SELECT user_id FROM practice_sessions WHERE id = :s)")
                .param("s", person.lastSession)
                .query(String.class)
                .single();
    }

    private int rows(Person person) {
        return jdbc.sql("SELECT count(*) FROM next_focus n JOIN users u ON u.id = n.user_id"
                        + " WHERE u.id = (SELECT user_id FROM practice_sessions WHERE id = :s)")
                .param("s", person.lastSession)
                .query(Integer.class)
                .single();
    }

    private record Take(String key, UUID session) {
        Take next() {
            return new Take(newKey(), session);
        }
    }

    private static String newKey() {
        return "t_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** A signed-in person with AI consent; each new topic starts on the next day, so a free session is there. */
    private final class Person {
        private final String clerkId = "user_" + UUID.randomUUID().toString().replace("-", "");
        private UUID lastSession;

        Person() {
            Api.Reply me = api().get("/v1/me");
            assertThat(me.status()).isEqualTo(200);
            String version = me.text("consent.policy_version");
            assertThat(api().put("/v1/me/consent", "{\"ai\":true,\"policy_version\":\"" + version + "\"}")
                            .status())
                    .isEqualTo(204);
        }

        Api api() {
            return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                    .with("X-Device-Id", "device-" + clerkId)
                    .bearer(FakeClerk.token(clerkId, clock.instant()).sign());
        }

        Take newTopic() {
            String key = newKey();
            Api.Reply reserved = api().post(
                            "/v1/sessions",
                            "{\"take_key\":\"" + key + "\",\"topic_id\":\"" + AnalyzeEndpointTest.TOPIC + "\"}");
            if (reserved.status() == 403) { // the day's free session is used: the next day
                clock.advance(Duration.ofDays(1));
                return newTopic();
            }
            assertThat(reserved.status()).isEqualTo(201);
            lastSession = UUID.fromString(reserved.text("session_id"));
            return new Take(key, lastSession);
        }

        JsonNode analyze(Take take) {
            String meta = "{\"session_id\":\"" + take.session() + "\",\"take_key\":\"" + take.key()
                    + "\",\"duration_ms\":55000}";
            Api.Reply reply =
                    api().with("Idempotency-Key", take.key()).postAudio("/v1/analyze", M4aFiles.ofLength(55_000), meta);
            assertThat(reply.status()).isEqualTo(200);
            return json.readTree(reply.body());
        }

        JsonNode nextFocus() {
            Api.Reply me = api().get("/v1/me");
            assertThat(me.status()).isEqualTo(200);
            return json.readTree(me.body()).path("next_focus");
        }

        Api.Reply dismiss(String fromSession) {
            return api().post("/v1/me/next-focus/dismiss", "{\"from_session_id\":\"" + fromSession + "\"}");
        }
    }
}
