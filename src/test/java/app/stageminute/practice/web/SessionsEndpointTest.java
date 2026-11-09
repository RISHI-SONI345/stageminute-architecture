package app.stageminute.practice.web;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.UserId;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPortFake;
import app.stageminute.practice.internal.SpendGateFake;
import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/sessions} and {@code POST /v1/sessions/{id}/release} over real HTTP (ticket #57, 06-API
 * section 5, 03 F-048 to F-052): idempotent per take, the same quota words as {@code /me}, 403
 * {@code quota_zero}, 429 {@code pro_cap} with no gate, ad or Pro offer, and the topic checked by id.
 */
@Import({FakePlans.class, SessionsEndpointTest.Catalog.class})
class SessionsEndpointTest extends DatabaseTest {

    private static final String LIVE = "prep-ele-001";
    private static final String RETIRED = "prep-ele-900";

    /** A small catalog with one live and one retired topic, so a retired id can be sent. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Catalog {
        @Bean
        @Primary
        TopicCatalog testCatalog() {
            Topic live = new Topic(LIVE, Style.PREP, Level.ELEMENTARY, 1, "Live?", null, 30, 60, true, false);
            Topic retired = new Topic(RETIRED, Style.PREP, Level.ELEMENTARY, 1, "Old?", null, 30, 60, false, true);
            return TopicCatalog.of(List.of(live, retired), TopicSettings.LAUNCH_DEFAULT);
        }
    }

    /** Problem fields any refusal may carry; nothing that opens a gate, an ad or a Pro offer. */
    private static final Set<String> PROBLEM_FIELDS =
            Set.of("type", "title", "status", "detail", "instance", "reason", "resets_at", "resumes_at", "request_id");

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    PlanPortFake plans;

    @Autowired
    SpendGateFake gate;

    @Autowired
    JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void morning() {
        gate.reset();
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-28T10:00:00Z")));
    }

    /** A signed-in person; each call signs a fresh token at the test clock, so moving the clock is safe. */
    private final class Person {
        private final String clerkId = "user_" + UUID.randomUUID().toString().replace("-", "");
        private final String device = "device-" + UUID.randomUUID();

        Api api() {
            return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                    .with("X-Device-Id", device)
                    .with("X-Platform", "android")
                    .bearer(FakeClerk.token(clerkId, clock.instant()).sign());
        }

        String clerkId() {
            return clerkId;
        }
    }

    private Person newPerson() {
        Person person = new Person();
        assertThat(person.api().get("/v1/me").status()).isEqualTo(200); // the account exists from the first call
        return person;
    }

    private UserId idOf(Person person) {
        return new UserId(jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                .param("c", person.clerkId())
                .query(UUID.class)
                .single());
    }

    private static String body(String takeKey, String topicId) {
        return "{\"take_key\":\"" + takeKey + "\",\"topic_id\":\"" + topicId + "\"}";
    }

    private static String newTake() {
        return "t_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Reply start(Person person, String takeKey, String topicId) {
        return person.api().with("Idempotency-Key", takeKey).post("/v1/sessions", body(takeKey, topicId));
    }

    private Reply start(Person person) {
        return start(person, newTake(), LIVE);
    }

