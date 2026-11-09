package app.stageminute.analysis.internal;

import app.stageminute.analysis.SpeechDataView;
import app.stageminute.analysis.score.DeliveryFacts;
import app.stageminute.analysis.score.FillerCandidate;
import app.stageminute.analysis.score.FillerFacts;
import app.stageminute.analysis.score.FillerKind;
import app.stageminute.analysis.score.FillerTally;
import app.stageminute.analysis.score.FixChoice;
import app.stageminute.analysis.score.FixId;
import app.stageminute.analysis.score.FixLibrary;
import app.stageminute.analysis.score.FixSelection;
import app.stageminute.analysis.score.FixSettings;
import app.stageminute.analysis.score.FocusCheckResult;
import app.stageminute.analysis.score.FocusResult;
import app.stageminute.analysis.score.GateInput;
import app.stageminute.analysis.score.GateResult;
import app.stageminute.analysis.score.GateThresholds;
import app.stageminute.analysis.score.LabelValidator;
import app.stageminute.analysis.score.MetricsSettings;
import app.stageminute.analysis.score.MetricsV2;
import app.stageminute.analysis.score.Moment;
import app.stageminute.analysis.score.MomentSettings;
import app.stageminute.analysis.score.Moments;
import app.stageminute.analysis.score.NoScoreGate;
import app.stageminute.analysis.score.PaceBand;
import app.stageminute.analysis.score.PaceWord;
import app.stageminute.analysis.score.PaceWords;
import app.stageminute.analysis.score.PersonalBandSettings;
import app.stageminute.analysis.score.PersonalPaceBand;
import app.stageminute.analysis.score.PreviousTake;
import app.stageminute.analysis.score.ScoreBuilder;
import app.stageminute.analysis.score.ScoreDelta;
import app.stageminute.analysis.score.ScoreInput;
import app.stageminute.analysis.score.ScoreResult;
import app.stageminute.analysis.score.ScoreSettings;
import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.SpeechFacts;
import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.StrongestLine;
import app.stageminute.analysis.score.StrongestLineResult;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.score.TakeFacts;
import app.stageminute.analysis.score.TakeMoments;
import app.stageminute.analysis.score.TakeStatus;
import app.stageminute.analysis.score.TimedWord;
import app.stageminute.analysis.score.Transcript;
import app.stageminute.analysis.vendor.Judge;
import app.stageminute.analysis.vendor.JudgeInput;
import app.stageminute.analysis.vendor.JudgeLabels;
import app.stageminute.analysis.vendor.JudgeVersions;
import app.stageminute.analysis.vendor.SpeechResult;
import app.stageminute.analysis.vendor.SpeechToText;
import app.stageminute.analysis.vendor.SttRequest;
import app.stageminute.analysis.vendor.VendorBudget;
import app.stageminute.analysis.vendor.VendorUnavailable;
import app.stageminute.practice.AttemptRule;
import app.stageminute.practice.AttemptSettings;
import app.stageminute.practice.FocusOutcome;
import app.stageminute.practice.NextAttempt;
import app.stageminute.reports.FixTexts;
import app.stageminute.reports.ReportV2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One take from audio to Report v2 (04-FLOWS.md "Analyze"; 03 F-026 to F-037): speech to text, Metrics v2, the
 * no-score gate (before the AI call, F-037), the one labels-only call (D-84), the label checks, the score, the fix and
 * focus check, the moments, the strongest line, the pace word and the delta. It holds no database, no clock and no
 * user: the caller passes the server-measured length, the cap, the baseline attempt and the past paces, and stores the
 * result. Vendor failures come out as {@link VendorUnavailable}; nothing is half-built.
 *
 * <p>Code builds every number. Model text is kept only where the report shows the model's words, and is dropped when it
 * holds a number ({@link LabelValidator}), is too long, or (the Try line) repeats a word heard with low confidence
 * (F-032, F-036).
 */
@Component
public class Pipeline {

    private static final int STEPS = 4;
    private static final int VERDICT_MAX_WORDS = 15;
    private static final int REWRITE_MAX_WORDS = 60;
    private static final double SHARE_DIGITS = 100.0;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final MetricsSettings METRICS = MetricsSettings.LAUNCH_DEFAULT;

