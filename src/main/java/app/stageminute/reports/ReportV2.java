package app.stageminute.reports;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Report v2: the one shape every Result screen, report page, sheet and Recent-takes row reads (03 F-026;
 * docs/architecture/06-API.md section 9). It is what {@code POST /v1/analyze} returns as {@code report} and what the
 * server keeps in {@code reports.report}. The wire is snake_case (the server's naming rule), so {@code analysisId}
 * goes out as {@code analysis_id}.
 *
 * <p>Code builds every number. {@code facts} and {@code findings} hold numbers, positions and fixed labels only, never
 * speech ({@code ReportHoldsNoWordsTest}); the short {@code quotes} are the one place speech text is kept (F-119). The
 * model's own words are {@code verdict}, {@code fix_first.try} and {@code strongest_line.why}; the fix label and how
 * line are the server's texts, never the model's (C-63). {@code speech_data} is never part of a report, nor is the
 * model's rewording of the answer ({@code rewrite}, {@code language_tries}): both travel with it (C-71).
 */
@Schema(name = "Report", description = "Report v2 (03 F-026)")
public record ReportV2(
        @Schema(description = "Always 2") int v,
        @JsonProperty("analysis_id") UUID analysisId,
        @JsonProperty("session_id") UUID sessionId,
        int attempt,
        StyleWire style,
        Status status,
        @JsonProperty("formula_version") String formulaVersion,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("schema_version") String schemaVersion,

        @Nullable @Schema(nullable = true, description = "Null when the take has no score")
        Score score,

        @Nullable @Schema(nullable = true, description = "The model's one line, at most 15 words")
        String verdict,

        @Nullable @Schema(nullable = true, description = "Null on a first attempt or a take with no score")
        Delta delta,

        Facts facts,

        @Nullable @Schema(nullable = true, description = "Null when the take has no score")
        Findings findings,

        @Schema(description = "Sentence id to the short quote: the only speech text the server keeps (F-119)")
        Map<String, String> quotes,

        @Nullable @Schema(nullable = true) @JsonProperty("fix_first")
        FixFirst fixFirst,

        @Nullable @Schema(nullable = true, description = "Null on a first attempt") @JsonProperty("focus_check")
        FocusCheck focusCheck,

        @Nullable
        @Schema(nullable = true, description = "Null when the take has no score")
        @JsonProperty("next_attempt")
        NextAttempt nextAttempt,

        @Nullable
        @Schema(
                nullable = true,
                description = "The focus this take leaves for the next topic (F-034); null when none is carried")
        @JsonProperty("next_focus")
        NextFocus nextFocus,

        @Nullable
        @Schema(
                nullable = true,
                description = "The focus this take started with, from the last topic") // copy-ok: API description
        @JsonProperty("carried_focus")
        CarriedFocus carriedFocus,

        @Nullable @Schema(nullable = true) @JsonProperty("strongest_line")
        StrongestLine strongestLine,

        @Schema(description = "At most 3, in time order") List<Moment> moments,

        List<Caution> cautions,

        @Nullable @Schema(nullable = true, description = "Next; always null at launch")
        Map<String, Integer> trend,

        @Nullable @Schema(nullable = true, description = "Only when the take has no score") @JsonProperty("no_score")
        NoScore noScore) {

    public static final int VERSION = 2;

    /** The same report with the carried focus it started with and the focus it leaves (F-034); nothing else moves. */
    public ReportV2 withFocus(@Nullable CarriedFocus carried, @Nullable NextFocus next) {
        return new ReportV2(
                v,
                analysisId,
                sessionId,
                attempt,
                style,
                status,
                formulaVersion,
                modelVersion,
                promptVersion,
                schemaVersion,
                score,
                verdict,
                delta,
                facts,
                findings,
                quotes,
                fixFirst,
                focusCheck,
                nextAttempt,
                next,
                carried,
                strongestLine,
                moments,
                cautions,
                trend,
                noScore);
    }

    /** No quote and no model text: a report printed into a log line must not carry speech (rule 4, F-119). */
    @Override
    public String toString() {
        return "ReportV2[" + analysisId + ", " + status.wire() + "]";
    }

    /** PREP or STAR. */
    public enum StyleWire {
        PREP,
        STAR;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether the take was scored, and if not, why (03 F-026, F-037). */
    public enum Status {
        OK,
        SHORT_TAKE,
        LOW_AUDIO,
        NOT_ENGLISH,
        NO_SPEECH;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A score band (03 F-030). */
    public enum BandWire {
        EXCELLENT,
        STRONG,
        GOOD,
        OPPORTUNITY;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code score {total, overall_band, parts[]}}. */
    @Schema(name = "ReportScore")
    public record Score(
            int total, @JsonProperty("overall_band") BandWire overallBand, List<Part> parts) {}

    /** One part of the score: structure 45, language 35, delivery 20. */
    @Schema(name = "ReportPart")
    public record Part(PartId id, int points, int max, BandWire band) {}

    /** The three parts. */
    public enum PartId {
        STRUCTURE,
        LANGUAGE,
        DELIVERY;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The change since the previous attempt; {@code same} inside the noise band (02b F-045). */
    @Schema(name = "ReportDelta")
    public record Delta(
            @JsonProperty("previous_attempt") int previousAttempt,
            @JsonProperty("previous_total") int previousTotal,
            Direction direction) {}

    /** Which way the total moved. */
    public enum Direction {
        UP,
        DOWN,
        SAME;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What code measured (03 F-027, F-031, F-036): numbers and positions only. Times are milliseconds from the start
     * of the take.
     */
    @Schema(name = "ReportFacts")
    public record Facts(
            @Schema(description = "Length the server measured from the audio (C-69)") @JsonProperty("duration_ms")
            int durationMs,

            int words,
            Pace pace,

            @Schema(description = "Recorded pauses (1.2 s or more), in time order")
            List<Pause> pauses,

            @JsonProperty("pauses_recorded") int pausesRecorded,

            @Schema(description = "0 when there is no recorded pause") @JsonProperty("longest_pause_ms")
            int longestPauseMs,

            @Nullable
            @Schema(nullable = true, description = "Null when no word was heard")
            @JsonProperty("first_word_ms")
            Integer firstWordMs,

            Fillers fillers,

            @Schema(description = "Sentences that begin with so, okay or right (counted, never scored)")
            @JsonProperty("habit_markers")
            int habitMarkers,

            @JsonProperty("longest_sentence_words") int longestSentenceWords,

            @Schema(description = "Up to 3 positions of words heard with low confidence; never quoted")
            List<Misheard> misheard,

            @Schema(description = "Share of words heard with low confidence, 0 to 1") @JsonProperty("low_conf_share")
            double lowConfShare,

            @Schema(description = "Whether the take reached the topic's time limit (server measured)")
            @JsonProperty("hit_cap")
            boolean hitCap) {}

    /** Pace: the take's average, articulation rate, the pace word, the flag, the band and the windows. */
    @Schema(name = "ReportPace")
    public record Pace(
            @JsonProperty("average_wpm") int averageWpm,
            @JsonProperty("articulation_wpm") int articulationWpm,

            @Nullable @Schema(nullable = true, description = "No points, never in the total (C-65)")
            PaceWordWire word,

            @Schema(description = "30 s or more in total outside 85 to 240 words a minute")
            boolean flag,

            @Schema(description = "[low, high] words a minute")
            List<Integer> band,

            @JsonProperty("band_source") BandSource bandSource,

            @Schema(description = "Words a minute in each 10 s window")
            List<Integer> windows) {}

    /** The pace word (C-65). */
    public enum PaceWordWire {
        STEADY,
        A_BIT_FAST,
        A_BIT_SLOW,
        UNEVEN;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Where the pace band came from: the launch default, or the person's own from their past takes. */
    public enum BandSource {
        DEFAULT,
        PERSONAL;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A recorded pause; mid-answer when it is 5 s or more. */
    @Schema(name = "ReportPause")
    public record Pause(
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("length_ms") int lengthMs,
            @JsonProperty("mid_answer") boolean midAnswer) {}

    /** {@code fillers {confirmed, hedges, per_min, items[], clusters[]}} (F-031); no filler words. */
    @Schema(name = "ReportFillers")
    public record Fillers(
            int confirmed,

            @Schema(description = "Softening phrases: counted, never scored")
            int hedges,

            @JsonProperty("per_min") int perMin,
            List<FillerItem> items,
            List<Cluster> clusters) {}

    /** A confirmed filler: its candidate id and when it starts. */
    @Schema(name = "ReportFillerItem")
    public record FillerItem(
            int id, @JsonProperty("start_ms") int startMs) {}

    /** A filler cluster: 3 or more confirmed fillers in 10 s. */
    @Schema(name = "ReportCluster")
    public record Cluster(
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("end_ms") int endMs,
            int count) {}

    /** A word heard with low confidence, by its position in the take's words. */
    @Schema(name = "ReportMisheard")
    public record Misheard(@JsonProperty("word_index") int wordIndex) {}

    /** The call's labels and the code's counts about structure and language: no words. */
    @Schema(name = "ReportFindings")
    public record Findings(
            List<Step> steps,

            @Schema(description = "Steps heard (strong or weak), out of 4") @JsonProperty("steps_heard")
            int stepsHeard,

            Rubric rubric,
            @JsonProperty("language_items") List<LanguageItem> languageItems,
            List<LanguageItem> swaps,
            @JsonProperty("run_on") boolean runOn,
            @JsonProperty("run_on_count") int runOnCount) {}

    /** One PREP or STAR step. */
    @Schema(name = "ReportStep")
    public record Step(
            StepName name,
            StepStatusWire status,

            @Nullable
            @Schema(nullable = true, description = "The sentence id of its quote, if any")
            @JsonProperty("quote_sentence")
            Integer quoteSentence) {}

    /** The four PREP steps then the four STAR steps. */
    public enum StepName {
        POINT,
        REASON,
        EXAMPLE,
        CLOSING,
        SITUATION,
        TASK,
        ACTION,
        RESULT;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A step label from the one call. */
    public enum StepStatusWire {
        STRONG,
        WEAK,
        MISSING;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The rubric levels, 1 to 4. */
    @Schema(name = "ReportRubric")
    public record Rubric(int relevance, int clarity, int conciseness, int accuracy) {}

    /**
     * A language item from the one call, by position: the words it is about are {@code first_word} to {@code last_word}
     * in the take's words, inside sentence {@code sentence}. The model's suggested wording is in {@code
     * speech_data.language_tries} under {@code id} (C-71): it stays on the phone, so findings never hold text.
     */
    @Schema(name = "ReportLanguageItem")
    public record LanguageItem(
            int id,
            LanguageKind kind,
            int sentence,
            @JsonProperty("first_word") int firstWord,
            @JsonProperty("last_word") int lastWord) {}

    /** What kind of language item. */
    public enum LanguageKind {
        GRAMMAR,
        WORD_CHOICE,
        SWAP;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The fix the report puts first (03 F-032). */
    @Schema(name = "ReportFixFirst")
    public record FixFirst(
            @Schema(description = "The fix id of 03 F-032, for example add_closing")
            String id,

            @Nullable @Schema(nullable = true, description = "The step (0 to 3) a step fix is about")
            Integer step,

            @Nullable
            @Schema(nullable = true, description = "The Fix Library label from content/fixes, never the model (C-70)")
            String label,

            @Nullable
            @Schema(
                    nullable = true,
                    description = "The Fix Library How line from content/fixes, never the model (C-70)")
            String how,

            @Nullable @JsonProperty("try") @Schema(nullable = true, description = "The model's Try line")
            String tryLine,

            @Nullable @Schema(nullable = true, description = "The id of the moment it points at, if any")
            String moment,

            @JsonProperty("carried_over") boolean carriedOver,

            @Schema(description = "A strong take: one polish, shown as Nothing major to fix")
            @JsonProperty("nothing_major")
            boolean nothingMajor) {}

    /** The focus check of attempt 2 or 3 (03 F-033). */
    @Schema(name = "ReportFocusCheck")
    public record FocusCheck(
            @JsonProperty("fix_id") String fixId,
            FocusResultWire result,
            Evidence evidence,

            @Nullable
            @Schema(nullable = true, description = "The step's earlier label, for a fix about one step")
            @JsonProperty("previous_status")
            StepStatusWire previousStatus) {}

    /** Done, Better or Not yet. */
    public enum FocusResultWire {
        DONE,
        BETTER,
        NOT_YET;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What was measured before and after (C-38), in whole numbers. */
    @Schema(name = "ReportEvidence")
    public record Evidence(MeasureWire measure, int before, int after) {}

    /** What a focus check measured. */
    public enum MeasureWire {
        SPEECH_MS,
        RELEVANCE_LEVEL,
        STEP_STATUS,
        LONGEST_PAUSE_MS,
        FILLERS_PER_MINUTE,
        FIRST_WORD_MS,
        LONGEST_SENTENCE_WORDS,
        WINDOWS_IN_BAND_PERCENT,
        LANGUAGE_LEVEL;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether another attempt on this topic is offered (03 F-047). */
    @Schema(name = "ReportNextAttempt")
    public record NextAttempt(
            boolean allowed,

            @Schema(allowableValues = {"second_attempt", "last_try", "focus_done", "attempt_cap", "focus_unknown"})
            String reason) {}

    /** The fix this take leaves for the first take of the next topic (03 F-034). */
    @Schema(name = "ReportNextFocus")
    public record NextFocus(@JsonProperty("fix_code") String fixCode) {}

    /**
     * The fix this take started with, carried from the last topic (03 F-034; 02b F-039: the strip "STILL YOUR FOCUS"
     * with "Not today"). Kept on the report, so the Result draws it again on a later reopen. Labels only, no word of
     * the take.
     */
    @Schema(name = "ReportCarriedFocus")
    public record CarriedFocus(
            @JsonProperty("fix_code") String fixCode,

            @Schema(description = "The Fix Library label (C-70)")
            String label,

            @Schema(description = "The session (topic) it came from; send it with Not today")
            @JsonProperty("from_session_id")
            UUID fromSessionId) {}

    /** The strongest line (03 F-035). */
    @Schema(name = "ReportStrongestLine")
    public record StrongestLine(
            @JsonProperty("sentence_id") int sentenceId,
            String quote,
            int t0,
            int t1,

            @Nullable @Schema(nullable = true, description = "The model's reason")
            String why) {}

    /** A moment on the take map. */
    @Schema(name = "ReportMoment")
    public record Moment(
            String id,
            MomentKindWire kind,
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("end_ms") int endMs) {}

    /** What a moment marks. */
    public enum MomentKindWire {
        PAUSE,
        FILLER_CLUSTER,
        ENDING;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A caution strip on a scored take (F-039). */
    public enum Caution {
        SHORT_TAKE_CAUTION,
        LOW_AUDIO;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why a take has no score, with the numbers the no-score screen shows. */
    @Schema(name = "ReportNoScore")
    public record NoScore(
            Status reason,
            int words,
            @JsonProperty("duration_ms") int durationMs,
            @JsonProperty("counted_as_attempt") boolean countedAsAttempt) {}
}