    private int ledgerRows(UserId user) {
        return jdbc.sql("SELECT count(*) FROM quota_events WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    private Set<String> fieldsOf(Reply reply) {
        return json.readTree(reply.body()).propertyNames().stream().collect(Collectors.toSet());
    }

    // --- one free session a day ---

    @Test
    void theFirstStartOfTheDayReservesAFreeSession() {
        Person person = newPerson();

        Reply reply = start(person);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.text("$.session_id")).isNotBlank();
        assertThat(reply.value("$.attempt")).isEqualTo(1);
        assertThat(reply.text("$.source")).isEqualTo("free");
        assertThat(reply.text("$.quota_state")).isEqualTo("ok");
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void aSecondStartTheSameDayIsQuotaZeroWithTheResetTime() {
        Person person = newPerson();
        start(person);

        Reply refused = start(person);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.text("$.reason")).isEqualTo("quota_zero");
        assertThat(refused.text("$.resets_at")).isEqualTo("2026-10-29T00:00:00Z"); // midnight in the stored zone (UTC)
        assertThat(PROBLEM_FIELDS).containsAll(fieldsOf(refused));
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void theNextAccountDayStartsAgain() {
        Person person = newPerson();
        start(person);
        clock.advance(Duration.ofDays(1));

        assertThat(start(person).status()).isEqualTo(201);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void anAdCreditPaysForASecondSession() {
        Person person = newPerson();
        start(person);
        plans.setAdCredits(idOf(person), 1);

        Reply second = start(person);

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.text("$.source")).isEqualTo("rewarded");
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    // --- Pro fair-use cap ---

    @Test
    void overTheProCapIs429ProCapWithNoGateAdOrProOffer() {
        Person person = newPerson();
        plans.setPlan(idOf(person), Plan.PRO);
        for (int i = 0; i < 15; i++) {
            assertThat(start(person).status()).isEqualTo(201);
        }

        Reply refused = start(person);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.text("$.reason")).isEqualTo("pro_cap");
        assertThat(refused.text("$.resets_at")).endsWith("Z");
        assertThat(PROBLEM_FIELDS).containsAll(fieldsOf(refused));
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    // --- the AI-cost throttle (03 F-087, #19.2) ---

    @Test
    void aPausedFreePersonGets503ThrottledWithTheTimeThePauseEndsAndNothingIsReserved() {
        Person person = newPerson();
        gate.setFree(SpendDecision.FREE_PAUSED);

        Reply refused = start(person);

        assertThat(refused.status()).isEqualTo(503);
        assertThat(refused.text("$.reason")).isEqualTo("throttled");
        assertThat(refused.text("$.resets_at")).isEqualTo("2026-10-29T00:00:00Z"); // the next UTC midnight
        assertThat(refused.text("$.resumes_at")).isEqualTo("2026-10-29T00:00:00Z"); // the name 14-STATES-IN-WORDS uses
        assertThat(PROBLEM_FIELDS).containsAll(fieldsOf(refused));
        assertThat(ledgerRows(idOf(person))).isZero();
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void aProPersonAtTheLoweredCeilingGets429ProCapNotThrottled() {
        Person person = newPerson();
        plans.setPlan(idOf(person), Plan.PRO);
        gate.setPro(SpendDecision.proReduced(1));
        assertThat(start(person).status()).isEqualTo(201);

        Reply refused = start(person);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.text("$.reason")).isEqualTo("pro_cap");
        assertThat(refused.text("$.resets_at")).endsWith("Z");
    }

    @Test
    void meSaysThrottledWithNoAdFieldsWhileTheFreePausedAndTheTimeItEnds() {
        Person person = newPerson();
        plans.setAdCredits(idOf(person), 1);
        gate.setFree(SpendDecision.FREE_PAUSED);

        Reply me = person.api().get("/v1/me");

        assertThat(me.text("$.quota.state")).isEqualTo("throttled");
        assertThat(me.text("$.quota.resets_at")).isEqualTo("2026-10-29T00:00:00Z");
        assertThat(json.readTree(me.body()).get("quota").propertyNames())
                .doesNotContain("ad_rewards_today", "ad_rewards_max"); // no ad row while throttled (F-051)
    }

    // --- idempotent per take ---

    @Test
    void theSameTakeSentAgainGetsTheSameAnswerAndCountsOnce() {
        Person person = newPerson();
        plans.setAdCredits(idOf(person), 2);
        String take = newTake();

        Reply first = start(person, take, LIVE);
        Reply again = start(person, take, LIVE);

        assertThat(again.status()).isEqualTo(201);
        assertThat(again.text("$.session_id")).isEqualTo(first.text("$.session_id"));
        assertThat(ledgerRows(idOf(person))).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void aRetryAfterTheIdempotencyEntryExpiredReturnsTheSameSessionAndNoSecondLedgerRow() {
        Person person = newPerson();
        plans.setAdCredits(idOf(person), 2); // room for more, so only the unique key can stop a second count
        String take = newTake();
        Reply first = start(person, take, LIVE);

        clock.advance(Duration.ofMinutes(11)); // past idempotency_ttl_minutes (10)
        Reply retry = start(person, take, LIVE);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.text("$.session_id")).isEqualTo(first.text("$.session_id"));
        assertThat(ledgerRows(idOf(person))).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void anIdempotencyKeyThatIsNotTheTakeKeyIsRefused() {
        Person person = newPerson();

        Reply reply = person.api().with("Idempotency-Key", "other").post("/v1/sessions", body(newTake(), LIVE));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.text("$.reason")).isEqualTo("validation_failed");
        assertThat(ledgerRows(idOf(person))).isZero();
    }

    // --- the topic ---

    @Test
    void aRetiredTopicIdIsAccepted() {
        Person person = newPerson();

        assertThat(start(person, newTake(), RETIRED).status()).isEqualTo(201);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void anUnknownTopicIdIsRefused() {
        Person person = newPerson();

        Reply reply = start(person, newTake(), "prep-ele-999");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.text("$.reason")).isEqualTo("validation_failed");
        assertThat(ledgerRows(idOf(person))).isZero();
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    // --- request validation ---

    @Test
    void aMissingOrMalformedTakeKeyIsRefused() {
        Person person = newPerson();

        assertThat(person.api()
                        .post("/v1/sessions", "{\"topic_id\":\"" + LIVE + "\"}")
                        .status())
                .isEqualTo(400);
        assertThat(person.api().post("/v1/sessions", body("has space", LIVE)).status())
                .isEqualTo(400);
        assertThat(person.api()
                        .post("/v1/sessions", body("t_" + "x".repeat(200), LIVE))
                        .status())
                .isEqualTo(400);
        assertThat(person.api()
                        .post("/v1/sessions", "{\"take_key\":\"" + newTake() + "\"}")
                        .status())
                .isEqualTo(400);
        assertThat(ledgerRows(idOf(person))).isZero();
    }

    @Test
    void withoutSignInTheStartIsRefused() {
        Api anonymous = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")));

        assertThat(anonymous.post("/v1/sessions", body(newTake(), LIVE)).status())
                .isEqualTo(401);
    }

    // --- release ---

    @Test
    void aReleaseGivesTheFreeSessionBackForAnotherStart() {
        Person person = newPerson();
        String session = start(person).text("$.session_id");

        Reply released = person.api().post("/v1/sessions/" + session + "/release", "{\"reason\":\"no_local_take\"}");

        assertThat(released.status()).isEqualTo(204);
        assertThat(start(person).status()).isEqualTo(201);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void releasingTwiceIsAnsweredTheSameAndWritesOneLedgerRow() {
        Person person = newPerson();
        String session = start(person).text("$.session_id");

        person.api().post("/v1/sessions/" + session + "/release", "{\"reason\":\"discard\"}");
        Reply again = person.api().post("/v1/sessions/" + session + "/release", "{\"reason\":\"discard\"}");

        assertThat(again.status()).isEqualTo(204);
        assertThat(ledgerRows(idOf(person))).isEqualTo(2);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void someoneElsesSessionIsNotReleased() {
        Person owner = newPerson();
        Person other = newPerson();
        String session = start(owner).text("$.session_id");

        assertThat(other.api()
                        .post("/v1/sessions/" + session + "/release", "{\"reason\":\"cancel\"}")
                        .status())
                .isEqualTo(204);

        assertThat(start(owner).status()).isEqualTo(403); // still counted
        assertLedgerMatchesCounters(jdbc, idOf(owner), idOf(other));
    }

    @Test
    void anUnknownReleaseReasonIsRefused() {
        Person person = newPerson();
        String session = start(person).text("$.session_id");

        assertThat(person.api()
                        .post("/v1/sessions/" + session + "/release", "{\"reason\":\"no_score\"}")
                        .status())
                .isEqualTo(400);
        assertThat(person.api()
                        .post("/v1/sessions/not-a-uuid/release", "{\"reason\":\"cancel\"}")
                        .status())
                .isEqualTo(400);
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }
}