    private final SpeechToText speechToText;
    private final Judge judge;
    private final AnalysisSettings settings;
    private final FixTexts fixTexts;

    /**
     * @throws IllegalStateException naming every {@link FixId} the fix texts file does not have (C-70): the pipeline
     *     is built at start, so a missing text stops the server then, never at a request
     */
    public Pipeline(SpeechToText speechToText, Judge judge, AnalysisSettings settings, FixTexts fixTexts) {
        List<String> missing = Arrays.stream(FixId.values())
                .map(FixId::code)
                .filter(code -> fixTexts.text(code).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The fix texts file has no text for " + missing);
        }
        this.speechToText = speechToText;
        this.judge = judge;
        this.settings = settings;
        this.fixTexts = fixTexts;
    }

    /**
     * One take to analyse.
     *
     * @param audio the AAC/M4A bytes; in memory for this request only
     * @param durationMs the length the server measured from the audio (C-69)
     * @param capMs the topic's speaking limit; a take this long or longer reached the cap
     * @param attempt the number this take has if it is scored (1 to 3)
     * @param focusBaseline the attempt that set the fix this take is checked against (C-43), if any
     * @param previous the previous scored attempt of the session, for the delta, if any
     * @param pastTakeWpm the average pace of each of the person's past scored takes, oldest first
     */
    @SuppressWarnings("ArrayRecordComponent") // the audio is read once and never compared
    public record Input(
            byte[] audio,
            int durationMs,
            int capMs,
            Style style,
            String topicText,
            UUID analysisId,
            UUID sessionId,
            int attempt,
            Optional<PreviousTake> focusBaseline,
            Optional<PreviousScore> previous,
            List<Integer> pastTakeWpm) {

        public Input {
            Objects.requireNonNull(focusBaseline, "focusBaseline");
            Objects.requireNonNull(previous, "previous");
            pastTakeWpm = List.copyOf(pastTakeWpm);
        }

        @Override
        public String toString() {
            return "Pipeline.Input[" + style + ", attempt " + attempt + ", " + durationMs + " ms]";
        }
    }

    /** The previous scored attempt of the session: its number and total. */
    public record PreviousScore(int attempt, int total) {}

    /**
     * What came out: the report, the take's words for the phone (returned once, never stored) and, for a scored take,
     * what the server keeps about it.
     */
    public record Result(ReportV2 report, SpeechDataView speech, Optional<Scored> scored) {}

    /**
     * The numbers and labels a scored take leaves behind (the {@code facts_record}, D-11): no words.
     *
     * @param facts the facts the next attempt's focus check reads
     * @param fix the fix this report put first
     * @param focus the focus check result, if this was a later attempt
     * @param averageWpm the take's average pace, for a later personal band
     * @param answeredJudgeModel the model id the judge says served the labels (a real judge only), or null
     * @param answeredSpeechModel the model name speech to text says it used (a real client only), or null
     */
    public record Scored(
            TakeFacts facts,
            FixChoice fix,
            Optional<FocusResult> focus,
            int averageWpm,
            @Nullable String answeredJudgeModel,
            @Nullable String answeredSpeechModel) {}

    public Result run(Input input) {
        // One budget for both vendor calls (F-122): the Deepgram retry and the Gemini call see the time that is left.
        VendorBudget.Scope budget = VendorBudget.open(settings.vendorBudgetMs());
        try {
            return runWithinBudget(input);
        } finally {
            budget.close();
        }
    }

