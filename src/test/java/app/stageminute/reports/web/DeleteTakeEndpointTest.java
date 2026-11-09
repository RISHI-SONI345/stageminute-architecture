package app.stageminute.reports.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.web.AnalyzeEndpointTest;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code DELETE /v1/analyses/{id}} (#17.1, issue #79; 02b F-046; EC-063, EC-113; AD-147): one delete removes the
 * report, its facts, its disputes and the carried focus that came from it; the session, the day's counters, the quota
 * ledger, and so the streak and the totals built from them (#20 reads {@code practice_sessions}), stay exactly as they
 * were. The answer is 204 every time and for every id, so it tells nobody whether a report exists.
 */
@Import(AnalyzeEndpointTest.Doubles.class)
@TestPropertySource(
        properties = {
            "stageminute.analysis.fake-vendors=true",
            "spring.profiles.active=test",
            "logging.level.root=DEBUG"
        })
class DeleteTakeEndpointTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AnalyzeEndpointTest.Scripted stt;

    @BeforeEach
    void reset() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-29T10:00:00Z")));
        stt.scenario = FakeScenario.OK;
    }

    @Test
    void deletingTodaysTakeKeepsTheSessionTheCountersTheLedgerAndSoTheStreakAndTotals() { // EC-113, F-046
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        UUID user = owner(id);
        String before = practiceState(user);
        String quotaBefore = quota(person);

        Api.Reply deleted = person.api().delete("/v1/analyses/" + id);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(practiceState(user)).isEqualTo(before);
        assertThat(quota(person)).isEqualTo(quotaBefore); // today's session is not given back
    }

    @Test
    void deletingATakeRemovesTheReportItsFactsItsDisputesAndTheFocusFromIt() { // F-046, EC-063
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        assertThat(person.api()
                        .post("/v1/reports/" + id + "/dispute", "{\"area\":\"other\",\"transcript\":\"my words\"}")
                        .status())
                .isEqualTo(202);
        jdbc.sql("INSERT INTO next_focus (user_id, fix_code, from_report_id, style, set_at)"
                        + " SELECT user_id, 'add_closing', id, style, created_at FROM reports WHERE id = :id"
                        + " ON CONFLICT (user_id) DO UPDATE SET from_report_id = EXCLUDED.from_report_id")
                .param("id", id)
                .update(); // the focus this take leaves for the next topic (F-034)
        assertThat(rows("next_focus", "from_report_id", id)).isEqualTo(1);

        assertThat(person.api().delete("/v1/analyses/" + id).status()).isEqualTo(204);

        assertThat(rows("reports", "id", id)).isZero();
        assertThat(rows("facts_records", "report_id", id)).isZero();
        assertThat(rows("score_disputes", "report_id", id)).isZero();
        assertThat(rows("next_focus", "from_report_id", id)).isZero();
        assertThat(person.api().get("/v1/analyses/" + id).status()).isEqualTo(404);
        assertThat((List<?>) person.api().get("/v1/analyses").value("items")).isEmpty();
    }

    @Test
    void theTryAgainCopyOfTheReportGoesWithIt() { // F-046: "removes the server report", F-091
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        assertThat(answersNaming(id)).as("the 10-minute Try again copy").isEqualTo(1);

        person.api().delete("/v1/analyses/" + id);

        assertThat(answersNaming(id)).isZero();
    }

    @Test
    void aRepeatAnUnknownIdAndSomeoneElsesIdAllAnswerTheSameAndDeleteNothing() { // no leak
        TestPerson owner = new TestPerson(env, clock);
        UUID id = owner.newReport();
        TestPerson other = new TestPerson(env, clock);

        Api.Reply notYours = other.api().delete("/v1/analyses/" + id);
        Api.Reply unknown = other.api().delete("/v1/analyses/" + UUID.randomUUID());

        assertThat(notYours.status()).isEqualTo(204);
        assertThat(unknown.status()).isEqualTo(204);
        assertThat(notYours.body()).isEqualTo(unknown.body()).isEmpty();
        assertThat(rows("reports", "id", id)).isEqualTo(1);

        assertThat(owner.api().delete("/v1/analyses/" + id).status()).isEqualTo(204);
        assertThat(owner.api().delete("/v1/analyses/" + id).status()).isEqualTo(204);
        assertThat(rows("reports", "id", id)).isZero();
    }

    @Test
    void aReportOlderThanTheSevenDayWindowCanStillBeDeleted() { // D-60: the person can delete any report
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        clock.advance(Duration.ofDays(8));
        assertThat(person.api().get("/v1/analyses/" + id).status()).isEqualTo(404);

        assertThat(person.api().delete("/v1/analyses/" + id).status()).isEqualTo(204);

        assertThat(rows("reports", "id", id)).isZero();
    }

    /** The session row, the day's counters and the quota ledger of a person, as one text. */
    private UUID owner(UUID reportId) {
        return jdbc.sql("SELECT user_id FROM reports WHERE id = :id")
                .param("id", reportId)
                .query(UUID.class)
                .single();
    }

    private String practiceState(UUID user) {
        List<Map<String, Object>> sessions = jdbc.sql("SELECT * FROM practice_sessions WHERE user_id = :u ORDER BY id")
                .param("u", user)
                .query()
                .listOfRows();
        List<Map<String, Object>> counters = jdbc.sql(
                        "SELECT * FROM daily_counters WHERE user_id = :u ORDER BY local_date")
                .param("u", user)
                .query()
                .listOfRows();
        List<Map<String, Object>> ledger = jdbc.sql("SELECT * FROM quota_events WHERE user_id = :u ORDER BY id")
                .param("u", user)
                .query()
                .listOfRows();
        assertThat(sessions).isNotEmpty();
        assertThat(ledger).isNotEmpty();
        return sessions + "\n" + counters + "\n" + ledger;
    }

    private static String quota(TestPerson person) {
        Api.Reply me = person.api().get("/v1/me");
        assertThat(me.status()).isEqualTo(200);
        return String.valueOf(me.value("today"));
    }

    private int rows(String table, String column, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private int answersNaming(UUID id) {
        return jdbc.sql("SELECT count(*) FROM idempotency_entries WHERE response::text LIKE :pattern")
                .param("pattern", "%" + id + "%")
                .query(Integer.class)
                .single();
    }
}
