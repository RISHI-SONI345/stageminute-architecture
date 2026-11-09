package app.stageminute.practice.web;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.UserId;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPortFake;
import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code quota} block of {@code GET /v1/me} over real HTTP (ticket #58, 06-API section 3, 03 F-051): the
 * same four words as {@code POST /v1/sessions}, and no gate, ad or Pro-offer field for {@code pro_cap}.
 */
// Same imports as SessionsEndpointTest on purpose: classes with the same imports share one Spring context, and
// every new context wipes and rebuilds the shared test database, so fewer contexts is faster.
@Import({FakePlans.class, SessionsEndpointTest.Catalog.class})
class MeQuotaAgreementTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    PlanPortFake plans;

    @Autowired
    JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void morning() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-28T10:00:00Z")));
    }

    private final class Person {
        private final String clerkId = "user_" + UUID.randomUUID().toString().replace("-", "");
        private final String device = "device-" + UUID.randomUUID();

        Api api() {
            return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                    .with("X-Device-Id", device)
                    .with("X-Platform", "android")
                    .bearer(FakeClerk.token(clerkId, clock.instant()).sign());
        }
    }

    private Person newPerson() {
        Person person = new Person();
        assertThat(person.api().get("/v1/me").status()).isEqualTo(200);
        return person;
    }

    private UserId idOf(Person person) {
        return new UserId(jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                .param("c", person.clerkId)
                .query(UUID.class)
                .single());
    }

    private Reply start(Person person) {
        String take = "t_" + UUID.randomUUID().toString().replace("-", "");
        return person.api()
                .with("Idempotency-Key", take)
                .post("/v1/sessions", "{\"take_key\":\"" + take + "\",\"topic_id\":\"" + TOPIC + "\"}");
    }

    private Set<String> quotaFields(Reply me) {
        return json.readTree(me.body()).get("quota").propertyNames().stream().collect(Collectors.toSet());
    }

    @Test
    void aNewPersonSeesOkWithOneSessionLeftAndTheResetTime() {
        Person person = newPerson();

        Reply me = person.api().get("/v1/me");

        assertThat(me.text("$.quota.state")).isEqualTo("ok");
        assertThat(me.value("$.quota.sessions_left")).isEqualTo(1);
        assertThat(me.value("$.quota.ad_rewards_today")).isEqualTo(0);
        assertThat(me.value("$.quota.ad_rewards_max")).isEqualTo(2);
        assertThat(me.text("$.quota.resets_at")).isEqualTo("2026-10-29T00:00:00Z");
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void meAndPostSessionsUseTheSameWordsForOkAndQuotaZero() {
        Person person = newPerson();
        assertThat(person.api().get("/v1/me").text("$.quota.state")).isEqualTo("ok");
        Reply first = start(person);
        assertThat(first.text("$.quota_state")).isEqualTo("ok");

        Reply me = person.api().get("/v1/me");
        Reply refused = start(person);

        assertThat(me.text("$.quota.state")).isEqualTo("quota_zero");
        assertThat(refused.text("$.reason")).isEqualTo(me.text("$.quota.state"));
        assertThat(me.value("$.quota.sessions_left")).isEqualTo(0);
        assertThat(me.text("$.quota.resets_at")).isEqualTo(refused.text("$.resets_at"));
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void meAndPostSessionsUseTheSameWordForProCapAndCarryNoAdField() {
        Person person = newPerson();
        plans.setPlan(idOf(person), Plan.PRO);
        for (int i = 0; i < 15; i++) {
            assertThat(start(person).status()).isEqualTo(201);
        }

        Reply me = person.api().get("/v1/me");
        Reply refused = start(person);

        assertThat(me.text("$.quota.state")).isEqualTo("pro_cap");
        assertThat(refused.text("$.reason")).isEqualTo(me.text("$.quota.state"));
        assertThat(quotaFields(me)).containsExactlyInAnyOrder("state", "sessions_left", "resets_at");
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }

    @Test
    void aProPersonWithSessionsLeftHasNoAdFieldsEither() {
        Person person = newPerson();
        plans.setPlan(idOf(person), Plan.PRO);

        Reply me = person.api().get("/v1/me");

        assertThat(me.text("$.quota.state")).isEqualTo("ok");
        assertThat(me.value("$.quota.sessions_left")).isEqualTo(15);
        assertThat(quotaFields(me)).containsExactlyInAnyOrder("state", "sessions_left", "resets_at");
    }

    @Test
    void readingMeDoesNotCountASession() {
        Person person = newPerson();

        person.api().get("/v1/me");
        person.api().get("/v1/me");

        assertThat(start(person).status()).isEqualTo(201); // the one free session is still there
        assertLedgerMatchesCounters(jdbc, idOf(person));
    }
}