    private Result runWithinBudget(Input input) {
        SpeechResult heard = speechToText.transcribe(input.audio(), input.durationMs(), SttRequest.launch());
        Transcript transcript = heard.transcript();
        SpeechFacts speech = MetricsV2.compute(transcript, METRICS);
        GateResult gate = NoScoreGate.check(
                new GateInput(
                        input.durationMs(), speech.wordCount(), heard.englishHeard(), speech.lowConfidenceWords()),
                GateThresholds.LAUNCH_DEFAULT);
        JudgeVersions versions = judge.versions();
        boolean personal = input.pastTakeWpm().size() >= PersonalBandSettings.LAUNCH_DEFAULT.minTakes();
        PaceBand band = PersonalPaceBand.choose(input.pastTakeWpm(), PersonalBandSettings.LAUNCH_DEFAULT);
        boolean hitCap = input.durationMs() >= input.capMs();

        if (!gate.scored()) {
            FillerFacts fillers = FillerTally.of(speech.fillerCandidates(), Map.of(), input.durationMs(), METRICS);
            ReportV2.Facts facts = facts(transcript, speech, fillers, band, personal, Optional.empty(), hitCap);
            return new Result(
                    noScoreReport(input, gate, facts, versions),
                    speechData(transcript, null, Map.of()),
                    Optional.empty());
        }

        JudgeLabels labels = judge.judge(new JudgeInput(
                input.style(), input.topicText(), transcript, inContext(speech), speech.runOnSentences()));
        checkShape(labels);
        FillerFacts fillers = FillerTally.of(speech.fillerCandidates(), labels.fillers(), input.durationMs(), METRICS);
        TakeFacts take = takeFacts(input, speech, fillers, labels, band, hitCap);
        ScoreResult score = ScoreBuilder.build(
                new ScoreInput(
                        input.style(),
                        labels.steps(),
                        labels.relevance(),
                        labels.language(),
                        new DeliveryFacts(
                                speech.midAnswerPauses(),
                                fillers.clusterCount(),
                                fillers.confirmed(),
                                input.durationMs(),
                                speech.windowWpm()),
                        band),
                ScoreSettings.LAUNCH_DEFAULT);
        FixSelection selection = FixLibrary.choose(take, input.focusBaseline(), FixSettings.LAUNCH_DEFAULT);
        TakeMoments moments = Moments.pick(
                transcript,
                speech.fillerCandidates(),
                fillers,
                take,
                Optional.of(selection.fix().id()),
                METRICS,
                MomentSettings.LAUNCH_DEFAULT);
        Optional<StrongestLineResult> strongest =
                StrongestLine.pick(labels.strongestSentence(), transcript, moments.fixMoment(), METRICS);

        ReportBuilder report = new ReportBuilder(input, transcript, speech, labels, versions);
        ReportV2 built = report.scored(
                gate,
                score,
                facts(
                        transcript,
                        speech,
                        fillers,
                        band,
                        personal,
                        PaceWords.of(speech.windowWpm(), band, settings.paceWord()),
                        hitCap),
                selection,
                moments,
                strongest,
                nextAttempt(input.attempt(), selection.focusCheck()));
        Optional<FocusResult> focus = selection.focusCheck().map(FocusCheckResult::result);
        String rewrite = wordCount(labels.rewrite()) <= REWRITE_MAX_WORDS ? labels.rewrite() : null;
        return new Result(
                built,
                speechData(transcript, rewrite, report.tries()), // C-71: the rewording goes to the phone only
                Optional.of(new Scored(
                        take,
                        selection.fix(),
                        focus,
                        speech.averageWpm(),
                        labels.answeredModel(),
                        heard.answeredModel())));
    }

    /** A call that leaves out a step or a level is not an answer the score can be built from. */
    private static void checkShape(JudgeLabels labels) {
        if (labels.steps().size() != STEPS || labels.stepSentences().size() != STEPS) {
            throw new VendorUnavailable("judge_shape");
        }
        if (labels.relevance() < 1 || labels.relevance() > STEPS) {
            throw new VendorUnavailable("judge_shape");
        }
    }

    private static List<FillerCandidate> inContext(SpeechFacts speech) {
        return speech.fillerCandidates().stream()
                .filter(candidate -> candidate.kind() == FillerKind.IN_CONTEXT)
                .toList();
    }

