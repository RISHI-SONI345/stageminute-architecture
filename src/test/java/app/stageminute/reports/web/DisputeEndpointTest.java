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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code POST /v1/reports/{analysisId}/dispute} (#17.1, issue #79; 02b F-042, C-67; D-18, D-84; AD-147): a dispute
 * is kept, counts toward 5 a day on the server's account day, never changes the score or the report, keeps words
 * only when sent (and nowhere else), and answers the same 404 as {@code GET /v1/analyses/{id}} for a report that is
 * not the person's to see. Same test app (and DEBUG logging) as {@code AnalysesEndpointTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(AnalyzeEndpointTest.Doubles.class)
@TestPropertySource(
        properties = {
            "stageminute.analysis.fake-vendors=true",
            "spring.profiles.active=test",
            "logging.level.root=DEBUG"
        })
class DisputeEndpointTest extends DatabaseTest {

    /** Words that exist only in what the person sent: they must never show up outside {@code score_disputes}. */
    private static final String NOTE = "Zebracorn note about my closing";

    private static final String TRANSCRIPT = "Quixotic platypus transcript of the whole take";

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
    void aDisputeIsAcceptedAndTheScoreAndTheReportStayExactlyTheSame() { // D-84, F-042
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        Map<String, Object> before = storedReport(id);
        String servedBefore = person.api().get("/v1/analyses/" + id).body();

        Api.Reply full = dispute(person, id, "{\"area\":\"structure_step\",\"index\":3,\"note\":\"" + NOTE + "\"}");
        Api.Reply oneTap = dispute(person, id, "{\"area\":\"fix_first\"}");

        assertThat(full.status()).isEqualTo(202);
        assertThat(full.text("dispute_state")).isEqualTo("sent");
        assertThat(oneTap.status()).isEqualTo(202);
        assertThat(storedReport(id)).isEqualTo(before);
        assertThat(person.api().get("/v1/analyses/" + id).body()).isEqualTo(servedBefore);
    }

    @Test
    void theSixthDisputeOfTheAccountDayIsRateLimitedAndTheNextDayOpensAgain() { // D-18, 06 section 1
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        for (int i = 0; i < 5; i++) {
            assertThat(dispute(person, id, "{\"area\":\"score\"}").status())
                    .as("dispute " + (i + 1))
                    .isEqualTo(202);
        }

        Api.Reply sixth = dispute(person, id, "{\"area\":\"other\",\"note\":\"" + NOTE + "\"}");

        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.text("reason")).isEqualTo("rate_limited");
        int retryAfter = ((Number) sixth.value("retry_after_s")).intValue();
        assertThat(retryAfter).isBetween(1, 25 * 3600);
        assertThat(sixth.body()).doesNotContain("Zebracorn");
        assertThat(disputeRows(id)).isEqualTo(5); // the refused one is not kept
        assertThat(disputesCounted(id)).isEqualTo(5);

        clock.advance(Duration.ofSeconds(retryAfter)); // the server's next account day
        assertThat(dispute(person, id, "{\"area\":\"score\"}").status()).isEqualTo(202);
    }

    @Test
    void theOneTapCountsTowardTheSameFiveAndTheSixthIsRateLimited() { // C-67 box 2
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        for (int i = 0; i < 5; i++) {
            assertThat(dispute(person, id, "{\"area\":\"pace\"}").status()).isEqualTo(202);
        }

        Api.Reply oneTap = dispute(person, id, "{\"area\":\"fix_first\"}");

        assertThat(oneTap.status()).isEqualTo(429);
        assertThat(oneTap.text("reason")).isEqualTo("rate_limited");
        assertThat(oneTapRows(id)).isEmpty();
    }

    @Test
    void theTranscriptIsKeptOnlyWhenSentWithAPurgeDateAndTheReportShowsSent() { // D-18, 05 section 5
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        assertThat(person.api().get("/v1/analyses").value("items[0].dispute_state"))
                .isNull();

        dispute(person, id, "{\"area\":\"filler\",\"note\":\"" + NOTE + "\"}");
        clock.advance(Duration.ofMinutes(1));
        dispute(person, id, "{\"area\":\"pause\",\"transcript\":\"" + TRANSCRIPT + "\"}");

        List<Map<String, Object>> rows = jdbc.sql("SELECT area, note, transcript_text,"
                        + " extract(epoch FROM purge_after - created_at)::bigint AS kept_s"
                        + " FROM score_disputes WHERE report_id = :id ORDER BY created_at")
                .param("id", id)
                .query()
                .listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("note")).isEqualTo(NOTE);
        assertThat(rows.get(0).get("transcript_text")).isNull();
        assertThat(rows.get(1).get("note")).isNull();
        assertThat(rows.get(1).get("transcript_text")).isEqualTo(TRANSCRIPT);
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("kept_s")).longValue())
                    .isEqualTo(Duration.ofDays(90).toSeconds());
        }
        assertThat(person.api().get("/v1/analyses").text("items[0].dispute_state"))
                .isEqualTo("sent");
    }

    @Test
    void theOneTapKeepsCodesFromTheStoredReportAndNoWords() { // C-67 boxes 1 and 4
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();
        Map<String, Object> report = jdbc.sql("SELECT report -> 'fix_first' ->> 'id' AS fix, formula_version,"
                        + " report ->> 'prompt_version' AS prompt FROM reports WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();

        Api.Reply tap = dispute(
                person, id, "{\"area\":\"fix_first\",\"fix_code\":\"say_point_first\",\"formula_version\":\"x\"}");
        Api.Reply again = dispute(person, id, "{\"area\":\"fix_first\"}");

        assertThat(tap.status()).isEqualTo(202);
        assertThat(again.status()).isEqualTo(202); // the same tap again: kept once, counted once
        List<Map<String, Object>> rows = oneTapRows(id);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("report_id")).isEqualTo(id);
        assertThat(row.get("fix_code")).isEqualTo(report.get("fix")).isNotNull();
        assertThat(row.get("formula_version")).isEqualTo(report.get("formula_version"));
        assertThat(row.get("prompt_version")).isEqualTo(report.get("prompt"));
        assertThat(row.get("note")).isNull();
        assertThat(row.get("transcript_text")).isNull();
        assertThat(row.get("item_index")).isNull();
        assertThat(disputesCounted(id)).isEqualTo(1);
    }

    @Test
    void aOneTapThatCarriesANoteOrATranscriptIsRefusedAndNothingIsKept() { // C-67 box 1
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();

        Api.Reply withNote = dispute(person, id, "{\"area\":\"fix_first\",\"note\":\"" + NOTE + "\"}");
        Api.Reply withTranscript =
                dispute(person, id, "{\"area\":\"fix_first\",\"transcript\":\"" + TRANSCRIPT + "\"}");
        Api.Reply withEmptyNote = dispute(person, id, "{\"area\":\"fix_first\",\"note\":\"\"}");

        assertThat(List.of(withNote, withTranscript, withEmptyNote)).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.text("reason")).isEqualTo("validation_failed");
            assertThat(reply.body()).doesNotContain("Zebracorn").doesNotContain("Quixotic");
        });
        assertThat(disputeRows(id)).isZero();
    }

    @Test
    void aBodyThatBreaksARuleIsRefusedWithTheFieldAndCountsNothing() { // F-042
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();

        assertThat(field(dispute(person, id, "{\"area\":\"other\",\"note\":\"" + "a".repeat(301) + "\"}")))
                .isEqualTo("note");
        assertThat(dispute(person, id, "{\"area\":\"other\",\"note\":\"" + "a".repeat(300) + "\"}")
                        .status())
                .isEqualTo(202);
        assertThat(field(dispute(person, id, "{\"area\":\"structure_step\"}"))).isEqualTo("index");
        assertThat(field(dispute(person, id, "{\"area\":\"structure_step\",\"index\":4}")))
                .isEqualTo("index");
        assertThat(field(dispute(person, id, "{\"area\":\"language_suggestion\",\"index\":999}")))
                .isEqualTo("index");
        assertThat(field(dispute(person, id, "{\"area\":\"score\",\"index\":0}")))
                .isEqualTo("index");
        assertThat(field(dispute(person, id, "{}"))).isEqualTo("area");
        Api.Reply unknownArea = dispute(person, id, "{\"area\":\"accent\"}");
        assertThat(unknownArea.status()).isEqualTo(400);
        assertThat(unknownArea.text("reason")).isEqualTo("validation_failed");
        assertThat(disputesCounted(id)).isEqualTo(1);
    }

    @Test
    void someoneElsesAnUnknownADeletedOrAnOldReportIsNotFoundLikeTheGet() { // EC-062, AD-130
        TestPerson owner = new TestPerson(env, clock);
        UUID id = owner.newReport();
        TestPerson other = new TestPerson(env, clock);

        Api.Reply notYours = dispute(other, id, "{\"area\":\"score\"}");
        Api.Reply unknown = dispute(owner, UUID.randomUUID(), "{\"area\":\"score\"}");

        assertThat(List.of(notYours, unknown)).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.text("reason")).isEqualTo("not_found");
        });
        assertThat(notYours.body().replaceAll("\"(request_id|instance)\":\"[^\"]*\"", ""))
                .isEqualTo(unknown.body().replaceAll("\"(request_id|instance)\":\"[^\"]*\"", ""));
        assertThat(disputeRows(id)).isZero();

        assertThat(owner.api().delete("/v1/analyses/" + id).status()).isEqualTo(204);
        assertThat(dispute(owner, id, "{\"area\":\"score\"}").status()).isEqualTo(404);

        UUID old = owner.newReportNextDay();
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        assertThat(owner.api().get("/v1/analyses/" + old).status()).isEqualTo(404);
        assertThat(dispute(owner, old, "{\"area\":\"score\"}").status()).isEqualTo(404);
    }

    @Test
    void theNoteAndTheTranscriptReachNoLogLineAndNoOtherTable(CapturedOutput output) { // rule 4, 05 F-081
        TestPerson person = new TestPerson(env, clock);
        UUID id = person.newReport();

        dispute(person, id, "{\"area\":\"other\",\"note\":\"" + NOTE + "\",\"transcript\":\"" + TRANSCRIPT + "\"}");
        dispute(person, id, "{\"area\":\"other\",\"note\":\"" + NOTE + "a".repeat(300) + "\"}"); // refused: too long
        dispute(person, UUID.randomUUID(), "{\"area\":\"other\",\"transcript\":\"" + TRANSCRIPT + "\"}"); // 404

        assertThat(output.getAll()).doesNotContain("Zebracorn").doesNotContain("Quixotic");
        String everywhereElse = jdbc.sql("""
                        SELECT coalesce(string_agg(t, ' '), '') FROM (
                          SELECT report::text AS t FROM reports
                          UNION ALL SELECT row_to_json(f)::text FROM facts_records f
                          UNION ALL SELECT coalesce(response::text, '') FROM idempotency_entries
                          UNION ALL SELECT row_to_json(e)::text FROM event_publication e) s
                        """).query(String.class).single();
        assertThat(everywhereElse).doesNotContain("Zebracorn").doesNotContain("Quixotic");
    }

    private Api.Reply dispute(TestPerson person, UUID id, String body) {
        return person.api().post("/v1/reports/" + id + "/dispute", body);
    }

    private static String field(Api.Reply reply) {
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.text("reason")).isEqualTo("validation_failed");
        return reply.text("fields[0]");
    }

    private int disputeRows(UUID id) {
        return jdbc.sql("SELECT count(*) FROM score_disputes WHERE report_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private List<Map<String, Object>> oneTapRows(UUID id) {
        return jdbc.sql("SELECT * FROM score_disputes WHERE report_id = :id AND area = 'fix_first'")
                .param("id", id)
                .query()
                .listOfRows();
    }

    /** Today's dispute count of the report's owner (every report in these tests is made on the same day). */
    private int disputesCounted(UUID id) {
        return jdbc.sql("SELECT coalesce(sum(c.disputes), 0) FROM daily_counters c"
                        + " JOIN reports r ON r.user_id = c.user_id WHERE r.id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    /** Every stored number of the report and its JSON, except the dispute mark itself. */
    private Map<String, Object> storedReport(UUID id) {
        return jdbc.sql("SELECT score_total, structure_points, language_points, delivery_points, overall_band,"
                        + " formula_version, model_version, status, report::text AS report FROM reports WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }
}
