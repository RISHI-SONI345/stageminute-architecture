package app.stageminute.platform;

import java.util.Locale;

/**
 * Every number the server uses is read by one of these names (root CLAUDE.md "Numbers"). The value in the
 * constructor is the launch default from {@code requirements/06-ABUSE-AND-COST.md} section 1 (and F-030 for
 * the score weights); it is used when no row exists, so a missing setting never opens a limit.
 */
public enum SettingKey {
    FREE_SESSIONS_PER_DAY(1),
    REWARD_GRANTS_PER_DAY(2),
    PRO_SESSIONS_PER_DAY(15),
    FREE_ANALYSES_PER_DAY(30),
    PRO_ANALYSES_PER_DAY(40),
    ANALYZE_CALLS_PER_MINUTE(6),
    DISPUTES_PER_DAY(5),
    NO_SCORE_TAKES_FREE_PER_DAY(5),
    UNCONFIRMED_GIVE_BACKS_PER_DAY(2),
    EXIT_ADS_PER_DAY(1),
    NEW_ACCOUNTS_PER_DEVICE_30_DAYS(3),
    NEW_ACCOUNTS_PER_IP_PER_DAY(5),
    UPLOAD_MAX_BYTES(8 * 1024 * 1024),
    SOFT_SHEET_INTERVAL_DAYS(3),
    SOFT_SHEET_PAUSE_DAYS(14),
    SOFT_SHEET_DISMISSALS_BEFORE_PAUSE(2),
    TIMEZONE_CHANGE_INTERVAL_DAYS(7),
    IDEMPOTENCY_TTL_MINUTES(10),
    RESERVATION_TIMEOUT_HOURS(48),
    SCORE_WEIGHT_STRUCTURE(45),
    SCORE_WEIGHT_LANGUAGE(35),
    SCORE_WEIGHT_DELIVERY(20),
    TOPICS_RAMP_SESSIONS(35),
    TOPICS_MIN_PER_CELL(20),
    TOPICS_EXCLUDE_MAX(20),
    TOPICS_RECENT_SESSIONS(20),
    // The pace word (C-65, AD-126): no points, never in the total.
    PACE_WORD_STEADY_IN_BAND_PERCENT(80),
    PACE_WORD_ONE_SIDE_PERCENT(75),
    // delta.direction is "same" when the total moved fewer points than this (02b F-045, the noise band).
    DELTA_SAME_WITHIN_POINTS(7),
    // POST /v1/analyze (#15.3, AD-129): an analysis mark older than this is a crashed run (F-122 budget is 45 s);
    // the server accepts a take up to the topic's time plus this margin (C-60); a gap between the phone's length
    // and the measured one larger than this is logged as an event with the numbers (C-69).
    ANALYSIS_STALE_SECONDS(120),
    ANALYZE_CAP_MARGIN_MS(3000),
    DURATION_MISMATCH_LOG_MS(2000),
    // GET /v1/analyses shows, and GET /v1/analyses/{id} opens, reports of the last this many days (03 F-057).
    REPORT_WINDOW_DAYS(7),
    // The carried focus is ignored this many days after it was set (03 F-034).
    CARRY_FIX_MAX_AGE_DAYS(7),
    // POST /v1/reports/{id}/dispute (#17.1, AD-147): the note is at most this many characters (02b F-042; the column
    // is varchar(300), so a larger value needs a migration first); a dispute's note and transcript are kept this many
    // days (05 section 5, D-18); a sent transcript longer than this is refused (a 90-second take is far shorter).
    DISPUTE_NOTE_MAX_CHARS(300),
    DISPUTE_RETENTION_DAYS(90),
    DISPUTE_TRANSCRIPT_MAX_CHARS(10_000),
    // Background jobs (03 F-094, #18.1, AD-149): a job or event listener that has not finished after this many hours is
    // an alert (proposed 6); a listener tried more often than this has failed for a reason that will not mend itself.
    JOB_ALERT_HOURS(6),
    EVENT_RETRY_ALERT_ATTEMPTS(10),
    // The real vendors (#19.1, AD-151; 03 F-091, F-122). Server total budget for one analysis, 45 s; a retry runs only
    // when at least this much of it is left (proposed); a breaker opens when this share of the last calls failed
    // (proposed values) and tries again after the open time; a bulkhead caps concurrent calls per vendor (07 F-117: 8).
    ANALYSIS_BUDGET_MS(45_000),
    VENDOR_RETRY_MIN_REMAINING_MS(10_000),
    VENDOR_BREAKER_FAILURE_PERCENT(50),
    VENDOR_BREAKER_WINDOW_CALLS(20),
    VENDOR_BREAKER_MIN_CALLS(10),
    VENDOR_BREAKER_OPEN_SECONDS(30),
    VENDOR_BREAKER_HALF_OPEN_CALLS(3),
    VENDOR_BULKHEAD_MAX_CALLS(8),
    // The AI-cost meter and throttle (#19.2, AD-152; 06 F-087, F-111, D-58). Budget in whole US dollars a day (D-58:
    // 15);
    // the alert, the free pause and the Pro ceiling drop are shares of it (70, 100, 150); the lowered Pro ceiling is 10
    // (normal 40 is PRO_ANALYSES_PER_DAY). A zero budget pauses everyone: a missing or broken number never opens a
    // limit.
    AI_DAILY_BUDGET_USD(15),
    AI_ALERT_PERCENT(70),
    AI_FREE_THROTTLE_PERCENT(100),
    AI_PRO_THROTTLE_PERCENT(150),
    PRO_THROTTLED_CEILING(10),
    // Proposed (not in the requirements, C-85): a vendor call is refused once confirmed plus reserved spend plus the
    // call's worst case would pass this share of the budget. 160 % of USD 15 is USD 24, just under the USD 25 vendor
    // caps.
    AI_CALL_STOP_PERCENT(160),
    // A call that ended with no usage report stays counted this long (proposed 5 minutes).
    AI_EXPOSURE_WINDOW_SECONDS(300),
    // Published prices in millionths of a dollar (checked 2026-11-02, AD-152): Deepgram Nova-3 multilingual (the
    // client turns language detection on) USD 0.0052 a minute; Gemini 3.8 Flash from 2027-01-01 USD 1.50 in and
    // USD 7.50 out per million tokens (USD 0.75 and 3.75 until 2026-12-31: the higher price is kept so the meter
    // never counts less than the bill).
    DEEPGRAM_MICROS_PER_AUDIO_MINUTE(5_200),
    GEMINI_INPUT_MICROS_PER_MILLION_TOKENS(1_500_000),
    GEMINI_OUTPUT_MICROS_PER_MILLION_TOKENS(7_500_000),
    // What one Gemini call can cost at most, held back before the call (proposed): the input is a 90 s take's
    // sentences (a few thousand tokens), the output is the 8192-token limit the client sets (thinking included).
    GEMINI_WORST_CASE_INPUT_TOKENS(8_000),
    GEMINI_WORST_CASE_OUTPUT_TOKENS(8_192),
    // How many tokens Gemini may spend thinking before it answers (proposed, #19.3, AD-154, C-87). Measured on 5 reel
    // clips x 5 runs: 512 gave p50 7.7 s, score SD 0.00, USD 0.0100 an analysis; 1024 gave score SD 3.13 (over the
    // line); the model's own default gave p50 22.8 s, SD 3.29, USD 0.0252. 0 means "not set": the request carries
    // no thinking setting and the model's own default applies. A real budget of 0 (thinking off) is not offered:
    // the 2026-10 spike showed it makes the answer longer.
    GEMINI_THINKING_BUDGET_TOKENS(512),
    // Retries over calls above this percent (F-122: proposed 2), once at least this many calls were counted, is a
    // warning.
    AI_RETRY_ALERT_PERCENT(2),
    AI_RETRY_ALERT_MIN_CALLS(50),
    // Your patterns (03 F-056, #20.1, AD-155): the last 5 first takes of a style; a criterion is a Strength when it is
    // strong in 4 or more of them; a step is an Opportunity when it is weak or missing in 3 or more of them.
    PATTERN_WINDOW_TAKES(5),
    PATTERN_STRONG_MIN(4),
    PATTERN_OPPORTUNITY_MIN(3),
    // failed_start_log is read only by the outage freeze; rows older than this many days are removed (03 section 3).
    FAILED_START_RETENTION_DAYS(90);

    private final int launchDefault;

    SettingKey(int launchDefault) {
        this.launchDefault = launchDefault;
    }

    /** The row key in {@code app_settings}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int launchDefault() {
        return launchDefault;
    }
}
