package app.stageminute.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.NextTopicService;
import app.stageminute.practice.internal.SessionsRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code GET /topics/next} (ticket #60, 03 F-014, AD-102): the rules on a small hand-made catalog with real
 * sessions in Postgres, then the HTTP edge on the real catalog. A pick only reads, so no quota number moves.
 */
@Import(FakePlans.class)
class NextTopicTest extends DatabaseTest {

    // A small catalog of one style and level: one seed at difficulty 1, two topics at 2, one each at 3, 4 and 5.
    private static final String SEED = "prep-sta-001";
    private static final String D2 = "prep-sta-002";
    private static final String D2B = "prep-sta-003";
    private static final String D3 = "prep-sta-004";
    private static final String D4 = "prep-sta-005";
    private static final String D5 = "prep-sta-006";
    private static final Instant START = Instant.parse("2026-10-20T10:00:00Z");

    @Autowired
    AccountService accounts;

    @Autowired
    SessionsRepository sessionsRepository;

    @Autowired
    Settings settings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    TopicCatalog realCatalog;

    private NextTopicService service;
    private int sessionNumber;

    // --- helpers ---

    private static Topic topic(String id, int difficulty, boolean seed) {
        return new Topic(id, Style.PREP, Level.STARTER, difficulty, "Topic " + id + "?", null, 30, 60, seed, false);
    }

    private NextTopicService serviceOnSmallCatalog() {
        TopicCatalog small = TopicCatalog.of(
                List.of(
                        topic(SEED, 1, true),
                        topic(D2, 2, false),
                        topic(D2B, 2, false),
                        topic(D3, 3, false),
                        topic(D4, 4, false),
                        topic(D5, 5, false)),
                TopicSettings.LAUNCH_DEFAULT);
        return new NextTopicService(small, sessionsRepository, settings);
    }

    private UserId newUser() {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
    }

    /** One session row; {@code state} is reserved, committed or released. Later calls are later sessions. */
    private void session(UserId user, String topicId, String state) {
        OffsetDateTime at = OffsetDateTime.ofInstant(START.plusSeconds(60L * sessionNumber++), ZoneOffset.UTC);
        jdbc.sql("INSERT INTO practice_sessions (id, user_id, take_key, topic_id, style, level, local_date, source,"
                        + " state, reserved_at, committed_at, released_at)"
                        + " VALUES (:id, :u, :k, :t, 'prep', 'starter', :d, 'free', :s, :at,"
                        + " CAST(:c AS timestamptz), CAST(:r AS timestamptz))")
                .param("id", UUID.randomUUID())
                .param("u", user.value())
                .param("k", "t_" + UUID.randomUUID())
                .param("t", topicId)
                .param("d", LocalDate.of(2026, 10, 20))
                .param("s", state)
                .param("at", at)
                .param("c", state.equals("committed") ? at : null)
                .param("r", state.equals("released") ? at : null)
                .update();
    }

    private void committedElsewhere(UserId user, int count) {
        for (int i = 0; i < count; i++) {
            session(user, "other-" + UUID.randomUUID(), "committed");
        }
    }

    private Topic next(UserId user, String... shown) {
        return service.next(user, Style.PREP, Level.STARTER, List.of(shown)).orElseThrow();
    }

    private Set<String> manyPicks(UserId user, int times, String... shown) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < times; i++) {
            ids.add(next(user, shown).id());
        }
        return ids;
    }

    private int rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    // --- the rules ---

    @Test
    void theFirstCallEverIsTheSeedTopic() {
        service = serviceOnSmallCatalog();

        assertThat(manyPicks(newUser(), 10)).containsExactly(SEED);
    }

    @Test
    void theTargetDifficultyFollowsTheCompletedSessionCount() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();

        committedElsewhere(user, 8);
        assertThat(next(user).difficulty()).isEqualTo(1);
        committedElsewhere(user, 1); // 9 completed: 2 from the 9th
        assertThat(next(user).difficulty()).isEqualTo(2);
        committedElsewhere(user, 9); // 18
        assertThat(next(user).difficulty()).isEqualTo(3);
        committedElsewhere(user, 9); // 27
        assertThat(next(user).difficulty()).isEqualTo(4);
        committedElsewhere(user, 8); // 35
        assertThat(next(user).difficulty()).isEqualTo(5);
    }

    @Test
    void onlyCommittedSessionsCountAsCompleted() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        committedElsewhere(user, 8);
        for (int i = 0; i < 5; i++) {
            session(user, "other-r" + i, "released");
            session(user, "other-p" + i, "reserved");
        }

        assertThat(next(user).difficulty()).isEqualTo(1); // 8 completed, not 18

        committedElsewhere(user, 1);
        assertThat(next(user).difficulty()).isEqualTo(2);
    }

    @Test
    void anotherPersonsSessionsAreNotCounted() {
        service = serviceOnSmallCatalog();
        UserId busy = newUser();
        committedElsewhere(busy, 12);
        session(busy, SEED, "committed");

        assertThat(manyPicks(newUser(), 10)).containsExactly(SEED);
    }

    @Test
    void neverReturnsATopicOfTheLastSessionsOrAnExcludedId() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        session(user, D2, "committed");
        session(user, D3, "released"); // shown and started, then released: still avoided
        session(user, D4, "reserved");

        // D5 and D2B are left; the phone's list takes D5 out too. SEED is not avoided by anything.
        Set<String> picked = manyPicks(user, 40, D5);

        assertThat(picked).isSubsetOf(SEED, D2B);
        assertThat(picked).isNotEmpty();
    }

    @Test
    void onlyTheLastSessionsAreAvoidedNotTheWholeHistory() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        session(user, D3, "committed"); // the oldest
        for (int i = 0; i < 20; i++) {
            session(user, "other-" + i, "released");
        }

        // The phone avoids all but D3 and D5. D3 is older than the last 20 sessions, so it may return, and it is
        // the nearer to the target difficulty (1 completed session).
        assertThat(manyPicks(user, 10, SEED, D2, D2B, D4)).containsExactly(D3);
    }

    @Test
    void aTopicInsideTheLastSessionsStaysAvoided() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        session(user, D3, "committed");
        for (int i = 0; i < 19; i++) {
            session(user, "other-" + i, "released");
        }

        assertThat(manyPicks(user, 40, SEED, D2, D2B, D4)).containsExactly(D5);
    }

    @Test
    void whenEverythingWasShownTheOneShownLongestAgoComesBack() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        session(user, D3, "committed"); // older
        session(user, D2, "committed"); // newer

        assertThat(next(user, SEED, D2B, D4, D5).id()).isEqualTo(D3);
    }

    @Test
    void anEmptyPoolGivesNoTopic() {
        service = serviceOnSmallCatalog();

        assertThat(service.next(newUser(), Style.STAR, Level.STARTER, List.of()))
                .isEmpty();
    }

    @Test
    void moreThanTwentyExcludedIdsAreRefusedAndTwentyAreFine() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        List<String> twenty = IntStream.range(0, 20).mapToObj(i -> "x" + i).toList();
        List<String> twentyOne = new ArrayList<>(twenty);
        twentyOne.add("x20");

        assertThat(service.next(user, Style.PREP, Level.STARTER, twenty)).isPresent();
        assertThatThrownBy(() -> service.next(user, Style.PREP, Level.STARTER, twentyOne))
                .isInstanceOfSatisfying(
                        BusinessRefusal.class, e -> assertThat(e.reason()).isEqualTo(Reason.VALIDATION_FAILED));
    }

    @Test
    void aPickWritesNothing() {
        service = serviceOnSmallCatalog();
        UserId user = newUser();
        session(user, D2, "committed");
        int sessionRows = rows("practice_sessions");

        manyPicks(user, 5);

        assertThat(rows("practice_sessions")).isEqualTo(sessionRows);
        assertThat(rows("quota_events")).isZero();
        assertThat(rows("daily_counters")).isZero();
    }

    // --- the HTTP edge, on the real catalog ---

    private Api person() {
        String clerk = "user_" + UUID.randomUUID().toString().replace("-", "");
        Api api = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
        assertThat(api.get("/v1/me").status()).isEqualTo(200); // the account exists from the first call
        return api;
    }

    private List<String> poolIds(String style, String level) {
        return realCatalog
                .pool(Style.fromWire(style).orElseThrow(), Level.fromWire(level).orElseThrow())
                .stream()
                .map(Topic::id)
                .toList();
    }

    @Test
    void overHttpTheFirstCallEverReturnsTheSeedTopicWithTheFieldsThePhoneNeeds() {
        Reply reply = person().get("/v1/topics/next?style=prep&level=starter");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.value("$.topic.seed")).isEqualTo(true);
        assertThat(poolIds("prep", "starter")).contains(reply.text("$.topic.id"));
        assertThat(reply.text("$.topic.text")).isNotBlank();
        assertThat(reply.value("$.topic.difficulty")).isNotNull();
        assertThat(reply.value("$.topic.think_s")).isNotNull();
        assertThat(reply.value("$.topic.speak_s")).isNotNull();
        assertThat(reply.body()).contains("\"role_context\"");
    }

    @Test
    void overHttpTheExcludeIdsAreHonoured() {
        Api person = person();
        List<String> pool = poolIds("prep", "intermediate");
        List<String> excluded = pool.subList(0, Math.min(20, pool.size() - 1));

        for (int i = 0; i < 10; i++) {
            Reply reply = person.get("/v1/topics/next?style=prep&level=intermediate&exclude="
                    + excluded.stream().collect(Collectors.joining(",")));

            assertThat(reply.status()).isEqualTo(200);
            assertThat(excluded).doesNotContain(reply.text("$.topic.id"));
        }
    }

    @Test
    void overHttpTwentyExcludeIdsPassAndTwentyOneAreRefused() {
        Api person = person();
        String twenty = IntStream.range(0, 20).mapToObj(i -> "x" + i).collect(Collectors.joining(","));

        assertThat(person.get("/v1/topics/next?style=prep&level=starter&exclude=" + twenty)
                        .status())
                .isEqualTo(200);
        Reply refused = person.get("/v1/topics/next?style=prep&level=starter&exclude=" + twenty + ",x20");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.text("$.reason")).isEqualTo("validation_failed");
    }

    @Test
    void overHttpAnEmptyExcludeIsFine() {
        assertThat(person().get("/v1/topics/next?style=star&level=elementary&exclude=")
                        .status())
                .isEqualTo(200);
    }

    @Test
    void overHttpAMissingOrUnknownStyleOrLevelIsRefused() {
        Api person = person();

        assertThat(person.get("/v1/topics/next?level=starter").status()).isEqualTo(400);
        assertThat(person.get("/v1/topics/next?style=prep").status()).isEqualTo(400);
        assertThat(person.get("/v1/topics/next?style=essay&level=starter").status())
                .isEqualTo(400);
        Reply unknownLevel = person.get("/v1/topics/next?style=prep&level=expert");
        assertThat(unknownLevel.status()).isEqualTo(400);
        assertThat(unknownLevel.text("$.reason")).isEqualTo("validation_failed");
    }

    @Test
    void overHttpWithoutSignInIsRefused() {
        Api anonymous = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")));

        assertThat(anonymous.get("/v1/topics/next?style=prep&level=starter").status())
                .isEqualTo(401);
    }
}
