package app.stageminute.analysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.stageminute.analysis.SpeechDataView;
import app.stageminute.analysis.score.FixId;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.vendor.FakeJudge;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.vendor.FakeSpeechToText;
import app.stageminute.analysis.vendor.Judge;
import app.stageminute.analysis.vendor.JudgeInput;
import app.stageminute.analysis.vendor.JudgeLabels;
import app.stageminute.analysis.vendor.JudgeVersions;
import app.stageminute.analysis.vendor.SttRequest;
import app.stageminute.analysis.vendor.VendorUnavailable;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.reports.FixTexts;
import app.stageminute.reports.FixTextsFixtures;
import app.stageminute.reports.ReportV2;
import app.stageminute.reports.ReportV2.Status;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pipeline on the fake vendors (15.2; 10 section 3 step 2): every report status from a fixture, the options sent
 * to speech to text, and the rule that facts and findings never carry the take's words. Expected numbers are worked
 * by hand from 03 F-030 (see each test), not read back from the code.
 */
class PipelineTest {

    private static final int TAKE_MS = 55_000;
    private static final int CAP_MS = 60_000;
    private static final UUID ANALYSIS = UUID.fromString("0190f0a0-0000-7000-8000-00000000000a");
    private static final UUID SESSION = UUID.fromString("0190f0a0-0000-7000-8000-00000000000b");

    private final Settings settings = launchSettings();

    @Test
    void anOkTakeGetsTheScoreWorkedByHand() {
        // 128 words; Structure S,S,W,S = 13.5 + 9 + 5.625 + 11.25 = 39; Language 3,3,4 = 8.17 + 8.17 + 11.67 = 28;
        // Delivery: no 5 s pause, 6 "um" (6.5 a minute, under 8), no cluster, every window in 85..240 = 20.
        ReportV2 report = run(FakeScenario.OK, TAKE_MS).report();

        assertThat(report.status()).isEqualTo(Status.OK);
        assertThat(report.score()).isNotNull();
        assertThat(report.score().total()).isEqualTo(87);
        assertThat(report.score().overallBand()).isEqualTo(ReportV2.BandWire.STRONG);
        assertThat(report.score().parts()).extracting(ReportV2.Part::points).containsExactly(39, 28, 20);
        assertThat(report.facts().words()).isEqualTo(128);
        assertThat(report.facts().fillers().confirmed()).isEqualTo(6);
        assertThat(report.facts().fillers().hedges()).isEqualTo(1); // "I think"
        assertThat(report.facts().habitMarkers()).isEqualTo(1); // one sentence opens with "So"
        assertThat(report.facts().pace().word()).isEqualTo(ReportV2.PaceWordWire.STEADY);
        assertThat(report.facts().pace().windows()).hasSize(6); // 5 full windows and a last one of 5 s
        assertThat(report.facts().hitCap()).isFalse();
        assertThat(report.findings().stepsHeard()).isEqualTo(4);
        assertThat(report.fixFirst().id()).isEqualTo("strengthen_step");
        assertThat(report.fixFirst().step()).isEqualTo(2); // the weak Example
        assertThat(report.fixFirst().label()).isEqualTo("Strengthen a step."); // C-70: from content/fixes
        assertThat(report.nextAttempt()).isEqualTo(new ReportV2.NextAttempt(true, "second_attempt"));
        assertThat(report.strongestLine()).isNotNull();
        assertThat(report.strongestLine().sentenceId()).isEqualTo(1);
        assertThat(report.quotes()).containsOnlyKeys("0", "1", "2", "10");
        assertThat(report.cautions()).isEmpty();
        assertThat(report.noScore()).isNull();
    }

    @Test
    void everyReportCarriesTheJudgesVersions() {
        for (FakeScenario scenario : List.of(
                FakeScenario.OK,
                FakeScenario.SHORT_TAKE,
                FakeScenario.LOW_AUDIO,
                FakeScenario.NOT_ENGLISH,
                FakeScenario.NO_SPEECH)) {
            ReportV2 report = run(scenario, TAKE_MS).report();
            assertThat(report.promptVersion()).as(scenario.wire()).isEqualTo("fake-prompt-0");
            assertThat(report.schemaVersion()).as(scenario.wire()).isEqualTo("fake-schema-0");
            assertThat(report.modelVersion()).as(scenario.wire()).isEqualTo("fake-judge");
            assertThat(report.formulaVersion()).as(scenario.wire()).isEqualTo("2026-10-17");
        }
    }

