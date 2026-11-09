package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 03 F-027 "Done when": the metrics match the research test script on the fixture set.
 *
 * <p>The fixture set is 42 real Deepgram results (the clips of step2/research, first 60 s, English), kept as timings,
 * confidences and sentence spans only: every word that is not a filler, hedge, habit marker or function word is "w".
 * It is rebuilt with step2/research/scripts/make_parity_fixtures.py (step2 is git-ignored: the script, the clips and
 * the generator are on the founder's PC only), which also writes {@code expected.tsv}. The
 * expected values in that file do not come from the Java code:
 *
 * <ul>
 *   <li>{@code script_*} are what step2/research/scripts/metrics_v2.py prints, for the measures the script and
 *       requirements version 1.7 define the same way;
 *   <li>the rest come from an independent Python reading of 03 F-027 and F-031 (version 1.7).
 * </ul>
 *
 * <p>Where version 1.7 replaced a rule of the script on purpose, the two differ and are not compared: articulation
 * rate (script drops every gap over 0.3 s, v1.7 drops pauses of 1.2 s or more), the freeze (script 4 s, v1.7 5 s),
 * the filler word lists, the pace band (script 95 to 210, v1.7 85 to 240 from 0 s) and the mis-heard list.
 */
class ResearchFixtureParityTest {

    private static final String ROOT = "/research-parity/";
    private static final MetricsSettings SETTINGS = MetricsSettings.LAUNCH_DEFAULT;

    /** Keys written by the generator for the v1.7 reference values, in the order the generator writes them. */
    private static final List<String> V17_KEYS = List.of(
            "n",
            "first",
            "rec",
            "mid",
            "longest",
            "art",
            "avg",
            "wins",
            "outside",
            "flag",
            "low",
            "habit",
            "runon",
            "always",
            "context",
            "hedge",
            "clusters",
            "mis");

    private static String read(String name) throws IOException {
        try (InputStream in = ResearchFixtureParityTest.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IOException("missing test resource " + ROOT + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Stream<Arguments> clips() throws IOException {
        String[] lines = read("expected.tsv").split("\n", -1);
        String[] header = lines[0].split("\t", -1);
        List<Arguments> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split("\t", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.length; c++) {
                row.put(header[c], cells[c]);
            }
            rows.add(Arguments.of(row.get("id"), row));
        }
        return rows.stream();
    }

    private static Transcript transcript(String id) throws IOException {
        int durationMs = 0;
        List<TimedWord> words = new ArrayList<>();
        List<SentenceSpan> sentences = new ArrayList<>();
        for (String line : read("clips/" + id + ".tsv").split("\n", -1)) {
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "D" -> durationMs = Integer.parseInt(f[1]);
                case "W" ->
                    words.add(new TimedWord(
                            f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Double.parseDouble(f[4])));
                case "S" -> sentences.add(new SentenceSpan(Integer.parseInt(f[1]), Integer.parseInt(f[2])));
                default -> {}
            }
        }
        return new Transcript(words, sentences, durationMs);
    }

    private static long countKind(SpeechFacts facts, FillerKind kind) {
        return facts.fillerCandidates().stream().filter(c -> c.kind() == kind).count();
    }

    /** The same measures, in the same text form, as the generator's reference values. */
    private static Map<String, String> measured(SpeechFacts r) {
        List<Integer> alwaysStarts = r.fillerCandidates().stream()
                .filter(c -> c.kind() == FillerKind.ALWAYS_COUNTED)
                .map(FillerCandidate::startMs)
                .toList();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("n", String.valueOf(r.wordCount()));
        m.put("first", String.valueOf(r.firstWordMs()));
        m.put("rec", String.valueOf(r.pausesRecorded()));
        m.put("mid", String.valueOf(r.midAnswerPauses()));
        m.put("longest", String.valueOf(r.longestPauseMs()));
        m.put("art", String.valueOf(r.articulationWpm()));
        m.put("avg", String.valueOf(r.averageWpm()));
        m.put("wins", r.windowWpm().stream().map(String::valueOf).collect(Collectors.joining(",")));
        m.put("outside", String.valueOf(r.outsideBandMs()));
        m.put("flag", String.valueOf(r.paceFlag()));
        m.put("low", String.valueOf(r.lowConfidenceWords()));
        m.put("habit", String.valueOf(r.habitMarkerCount()));
        m.put("runon", String.valueOf(r.runOnSentences().size()));
        m.put("always", String.valueOf(countKind(r, FillerKind.ALWAYS_COUNTED)));
        m.put("context", String.valueOf(countKind(r, FillerKind.IN_CONTEXT)));
        m.put("hedge", String.valueOf(countKind(r, FillerKind.HEDGE)));
        m.put("clusters", String.valueOf(MetricsV2.fillerClusters(alwaysStarts, SETTINGS)));
        m.put("mis", String.valueOf(r.misheardWords().size()));
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clips")
    void javaMatchesTheIndependentReadingOfRequirements17(String id, Map<String, String> expected) throws IOException {
        SpeechFacts facts = MetricsV2.compute(transcript(id), SETTINGS);

        Map<String, String> wanted = new LinkedHashMap<>();
        V17_KEYS.forEach(k -> wanted.put(k, expected.get(k)));
        assertThat(measured(facts)).isEqualTo(wanted);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clips")
    void javaMatchesTheResearchScriptWhereTheDefinitionsAgree(String id, Map<String, String> expected)
            throws IOException {
        SpeechFacts facts = MetricsV2.compute(transcript(id), SETTINGS);

        // words a minute from the first word's start to the last word's end (the script prints a whole number)
        assertThat(facts.averageWpm()).as("words a minute").isEqualTo(Integer.parseInt(expected.get("script_wpm")));
        // first word time (the script prints seconds with one decimal)
        assertThat(facts.firstWordMs() / 1000.0)
                .as("first word, seconds")
                .isCloseTo(Double.parseDouble(expected.get("script_first")), within(0.051));
        // longest pause: the script reports the longest gap of any size, we report the longest recorded pause (1.2 s+)
        double scriptLongest = Double.parseDouble(expected.get("script_longp"));
        if (scriptLongest >= 1.2) {
            assertThat(facts.longestPauseMs() / 1000.0)
                    .as("longest pause, seconds")
                    .isCloseTo(scriptLongest, within(0.051));
        } else {
            assertThat(facts.longestPauseMs()).as("no recorded pause").isZero();
        }
        // sentences over 40 words, unless one runs past the script's 60 s cut (the script counts it whole)
        if (!Boolean.parseBoolean(expected.get("runon_cut"))) {
            assertThat(facts.runOnSentences()).as("sentences over 40 words").hasSize((int)
                    Double.parseDouble(expected.get("script_over40")));
        }
        // a cluster of um/uh style fillers: the script reports the most in any 10 s, we report clusters
        List<Integer> alwaysStarts = facts.fillerCandidates().stream()
                .filter(c -> c.kind() == FillerKind.ALWAYS_COUNTED)
                .map(FillerCandidate::startMs)
                .toList();
        assertThat(MetricsV2.fillerClusters(alwaysStarts, SETTINGS) >= 1)
                .as("um/uh cluster present")
                .isEqualTo(Double.parseDouble(expected.get("script_cl_s")) >= 3);
    }

    @Test
    void theWholeFixtureSetIsThere() throws IOException {
        assertThat(clips().count()).isEqualTo(42);
    }

    @Test
    void noFixtureHoldsRealSpeech() throws IOException {
        // Privacy guard: a fixture word must be "w" or a word the lists already know (function words, fillers, hedges,
        // habit markers). A real transcript committed here by mistake fails this test.
        WordLists lists = SETTINGS.wordLists();
        Set<String> allowed = new java.util.HashSet<>(Set.of("w", "mhm", "mhmm"));
        allowed.addAll(lists.functionWords());
        allowed.addAll(lists.alwaysCounted());
        allowed.addAll(SETTINGS.habitMarkers());
        lists.phrases().forEach(p -> allowed.addAll(p.words()));

        List<String> strangers = new ArrayList<>();
        for (Object[] row : (Iterable<Object[]>) () -> {
            try {
                return clips().map(Arguments::get).iterator();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }) {
            String id = (String) row[0];
            for (TimedWord word : transcript(id).words()) {
                String normal = WordLists.normalise(word.text());
                if (!allowed.contains(normal)) {
                    strangers.add(id + ":" + normal);
                }
            }
        }

        assertThat(strangers).isEmpty();
    }
}
