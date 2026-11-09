package app.stageminute.topics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import app.stageminute.topics.internal.TopicFileLoader.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-014 and F-109: a bad topic file stops the server from starting. The rules are the ones of the Node check
 * ({@code content/tools/lib/topics.mjs}, tests in {@code tests/topics}), and the schema is the same file, read from
 * {@code content/schema}. Topics here are written by hand.
 */
class TopicFileLoaderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path SCHEMA = Path.of("..", "content", "schema", "topic.schema.json");

    private static Map<String, Object> prep(int n) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", String.format("prep-sta-%03d", n));
        t.put("style", "prep");
        t.put("level", "starter");
        t.put("difficulty", 1);
        t.put("text", "Tell us about a small thing that made you smile, number " + n + ".");
        t.put("think_s", 30);
        t.put("speak_s", 60);
        return t;
    }

    private static Map<String, Object> star(int n) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", String.format("star-sta-%03d", n));
        t.put("style", "star");
        t.put("level", "starter");
        t.put("difficulty", 1);
        t.put("text", "Tell me about a time you helped someone, number " + n + ".");
        t.put("role_context", "Your team lead asks:");
        t.put("think_s", 30);
        t.put("speak_s", 90);
        return t;
    }

    /** One seed per style at the lowest level, two topics each. */
    private static final class Bank {
        final List<Map<String, Object>> prepFile = new ArrayList<>();
        final List<Map<String, Object>> starFile = new ArrayList<>();
        final List<String> ids = new ArrayList<>();

        Bank() {
            prepFile.add(prep(1));
            prepFile.add(prep(2));
            starFile.add(star(1));
            starFile.add(star(2));
            prepFile.get(0).put("seed", true);
            starFile.get(0).put("seed", true);
            for (Map<String, Object> t : all()) {
                ids.add((String) t.get("id"));
            }
        }

        List<Map<String, Object>> all() {
            List<Map<String, Object>> all = new ArrayList<>(prepFile);
            all.addAll(starFile);
            return all;
        }

        Bank change(Consumer<Bank> change) {
            change.accept(this);
            return this;
        }

        List<SourceFile> files() {
            return List.of(
                    new SourceFile("prep-starter.json", JSON.writeValueAsString(prepFile)),
                    new SourceFile("star-starter.json", JSON.writeValueAsString(starFile)));
        }

        String idsJson() {
            return JSON.writeValueAsString(ids);
        }
    }

    private static String schemaJson() throws IOException {
        return Files.readString(SCHEMA, StandardCharsets.UTF_8);
    }

    private static TopicCatalog load(Bank bank, boolean enforceMin) throws IOException {
        return TopicFileLoader.load(
                bank.files(), bank.idsJson(), schemaJson(), TopicSettings.LAUNCH_DEFAULT, enforceMin);
    }

    private static List<String> problems(Bank bank) throws IOException {
        return problems(bank, false);
    }

    private static List<String> problems(Bank bank, boolean enforceMin) throws IOException {
        try {
            load(bank, enforceMin);
            return List.of();
        } catch (TopicFilesInvalidException e) {
            return e.problems();
        }
    }

    private static void assertOneProblem(List<String> problems, String code) {
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).startsWith(code + " ");
    }

    @Test
    void aCleanBankLoadsAndServesItsTopics() throws IOException {
        TopicCatalog catalog = load(new Bank(), false);

        assertThat(catalog.pool(Style.PREP, Level.STARTER))
                .extracting(Topic::id)
                .containsExactly("prep-sta-001", "prep-sta-002");
        Topic star = catalog.get("star-sta-001").orElseThrow();
        assertThat(star.roleContext()).isEqualTo("Your team lead asks:");
        assertThat(star.speakS()).isEqualTo(90);
        assertThat(star.seed()).isTrue();
        assertThat(star.retired()).isFalse();
    }

    @Test
    void aRetiredTopicLoadsAndStaysResolvable() throws IOException {
        Bank bank = new Bank().change(b -> b.prepFile.get(1).put("retired", true));

        TopicCatalog catalog = load(bank, false);

        assertThat(catalog.pool(Style.PREP, Level.STARTER))
                .extracting(Topic::id)
                .containsExactly("prep-sta-001");
        assertThat(catalog.get("prep-sta-002")).get().extracting(Topic::retired).isEqualTo(true);
    }

    @Test
    void textOverTheCapFails() throws IOException {
        assertOneProblem(
                problems(new Bank().change(b -> b.prepFile.get(1).put("text", "x".repeat(65)))), "E-TEXT-TOO-LONG");
        assertThat(problems(new Bank().change(b -> b.prepFile.get(1).put("text", "x".repeat(64)))))
                .isEmpty();
    }

    @Test
    void starWithoutRoleContextFails() throws IOException {
        assertOneProblem(
                problems(new Bank().change(b -> b.starFile.get(1).remove("role_context"))),
                "E-STAR-NEEDS-ROLE-CONTEXT");
    }

    @Test
    void aSourcedPromptNeedsItsSource() throws IOException {
        assertOneProblem(problems(new Bank().change(b -> b.prepFile.get(1).put("sourced", true))), "E-SOURCE-MISSING");
        assertThat(problems(new Bank().change(b -> {
                    b.prepFile.get(1).put("sourced", true);
                    b.prepFile.get(1).put("source", "Public domain, example.org");
                })))
                .isEmpty();
    }

    @Test
    void secondsOutOfRangeFail() throws IOException {
        assertOneProblem(problems(new Bank().change(b -> b.prepFile.get(1).put("think_s", 5))), "E-SECONDS-RANGE");
        assertOneProblem(problems(new Bank().change(b -> b.prepFile.get(1).put("speak_s", 90))), "E-SECONDS-RANGE");
        assertOneProblem(problems(new Bank().change(b -> b.starFile.get(1).put("speak_s", 20))), "E-SECONDS-RANGE");
    }

    @Test
    void anyOtherSchemaBreakIsASchemaError() throws IOException {
        assertOneProblem(problems(new Bank().change(b -> b.prepFile.get(1).put("mood", "happy"))), "E-SCHEMA");
        assertOneProblem(problems(new Bank().change(b -> b.prepFile.get(1).put("difficulty", 6))), "E-SCHEMA");
    }

    @Test
    void duplicateIdFailsAlsoAcrossFiles() throws IOException {
        Bank bank = new Bank().change(b -> {
            Map<String, Object> again = prep(2);
            again.put("text", "A different question that is long enough.");
            b.starFile.add(again);
        });

        assertThat(problems(bank)).anySatisfy(p -> assertThat(p).startsWith("E-ID-DUPLICATE "));
    }

    @Test
    void duplicateTextFailsIgnoringCaseSpacesAndTheClosingMark() throws IOException {
        Bank bank = new Bank().change(b -> {
            Map<String, Object> copy = prep(3);
            copy.put("text", "  TELL us about a small thing that made you smile, number 2 ");
            b.prepFile.add(copy);
            b.ids.add("prep-sta-003");
        });

        assertOneProblem(problems(bank), "E-TEXT-DUPLICATE");
    }

    @Test
    void anIdOfTheListThatIsGoneFromTheFilesFails() throws IOException {
        Bank bank = new Bank().change(b -> b.ids.add("prep-sta-099"));

        List<String> problems = problems(bank);

        assertOneProblem(problems, "E-ID-REMOVED");
        assertThat(problems.get(0)).contains("prep-sta-099");
    }

    @Test
    void aTopicThatIsNotOnTheListYetFails() throws IOException {
        assertOneProblem(problems(new Bank().change(b -> b.ids.remove("prep-sta-002"))), "E-ID-UNLISTED");
    }

    @Test
    void theIdMustNameTheStyleAndLevelOfTheTopic() throws IOException {
        Bank bank = new Bank().change(b -> {
            b.prepFile.get(1).put("id", "prep-int-002");
            b.ids.set(b.ids.indexOf("prep-sta-002"), "prep-int-002");
        });

        assertOneProblem(problems(bank), "E-ID-CELL");
    }

    @Test
    void aStyleWithoutALiveSeedAtTheLowestLevelFails() throws IOException {
        assertOneProblem(problems(new Bank().change(b -> b.prepFile.get(0).remove("seed"))), "E-NO-SEED");
        assertOneProblem(problems(new Bank().change(b -> b.starFile.get(0).put("retired", true))), "E-NO-SEED");
    }

    @Test
    void aThinCellIsAllowedUnlessTheMinimumIsEnforced() throws IOException {
        Bank bank = new Bank();

        assertThat(problems(bank, false)).isEmpty();
        List<String> enforced = problems(bank, true);
        assertThat(enforced).isNotEmpty().allSatisfy(p -> assertThat(p).startsWith("E-CELL-MIN "));
        assertThat(enforced)
                .anySatisfy(p -> assertThat(p).contains("prep/starter").contains("20"));
    }

    @Test
    void theMinimumIsASettingNotALiteral() throws IOException {
        Bank bank = new Bank();
        TopicSettings two = new TopicSettings(35, 2);

        // Two live topics in the cells that hold topics: fine. The six empty cells still fail.
        List<String> problems;
        try {
            TopicFileLoader.load(bank.files(), bank.idsJson(), schemaJson(), two, true);
            problems = List.of();
        } catch (TopicFilesInvalidException e) {
            problems = e.problems();
        }
        assertThat(problems).hasSize(6).noneMatch(p -> p.contains("prep/starter") || p.contains("star/starter"));
    }

    @Test
    void aFileThatIsNotJsonFailsStartup() throws IOException {
        List<SourceFile> files = List.of(new SourceFile("prep-starter.json", "{ not json"));

        assertThatThrownBy(() -> TopicFileLoader.load(files, "[]", schemaJson(), TopicSettings.LAUNCH_DEFAULT, false))
                .isInstanceOf(TopicFilesInvalidException.class)
                .hasMessageContaining("E-FILE")
                .hasMessageContaining("prep-starter.json");
    }

    @Test
    void theErrorMessageListsEveryProblemSoOneStartupShowsThemAll() throws IOException {
        Bank bank = new Bank().change(b -> {
            b.prepFile.get(1).put("text", "x".repeat(65));
            b.starFile.get(1).remove("role_context");
        });

        assertThatThrownBy(() -> load(bank, false))
                .isInstanceOf(TopicFilesInvalidException.class)
                .hasMessageContaining("E-TEXT-TOO-LONG")
                .hasMessageContaining("E-STAR-NEEDS-ROLE-CONTEXT");
    }
}