    @Test
    void aLowAudioTakeIsScoredWithTheCautionAndNoQuoteOnAnUnsureWord() {
        // one word in five under 0.5 confidence: 25 of 128 words, above the 10 % line (F-036)
        ReportV2 report = run(FakeScenario.LOW_AUDIO, TAKE_MS).report();

        assertThat(report.status()).isEqualTo(Status.LOW_AUDIO);
        assertThat(report.score()).isNotNull();
        assertThat(report.cautions()).containsExactly(ReportV2.Caution.LOW_AUDIO);
        assertThat(report.facts().misheard())
                .extracting(ReportV2.Misheard::wordIndex)
                .containsExactly(4, 9, 14);
        assertThat(report.quotes()).isEmpty(); // every sentence holds an unsure word
        assertThat(report.strongestLine()).isNull();
        assertThat(report.findings().languageItems()).isEmpty();
    }

    @Test
    void aShortTakeHasNoScoreAndSaysWhy() {
        ReportV2 report = run(FakeScenario.SHORT_TAKE, TAKE_MS).report();

        assertThat(report.status()).isEqualTo(Status.SHORT_TAKE);
        assertNoScore(report);
        assertThat(report.noScore()).isEqualTo(new ReportV2.NoScore(Status.SHORT_TAKE, 30, TAKE_MS, false));
    }

    @Test
    void aTakeInAnotherLanguageHasNoScore() {
        ReportV2 report = run(FakeScenario.NOT_ENGLISH, TAKE_MS).report();

        assertThat(report.status()).isEqualTo(Status.NOT_ENGLISH);
        assertNoScore(report);
    }

    @Test
    void aTakeWithNoWordsHasNoScore() {
        ReportV2 report = run(FakeScenario.NO_SPEECH, TAKE_MS).report();

        assertThat(report.status()).isEqualTo(Status.NO_SPEECH);
        assertNoScore(report);
        assertThat(report.facts().firstWordMs()).isNull();
    }

    @Test
    void aTakeOfMoreThanFortyWordsUnderThirtySecondsIsScoredWithACaution() {
        ReportV2 report = run(FakeScenario.OK, 25_000).report(); // 56 words in 25 s

        assertThat(report.status()).isEqualTo(Status.OK);
        assertThat(report.cautions()).containsExactly(ReportV2.Caution.SHORT_TAKE_CAUTION);
    }

    @Test
    void aVendorFailureStopsThePipelineWithNoReport() {
        assertThatThrownBy(() -> run(FakeScenario.VENDOR_FAILURE, TAKE_MS)).isInstanceOf(VendorUnavailable.class);
    }

