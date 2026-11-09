package app.stageminute.progress.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.web.AnalyzeEndpointTest;
import app.stageminute.reports.web.TestPerson;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code GET /v1/me/progress}, {@code POST /v1/me/seen/{marker}}, {@code GET /v1/me/patterns} and the {@code
 * can_continue} flag of {@code GET /v1/analyses}, through the real endpoints with a scored take made by the fake
 * vendors (#20.1; 03 F-053, F-055, F-056, F-057; EC-113, EC-114).
 */
@Import(AnalyzeEndpointTest.Doubles.class)
@TestPropertySource(
        properties = {
            "stageminute.analysis.fake-vendors=true",
            "spring.profiles.active=test",
            "logging.level.root=DEBUG"
        })
class ProgressEndpointTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    AnalyzeEndpointTest.Scripted stt;

    @BeforeEach
    void reset() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-29T10:00:00Z")));
        stt.scenario = FakeScenario.OK;
    }

    @Test
    void aNewPersonHasNoStreakAndNoTotals() {
        TestPerson person = new TestPerson(env, clock);

        Api.Reply reply = person.api().get("/v1/me/progress");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text("streak.state")).isEqualTo("new_user");
        assertThat(reply.value("streak.current")).isEqualTo(0);
        assertThat(reply.value("totals.sessions")).isEqualTo(0);
        assertThat(reply.value("totals.speaking_minutes")).isEqualTo(0);
        assertThat(reply.text("today_local_date")).isEqualTo("2026-10-29");
        assertThat((List<?>) reply.value("streak.week_days")).hasSize(7);
    }

    @Test
    void aDeliveredTakeCountsAndDeletingItsReportShrinksNothing() { // EC-113, F-055
        TestPerson person = new TestPerson(env, clock);
        UUID report = person.newReport();
        Api.Reply before = person.api().get("/v1/me/progress");
        assertThat(before.text("streak.state")).isEqualTo("practiced_today");
        assertThat(before.value("streak.current")).isEqualTo(1);
        assertThat(before.value("streak.practiced_today")).isEqualTo(true);
        assertThat(before.value("totals.sessions")).isEqualTo(1);
        assertThat(before.value("totals.speaking_minutes")).isEqualTo(1); // 55 s, rounded to the nearest minute

        assertThat(person.api().delete("/v1/analyses/" + report).status()).isEqualTo(204);

        Api.Reply after = person.api().get("/v1/me/progress");
        assertThat(after.body()).isEqualTo(before.body());
    }

    @Test
    void aMilestoneIsPendingUntilSeenAndMarkingItTwiceIsSafe() { // F-060
        TestPerson person = new TestPerson(env, clock);
        person.newReport();
        person.newReportNextDay();
        assertThat(person.api().get("/v1/me/progress").value("streak.milestone_pending"))
                .isNull();
        person.newReportNextDay();

        Api.Reply reached = person.api().get("/v1/me/progress");
        assertThat(reached.value("streak.current")).isEqualTo(3);
        assertThat(reached.value("streak.milestone_pending")).isEqualTo(3);

        assertThat(person.api().post("/v1/me/seen/milestone_3", "").status()).isEqualTo(204);
        assertThat(person.api().post("/v1/me/seen/milestone_3", "").status()).isEqualTo(204);
        assertThat(person.api().get("/v1/me/progress").value("streak.milestone_pending"))
                .isNull();
    }

    @Test
    void anyOtherWordOrAMilestoneNotReachedIsRefused() {
        TestPerson person = new TestPerson(env, clock);
        person.newReport();

        for (String marker : List.of("milestone_7", "milestone_4", "nonsense")) {
            Api.Reply reply = person.api().post("/v1/me/seen/" + marker, "");
            assertThat(reply.status()).as(marker).isEqualTo(400);
            assertThat(reply.text("reason")).as(marker).isEqualTo("validation_failed");
        }
    }

    @Test
    void theProgressEndpointsNeedASignIn() {
        Api anonymous = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")));

        assertThat(anonymous.get("/v1/me/progress").status()).isEqualTo(401);
        assertThat(anonymous.get("/v1/me/patterns").status()).isEqualTo(401);
        assertThat(anonymous.post("/v1/me/seen/milestone_3", "").status()).isEqualTo(401);
    }

    @Test
    void patternsCountTheFirstTakesOfAStyleAndRefuseAnUnknownStyle() { // F-056
        TestPerson person = new TestPerson(env, clock);
        person.newReport();

        Api.Reply reply = person.api().get("/v1/me/patterns?style=prep");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.value("takes_counted")).isEqualTo(1);
        assertThat(reply.value("needed")).isEqualTo(5);
        assertThat(reply.value("strength")).isNull();
        assertThat(reply.value("all_strong")).isEqualTo(false);
        assertThat(reply.value("fixed_on_second.count")).isEqualTo(0);
        assertThat(person.api().get("/v1/me/patterns?style=star").value("takes_counted"))
                .isEqualTo(0); // the count restarts per style
        assertThat(person.api().get("/v1/me/patterns?style=pep").status()).isEqualTo(400);
        assertThat(person.api().get("/v1/me/patterns").status()).isEqualTo(200); // the current style
    }

    @Test
    void aTakeCanBeContinuedOnlyWhileItsAccountDayIsOpen() { // F-057
        TestPerson person = new TestPerson(env, clock);
        person.newReport();
        Api.Reply today = person.api().get("/v1/analyses");
        assertThat(today.value("items[0].can_continue")).isEqualTo(true);

        clock.advance(Duration.ofDays(1));

        Api.Reply tomorrow = person.api().get("/v1/analyses");
        assertThat(tomorrow.value("items[0].can_continue")).isEqualTo(false);
        assertThat(tomorrow.value("items[0].dispute_state")).isNull();
        assertThat(tomorrow.value("items[0].attempt")).isEqualTo(1);
    }
}