    private static TakeFacts takeFacts(
            Input input, SpeechFacts speech, FillerFacts fillers, JudgeLabels labels, PaceBand band, boolean hitCap) {
        return new TakeFacts(
                input.style(),
                labels.steps(),
                labels.relevance(),
                labels.language(),
                speech.wordCount(),
                speech.speechMs(),
                input.durationMs(),
                speech.firstWordMs(),
                speech.midAnswerPauses(),
                speech.longestPauseMs(),
                fillers.confirmed(),
                fillers.clusterCount(),
                speech.longestSentenceWords(),
                labels.runOn() && !speech.runOnSentences().isEmpty(),
                speech.windowWpm(),
                band,
                hitCap);
    }

    private static NextAttempt nextAttempt(int attempt, Optional<FocusCheckResult> focus) {
        FocusOutcome outcome =
                focus.map(check -> FocusOutcome.valueOf(check.result().name())).orElse(FocusOutcome.NONE);
        return AttemptRule.next(attempt, outcome, AttemptSettings.LAUNCH_DEFAULT);
    }

    /** {@code facts}: numbers and positions only. The pace word is absent on a take with no score. */
    private static ReportV2.Facts facts(
            Transcript transcript,
            SpeechFacts speech,
            FillerFacts fillers,
            PaceBand band,
            boolean personal,
            Optional<PaceWord> word,
            boolean hitCap) {
        List<ReportV2.FillerItem> items = new ArrayList<>();
        for (int i = 0; i < fillers.confirmedIds().size(); i++) {
            items.add(new ReportV2.FillerItem(
                    fillers.confirmedIds().get(i), fillers.confirmedStartsMs().get(i)));
        }
        List<ReportV2.Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < fillers.clusters().size(); i++) {
            List<Integer> ids = fillers.clusters().get(i);
            FillerCandidate last = speech.fillerCandidates().get(ids.get(ids.size() - 1));
            clusters.add(new ReportV2.Cluster(
                    fillers.clusterStartsMs().get(i),
                    transcript.words().get(last.lastWord()).endMs(),
                    ids.size()));
        }
        return new ReportV2.Facts(
                transcript.durationMs(),
                speech.wordCount(),
                new ReportV2.Pace(
                        speech.averageWpm(),
                        speech.articulationWpm(),
                        word.map(w -> ReportV2.PaceWordWire.valueOf(w.name())).orElse(null),
                        speech.paceFlag(),
                        List.of(band.lowWpm(), band.highWpm()),
                        personal ? ReportV2.BandSource.PERSONAL : ReportV2.BandSource.DEFAULT,
                        speech.windowWpm()),
                pauses(transcript),
                speech.pausesRecorded(),
                speech.longestPauseMs(),
                speech.firstWordMs() < 0 ? null : speech.firstWordMs(),
                new ReportV2.Fillers(fillers.confirmed(), fillers.hedges(), fillers.perMinute(), items, clusters),
                speech.habitMarkerCount(),
                speech.longestSentenceWords(),
                speech.misheardWords().stream().map(ReportV2.Misheard::new).toList(),
                lowConfidenceShare(speech),
                hitCap);
    }

    /** Recorded pauses (1.2 s or more between two words), in time order; 5 s or more is mid-answer. */
    private static List<ReportV2.Pause> pauses(Transcript transcript) {
        List<ReportV2.Pause> pauses = new ArrayList<>();
        List<TimedWord> words = transcript.words();
        for (int i = 1; i < words.size(); i++) {
            int start = words.get(i - 1).endMs();
            int length = words.get(i).startMs() - start;
            if (length >= METRICS.pauseRecordMs()) {
                pauses.add(new ReportV2.Pause(start, length, length >= METRICS.pauseCountMs()));
            }
        }
        return pauses;
    }

    private static double lowConfidenceShare(SpeechFacts speech) {
        if (speech.wordCount() == 0) {
            return 0.0;
        }
        return Math.round(SHARE_DIGITS * speech.lowConfidenceWords() / speech.wordCount()) / SHARE_DIGITS;
    }

    private ReportV2 noScoreReport(Input input, GateResult gate, ReportV2.Facts facts, JudgeVersions versions) {
        ReportV2.Status status = ReportV2.Status.valueOf(gate.status().name());
        return new ReportV2(
                ReportV2.VERSION,
                input.analysisId(),
                input.sessionId(),
                input.attempt(),
                ReportV2.StyleWire.valueOf(input.style().name()),
                status,
                ScoreSettings.LAUNCH_DEFAULT.formulaVersion(),
                versions.model(),
                versions.prompt(),
                versions.schema(),
                null,
                null,
                null,
                facts,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                new ReportV2.NoScore(status, facts.words(), facts.durationMs(), false));
    }

    private static SpeechDataView speechData(
            Transcript transcript, @Nullable String rewrite, Map<String, String> languageTries) {
        List<TimedWord> words = transcript.words();
        List<SpeechDataView.Sentence> sentences = new ArrayList<>();
        for (int id = 0; id < transcript.sentences().size(); id++) {
            SentenceSpan span = transcript.sentences().get(id);
            sentences.add(new SpeechDataView.Sentence(
                    id,
                    words.get(span.firstWord()).startMs(),
                    words.get(span.lastWord()).endMs()));
        }
        return new SpeechDataView(
                words.stream().map(TimedWord::text).collect(Collectors.joining(" ")),
                words.stream()
                        .map(w -> new SpeechDataView.Word(w.text(), w.startMs(), w.endMs(), w.confidence()))
                        .toList(),
                sentences,
                rewrite,
                languageTries);
    }

    /** Builds a scored report from the pieces; holds the per-take state the mapping needs. */
    private final class ReportBuilder {

        private final Input input;
        private final Transcript transcript;
        private final SpeechFacts speech;
        private final JudgeLabels labels;
        private final JudgeVersions versions;
        private final Map<String, String> quotes = new LinkedHashMap<>();
        private final Map<String, String> tries = new LinkedHashMap<>();

        ReportBuilder(
                Input input, Transcript transcript, SpeechFacts speech, JudgeLabels labels, JudgeVersions versions) {
            this.input = input;
            this.transcript = transcript;
            this.speech = speech;
            this.labels = labels;
            this.versions = versions;
        }

        ReportV2 scored(
                GateResult gate,
                ScoreResult score,
                ReportV2.Facts facts,
                FixSelection selection,
                TakeMoments moments,
                Optional<StrongestLineResult> strongest,
                NextAttempt next) {
            ReportV2.Findings findings = findings();
            List<ReportV2.LanguageItem> items = new ArrayList<>();
            List<ReportV2.LanguageItem> swaps = new ArrayList<>();
            languageItems(items, swaps, tries);
            findings = new ReportV2.Findings(
                    findings.steps(),
                    findings.stepsHeard(),
                    findings.rubric(),
                    items,
                    swaps,
                    findings.runOn(),
                    findings.runOnCount());

            List<ReportV2.Moment> shown = new ArrayList<>();
            String fixMomentId = null;
            for (int i = 0; i < moments.moments().size(); i++) {
                Moment moment = moments.moments().get(i);
                String id = "m" + (i + 1);
                shown.add(new ReportV2.Moment(
                        id, ReportV2.MomentKindWire.valueOf(moment.kind().name()), moment.startMs(), moment.endMs()));
                if (moments.fixMoment().filter(moment::equals).isPresent()) {
                    fixMomentId = id;
                }
            }
            ReportV2.StrongestLine line = strongest.map(this::strongestLine).orElse(null); // adds its quote
            List<ReportV2.Caution> cautions = new ArrayList<>();
            if (gate.shortCaution()) {
                cautions.add(ReportV2.Caution.SHORT_TAKE_CAUTION);
            }
            if (gate.status() == TakeStatus.LOW_AUDIO) {
                cautions.add(ReportV2.Caution.LOW_AUDIO);
            }
            return new ReportV2(
                    ReportV2.VERSION,
                    input.analysisId(),
                    input.sessionId(),
                    input.attempt(),
                    ReportV2.StyleWire.valueOf(input.style().name()),
                    ReportV2.Status.valueOf(gate.status().name()),
                    score.formulaVersion(),
                    versions.model(),
                    versions.prompt(),
                    versions.schema(),
                    score(score),
                    modelLine(labels.verdict(), VERDICT_MAX_WORDS),
                    input.previous()
                            .map(previous -> new ReportV2.Delta(
                                    previous.attempt(),
                                    previous.total(),
                                    ReportV2.Direction.valueOf(ScoreDelta.direction(
                                                    previous.total(), score.total(), settings.deltaSameWithinPoints())
                                            .name())))
                            .orElse(null),
                    facts,
                    findings,
                    Map.copyOf(quotes),
                    fixFirst(selection.fix(), fixMomentId),
                    selection.focusCheck().map(Pipeline::focusCheck).orElse(null),
                    new ReportV2.NextAttempt(
                            next.allowed(), next.reason().name().toLowerCase(Locale.ROOT)),
                    null, // next_focus and carried_focus: set when the report is saved (Reports.save, F-034)
                    null,
                    line,
                    shown,
                    cautions,
                    null,
                    null);
        }

        private ReportV2.Findings findings() {
            List<ReportV2.StepName> names = input.style() == Style.PREP
                    ? List.of(
                            ReportV2.StepName.POINT,
                            ReportV2.StepName.REASON,
                            ReportV2.StepName.EXAMPLE,
                            ReportV2.StepName.CLOSING)
                    : List.of(
                            ReportV2.StepName.SITUATION,
                            ReportV2.StepName.TASK,
                            ReportV2.StepName.ACTION,
                            ReportV2.StepName.RESULT);
            List<ReportV2.Step> steps = new ArrayList<>();
            int heard = 0;
            for (int i = 0; i < STEPS; i++) {
                StepStatus status = labels.steps().get(i);
                if (status != StepStatus.MISSING) {
                    heard++;
                }
                Integer sentence = status == StepStatus.MISSING
                        ? null
                        : quote(labels.stepSentences().get(i));
                steps.add(new ReportV2.Step(names.get(i), ReportV2.StepStatusWire.valueOf(status.name()), sentence));
            }
            boolean runOn = labels.runOn() && !speech.runOnSentences().isEmpty();
            return new ReportV2.Findings(
                    steps,
                    heard,
                    new ReportV2.Rubric(
                            labels.relevance(),
                            labels.language().clarity(),
                            labels.language().conciseness(),
                            labels.language().accuracy()),
                    List.of(),
                    List.of(),
                    runOn,
                    runOn ? speech.runOnSentences().size() : 0);
        }

        /** The model's wording of each language item, by item id: for {@code speech_data}, never the report (C-71). */
        Map<String, String> tries() {
            return Map.copyOf(tries);
        }

        /** Language items on sure words only (F-036); the wording goes to {@code language_tries}. */
        private void languageItems(
                List<ReportV2.LanguageItem> items, List<ReportV2.LanguageItem> swaps, Map<String, String> tries) {
            int id = 0;
            for (JudgeLabels.LanguageItem item : labels.languageItems()) {
                ReportV2.LanguageKind kind;
                try {
                    kind = ReportV2.LanguageKind.valueOf(item.kind().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue; // a kind the report does not know is left out
                }
                if (!inSentence(item) || !sure(item.firstWord(), item.lastWord()) || quote(item.sentence()) == null) {
                    continue;
                }
                ReportV2.LanguageItem shown =
                        new ReportV2.LanguageItem(id, kind, item.sentence(), item.firstWord(), item.lastWord());
                (kind == ReportV2.LanguageKind.SWAP ? swaps : items).add(shown);
                tries.put(String.valueOf(id), item.suggestion());
                id++;
            }
        }

        private boolean inSentence(JudgeLabels.LanguageItem item) {
            if (item.sentence() < 0 || item.sentence() >= transcript.sentences().size()) {
                return false;
            }
            SentenceSpan span = transcript.sentences().get(item.sentence());
            return item.firstWord() >= span.firstWord()
                    && item.lastWord() <= span.lastWord()
                    && item.firstWord() <= item.lastWord();
        }

        /**
         * Keeps the sentence as a quote and returns its id, or null when it does not exist or holds a word heard with
         * low confidence (F-036: never quote an unsure word).
         */
        private @Nullable Integer quote(@Nullable Integer sentenceId) {
            if (sentenceId == null
                    || sentenceId < 0
                    || sentenceId >= transcript.sentences().size()) {
                return null;
            }
            SentenceSpan span = transcript.sentences().get(sentenceId);
            if (!sure(span.firstWord(), span.lastWord())) {
                return null;
            }
            quotes.put(String.valueOf(sentenceId), text(span.firstWord(), span.lastWord()));
            return sentenceId;
        }

        private boolean sure(int firstWord, int lastWord) {
            for (int i = firstWord; i <= lastWord; i++) {
                if (transcript.words().get(i).confidence() < METRICS.lowConfidenceBelow()) {
                    return false;
                }
            }
            return true;
        }

        private String text(int firstWord, int lastWord) {
            return transcript.words().subList(firstWord, lastWord + 1).stream()
                    .map(TimedWord::text)
                    .collect(Collectors.joining(" "));
        }

        /** The fix by id, with the Fix Library's label and How line (C-70: never the model's) and the model's Try. */
        private ReportV2.FixFirst fixFirst(FixChoice fix, @Nullable String momentId) {
            FixTexts.Text text = fixTexts.text(fix.id().code()).orElseThrow(); // every id was checked at start
            return new ReportV2.FixFirst(
                    fix.id().code(),
                    fix.target() == FixChoice.NO_TARGET ? null : fix.target(),
                    text.label(),
                    text.how(),
                    tryLine(labels.tryLine()),
                    momentId,
                    fix.carriedOver(),
                    fix.nothingMajor());
        }

        /** The Try line, unless it holds a number or repeats a word heard with low confidence (F-032, F-036). */
        private @Nullable String tryLine(@Nullable String line) {
            String checked = modelLine(line, Integer.MAX_VALUE);
            if (checked == null) {
                return null;
            }
            Set<String> unsure = new HashSet<>();
            for (int position : speech.misheardWords()) {
                unsure.add(plain(transcript.words().get(position).text()));
            }
            for (String token : WHITESPACE.split(checked, -1)) {
                if (unsure.contains(plain(token))) {
                    return null;
                }
            }
            return checked;
        }

        private ReportV2.StrongestLine strongestLine(StrongestLineResult line) {
            quote(line.sentenceId());
            return new ReportV2.StrongestLine(
                    line.sentenceId(),
                    text(line.firstWord(), line.lastWord()),
                    line.startMs(),
                    line.endMs(),
                    modelLine(labels.strongestWhy(), Integer.MAX_VALUE));
        }
    }

    private static ReportV2.Score score(ScoreResult score) {
        return new ReportV2.Score(
                score.total(),
                ReportV2.BandWire.valueOf(score.overallBand().name()),
                score.parts().stream()
                        .map(part -> new ReportV2.Part(
                                ReportV2.PartId.valueOf(part.id().toUpperCase(Locale.ROOT)),
                                part.points(),
                                part.max(),
                                ReportV2.BandWire.valueOf(part.band().name())))
                        .toList());
    }

    private static ReportV2.FocusCheck focusCheck(FocusCheckResult check) {
        return new ReportV2.FocusCheck(
                check.fixId().code(),
                ReportV2.FocusResultWire.valueOf(check.result().name()),
                new ReportV2.Evidence(
                        ReportV2.MeasureWire.valueOf(check.evidence().measure().name()),
                        check.evidence().before(),
                        check.evidence().after()),
                check.previousStatus()
                        .map(status -> ReportV2.StepStatusWire.valueOf(status.name()))
                        .orElse(null));
    }

    /** Model text the report may show: null when it holds a number (F-029) or is longer than the limit. */
    private static @Nullable String modelLine(@Nullable String line, int maxWords) {
        if (line == null || line.isBlank() || LabelValidator.hasNumber(line) || wordCount(line) > maxWords) {
            return null;
        }
        return line;
    }

    private static int wordCount(@Nullable String text) {
        return text == null || text.isBlank() ? 0 : WHITESPACE.split(text.trim()).length;
    }

    private static String plain(String word) {
        return word.replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
    }
}