    @Test
    void theNoScoreChecksRunBeforeTheJudgeIsCalled() {
        Judge judge = mock(Judge.class);
        when(judge.versions()).thenReturn(FakeJudge.VERSIONS);
        Pipeline pipeline = new Pipeline(
                new FakeSpeechToText(FakeScenario.SHORT_TAKE, 0), judge, analysis(), FixTextsFixtures.content());

        pipeline.run(input(TAKE_MS));

        org.mockito.Mockito.verify(judge, org.mockito.Mockito.never()).judge(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theTakeReachesTheCapByTheServersMeasureOnly() {
        assertThat(run(FakeScenario.OK, 60_700).report().facts().hitCap()).isTrue(); // C-60: 60.7 s on a 60 s cap
        assertThat(run(FakeScenario.OK, 59_900).report().facts().hitCap()).isFalse();
    }

    @Test
    void everySpeechToTextCallOptsOutAndKeepsFillerWords() {
        FakeSpeechToText stt = new FakeSpeechToText(FakeScenario.OK, 0);
        Pipeline pipeline = new Pipeline(stt, new FakeJudge(), analysis(), FixTextsFixtures.content());

        pipeline.run(input(TAKE_MS));
        pipeline.run(input(30_000));

        assertThat(stt.requests()).hasSize(2).allSatisfy(request -> {
            assertThat(request.mipOptOut())
                    .as("mip_opt_out=true (03 F-027, F-080)")
                    .isTrue();
            assertThat(request.fillerWords()).as("filler_words=true (03 F-031)").isTrue();
        });
    }

    @Test
    void theRequestCheckFailsWhenAnOptionIsMissing() {
        // the test above would catch a request without either option
        assertThat(new SttRequest(false, true).mipOptOut()).isFalse();
        assertThat(SttRequest.launch()).isEqualTo(new SttRequest(true, true));
    }

    @Test
    void factsAndFindingsCarryNoWordOfTheTake() {
        Pipeline.Result result = run(FakeScenario.OK, TAKE_MS);
        Set<String> spoken = new HashSet<>();
        for (SpeechDataView.Word word : result.speech().words()) {
            spoken.add(word.w().replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT));
        }
        spoken.removeIf(String::isEmpty);

        JsonNode report = JsonMapper.builder().build().valueToTree(result.report());
        Set<String> found = new HashSet<>();
        collectText(report.path("facts"), found);
        collectText(report.path("findings"), found);

        assertThat(found).doesNotContainAnyElementsOf(spoken);
        assertThat(String.join(" ", result.report().quotes().values()))
                .as("quotes are the one place the words are kept (F-119)")
                .contains("checkout");
    }

    @Test
    void theFixLabelAndHowAreTheFixLibraryTextsNeverTheModels() { // C-70
        ReportV2 report = run(FakeScenario.OK, TAKE_MS).report();
        FixTexts.Text text =
                FixTextsFixtures.content().text(report.fixFirst().id()).orElseThrow();

        assertThat(report.fixFirst().label()).isEqualTo(text.label());
        assertThat(report.fixFirst().how()).isEqualTo(text.how());
        assertThat(report.fixFirst().tryLine()).isEqualTo("Give your example a name and a place."); // the model's
    }

    @Test
    void everyFixIdHasItsTextInTheFixLibraryFile() {
        FixTexts texts = FixTextsFixtures.content();

        for (FixId fix : FixId.values()) {
            assertThat(texts.text(fix.code())).as(fix.code()).isPresent();
        }
    }

    @Test
    void aFixIdMissingFromTheFileStopsThePipelineFromBeingBuilt() { // so the server does not start
        FixTexts missing = FixTextsFixtures.without("add_closing");

        assertThatThrownBy(() ->
                        new Pipeline(new FakeSpeechToText(FakeScenario.OK, 0), new FakeJudge(), analysis(), missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("add_closing");
    }

    @Test
    void modelTextWithANumberIsDroppedAndTheScoreDoesNotMove() {
        Judge sneaky = new Judge() {
            @Override
            public JudgeLabels judge(JudgeInput input) {
                JudgeLabels real = new FakeJudge().judge(input);
                return new JudgeLabels(
                        real.steps(),
                        real.stepSentences(),
                        real.relevance(),
                        real.language(),
                        real.fillers(),
                        real.runOn(),
                        "You earned 100 points.",
                        "Say it in 2 parts.",
                        real.rewrite(),
                        real.strongestSentence(),
                        "Ninety percent better.",
                        real.languageItems());
            }

            @Override
            public JudgeVersions versions() {
                return FakeJudge.VERSIONS;
            }
        };
        ReportV2 report = new Pipeline(
                        new FakeSpeechToText(FakeScenario.OK, 0), sneaky, analysis(), FixTextsFixtures.content())
                .run(input(TAKE_MS))
                .report();

        assertThat(report.verdict()).isNull();
        assertThat(report.fixFirst().tryLine()).isNull();
        assertThat(report.strongestLine().why()).isNull();
        assertThat(report.score().total()).isEqualTo(87);
    }

    @Test
    void aPersonWithFiveScoredTakesIsMeasuredAgainstTheirOwnBand() {
        Pipeline pipeline = new Pipeline(
                new FakeSpeechToText(FakeScenario.OK, 0), new FakeJudge(), analysis(), FixTextsFixtures.content());
        Pipeline.Input input = new Pipeline.Input(
                new byte[] {1},
                TAKE_MS,
                CAP_MS,
                Style.PREP,
                "topic",
                ANALYSIS,
                SESSION,
                1,
                Optional.empty(),
                Optional.empty(),
                List.of(150, 150, 150, 150, 150));

        ReportV2.Pace pace = pipeline.run(input).report().facts().pace();

        assertThat(pace.bandSource()).isEqualTo(ReportV2.BandSource.PERSONAL);
        assertThat(pace.band()).containsExactly(125, 175);
    }

    private static void assertNoScore(ReportV2 report) {
        assertThat(report.score()).isNull();
        assertThat(report.findings()).isNull();
        assertThat(report.verdict()).isNull();
        assertThat(report.fixFirst()).isNull();
        assertThat(report.nextAttempt()).isNull();
        assertThat(report.quotes()).isEmpty();
        assertThat(report.facts().pace().word()).isNull();
        assertThat(report.noScore()).isNotNull();
        assertThat(report.noScore().countedAsAttempt()).isFalse();
    }

    private static void collectText(JsonNode node, Set<String> found) {
        if (node.isString()) {
            for (String token : node.asString().split("[^\\p{L}]+", -1)) {
                found.add(token.toLowerCase(Locale.ROOT));
            }
        }
        for (JsonNode child : node) {
            collectText(child, found);
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                collectText(field.getValue(), found);
            }
        }
    }

    private Pipeline.Result run(FakeScenario scenario, int takeMs) {
        return new Pipeline(new FakeSpeechToText(scenario, 0), new FakeJudge(), analysis(), FixTextsFixtures.content())
                .run(input(takeMs));
    }

    private static Pipeline.Input input(int takeMs) {
        return new Pipeline.Input(
                new byte[] {1},
                takeMs,
                CAP_MS,
                Style.PREP,
                "topic",
                ANALYSIS,
                SESSION,
                1,
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    private AnalysisSettings analysis() {
        return new AnalysisSettings(settings);
    }

    private static Settings launchSettings() {
        Settings settings = mock(Settings.class);
        for (SettingKey key : SettingKey.values()) {
            when(settings.get(key)).thenReturn(key.launchDefault());
        }
        return settings;
    }
}
