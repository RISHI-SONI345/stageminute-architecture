# Server classes per module (class-level design)

The level below `02-SERVER-LLD.md`. **Public** = in the module's root package (other modules may use it). **Internal** = sub-packages (`internal`, `web`, `jobs`, `vendor`), hidden by Spring Modulith. Signatures are Java 21 sketches: records for data, sealed interfaces for outcomes, no framework types in pure code. Tickets may refine names; changing a public type's meaning needs a row in `DECISIONS.md`.

Conventions: ids are `UUID` (v7, AD-14) wrapped in small records (`UserId`, `SessionId`, `ReportId`) so they cannot be swapped by mistake; outcomes are sealed (`Ok | Refused(reason)`) instead of exceptions for expected business results; exceptions only for bugs and infrastructure failures, mapped to problem details at the edge.

## platform

| Class | Kind | Role |
|---|---|---|
| `UserId`, `RequestContext` | public record | opaque user id; request id + user id + device hash for logs |
| `Reason` | public enum | every reason code (06-API §1) |
| `BusinessRefusal` | public exception | carries a `Reason` + extra fields; mapped to RFC 9457 |
| `ProblemDetailsAdvice` | internal `@RestControllerAdvice` | refusal/validation → problem details; never echoes bodies |
| `Settings` | public interface | `int intValue(SettingKey)`, `<T> T json(SettingKey, Class<T>)`; `SettingKey` enum lists every number from `06` §1 with its launch default |
| `Flags` | public interface | `boolean isOn(Flag, UserId)`; unknown/missing → `false` (fail closed) |
| `DbSettings`, `DbFlags` | internal | Postgres-backed, cached 30 s, audited writes |
| `Idempotency` | public interface | `<T> T run(UserId, String key, byte[] requestHash, Supplier<T>, Class<T>)`; replays a stored result, rejects a different body under the same key (`409`) |
| `RateLimiter` | public interface | `void check(String bucket, int perMinute)` → `BusinessRefusal(rate_limited)` |
| `SingleRunJob` | public annotation | wraps `@Scheduled` with the `job_locks` lease |
| `TimeConfig` | internal | the `Clock` bean (AD-05) |
| `DailyCaps` | public port (built #17.1) | `tryCount(user, day, Counter, cap)`: a daily cap a module counts without owning `daily_counters`; `practice.internal.DailyCapsAdapter` answers it (AD-147) |

## identity (Clerk, AD-53)

| Class | Kind | Role |
|---|---|---|
| `CurrentUser` | public | `UserId require()`: verified Clerk `sub` → our user; the first call creates the row (unique key, parallel-safe) with velocity limits |
| `ClerkUsers` | public port | `void delete(String clerkUserId)`; adapters `ClerkBackendClient` (REST or `clerk-sdk-java`) and `FakeClerkUsers` |
| `ClerkJwtConfig` | internal | Spring Security resource server: JWKS URI, `iss` and `azp` validators |
| `ClerkWebhookController` | internal web | `POST /webhooks/clerk`, Svix signature check, webhook inbox |
| `ClerkDeletionListener` | internal | on `AccountDeletionRequested` → `ClerkUsers.delete` with retries (outbox) |

## account

| Class | Kind | Role |
|---|---|---|
| `AccountCalendar` | public | `LocalDate today(UserId)`, `Instant resetsAt(UserId)`, `LocalDate dayOf(UserId, Instant)` (the one day calculator) |
| `Profile`, `ProfileService` | public | read/patch profile; weekly zone-change rule |
| `AccountService`, `NewAccountContext` | public | `find`, `resolve` (first-call provisioning with the new-account limits, F-086), `updateEmail`, `deleteByClerkUserId`, `purgeOldAddressCounts` (ticket #8) |
| `ProfileChange` | public record | what a patch may change (level, style, time zone, name) |
| `ConsentService`, `ConsentState` | public | `state`, `record`, `require` (ticket #8; replaces the `Consent` row below) |
| `AccountRepository`, `ProfileRepository`, `ConsentRepository` | internal | the SQL only; no rule (AD-93) |
| `ConsentController` | internal web | `PUT /v1/me/consent` |
| `Consent` | public | `void require(UserId)` (throws `consent_off`), `void record(UserId, version, granted, usageData)` |
| `Deletion` | public | `void delete(UserId)`: publishes `Deletion.Requested`, writes `deleted_users`, deletes the user row, in one transaction (built #18.1, AD-149); `deleteByClerkUserId` (Clerk webhook); `wasDeleted(UUID)` |
| `Deletion.Requested` (was `AccountDeletionRequested`; `AccountDeleted` is not built) | public event record, nested in `Deletion` | carries only what listeners need (our id, Clerk user id, RevenueCat id) |
| `SeenMarkers` | public | `void mark(UserId, String)`, `Set<String> all(UserId)` |

## me (composition, AD-42)

| Class | Kind | Role |
|---|---|---|
| `MeController` | internal web | `GET /me`: asks `account`, `practice`, `money`, `progress`, `reports` (next focus) and `platform.Flags` through their public types and assembles the snapshot (06-API §3); holds no rule and owns no table |
| `MeSnapshotAssembler` | internal | maps the module views into the response record |

## topics

| Class | Kind | Role |
|---|---|---|
| `Topic` | public record | id, style, level, difficulty, text, roleContext, thinkS, speakS, seed, retired |
| `Style`, `Level` | public enums | `wire()` gives the word used in files and in the API (`prep`, `upper_intermediate`) |
| `TopicSettings` | public record | ramp length (35) and cell minimum (20); `LAUNCH_DEFAULT`; filled from `platform.Settings` at startup |
| `TopicCatalog` | public | `List<Topic> pool(Style, Level)`, `Optional<Topic> pick(Style, Level, int completedSessions, List<String> exclude, RandomGenerator)` (pure, `exclude` oldest first, AD-102 and AD-104), `Optional<Topic> get(String)` (retired included), `String version()` |
| `TopicFileLoader` | internal | `load(files, idsJson, schemaJson, settings, enforceMin)`: checks the files against the shared schema and the cross-file rules, throws `TopicFilesInvalidException` |
| `TopicFilesInvalidException` | internal | every problem, one line each, each starting with its code |
| `TopicsConfig` | internal | reads the files Gradle copied into the jar (`content-topics/`) and builds the `TopicCatalog` bean at startup |
| `TopicPicker`, `DifficultyRamp` | internal, pure | the pick and the target difficulty (1 to 5 over about 35 sessions) |
| `TopicsController` | `web/` | `GET /v1/topics/pool?style&level`, ETag and `304` |

## practice

| Class | Kind | Role |
|---|---|---|
| `QuotaState` | public record | state (`OK | QUOTA_ZERO | PRO_CAP | THROTTLED`), sessionsLeft, adRewardsToday, analysesLeft, firstSessionDone, resetsAt, openSession |
| `Practice` | public (the deep module) | `QuotaState state(UserId)`; `ReserveResult reserve(UserId, TakeKey, String topicId, DeviceHash)`; `AttemptResult beginAttempt(SessionId, TakeKey)`; `void finishAttempt(SessionId, AttemptOutcome)`; `void release(SessionId, ReleaseReason)` |
| `ReserveResult`, `AttemptResult` | public sealed | `Reserved(sessionId, attempt) | Refused(Reason, extras)` |
| `AttemptOutcome` | public sealed | `Scored(focusResult) | NoScore(kind) | Failed(confirmedByServer)` |
| `SessionCommitted`, `SessionReleased`, `StartRefused` | public events | for `progress` and analytics |
| `AttemptRule` | internal, pure | `NextAttempt next(int attemptsUsed, FocusResult last)`: 2 attempts, a 3rd only after Better/Not yet (D-15) |
| `AllowanceRule` | internal, pure | sessions allowed today from plan + ad credits (F-049) |
| `DailyCounters` | internal repository | the atomic `UPDATE ... WHERE x < cap RETURNING` statements (AD-11) |
| `QuotaLedger` | internal repository | append-only `quota_events` |
| `DeviceFlags` | internal | device-aware first session and account velocity (F-086) |
| `ReservationTimeoutJob` | internal job | releases reservations older than 48 h |
| `SessionsController` | internal web | `/sessions` endpoints |

## analysis

| Class | Kind | Role |
|---|---|---|
| `Analyzer` | public (built #15.3) | `AnalysisOutcome analyze(AnalyzeCommand)`: consent, size, idempotency replay, then `AnalysisRun` (AD-129) |
| `AnalysisRun` | internal (built #15.3) | measures the take, `Practice.beginAnalysis` (C-52), the cap and margin (C-60), the pipeline, then save, attempt and stored answer in one transaction; give-back on failure |
| `M4aAudio` | internal (built #15.3) | the length from the MP4 boxes (`moov/mvhd`); empty for anything else (415) |
| `SpeechDataCache` | internal (built #15.3) | the words for the retry window, in memory, bounded |
| `FactsMapping` | internal (built #15.3) | `TakeFacts` to and from `reports.FactsRecord`; the C-43 baseline |
| `AnalyzeCommand` | public record (built #15.3) | user, take key, session, audio bytes, the phone's length (a hint), device id; `toString` shows the byte count only |
| `AnalysisOutcome` | public record (built #15.3) | `report` and `speech_data` (null on a replay after the words expired); a refusal is a `BusinessRefusal` |
| `AnalysisPipeline` | internal | orchestrates: consent → idempotency → beginAttempt → STT → metrics → gate → judge → build → save → finishAttempt |
| `SpeechToText` | internal port | `Transcript transcribe(Path audio, Duration budget)`; `DeepgramClient` (asserts `mip_opt_out=true`), `FakeSpeechToText` |
| `Judge` | internal port | `Labels label(JudgeInput)`; `GeminiJudge` (one call, enum schema, one retry on invalid JSON), `FakeJudge` |
| `AudioTempFiles` | internal | write to temp, delete in `finally`, clean the folder at startup (F-081) |
| `spend.SpendMeter` | built #19.2 | the usage seam (`UsageSink`) and the gate (`platform.SpendGate`): `starting` reserves the worst case of a call under one lock and may refuse (`SpendRefused`); `used` turns it into confirmed spend in `ai_spend_daily`; `noUsageReported` keeps it as exposure for 5 minutes; `decide(Tier)` is the throttle question; `today()` is the read for the trust harness and the admin console (AD-152) |
| `spend.ThrottlePolicy`, `spend.SpendPrices` | pure (built #19.2) | the decision (ALLOW, FREE_PAUSED, PRO_REDUCED) from confirmed plus reserved micros, the budget and the plan; units to cost micros, rounded up |
| `platform.SpendGate`, `platform.SpendDecision` | port in the shared kernel (built #19.2) | what `practice` asks before it reserves a session; `analysis.spend.SpendMeter` answers |
| `SpeechDataCache` | internal | in-memory, 10-minute life, never serialized |
| `score.MetricsV2` | pure | `Facts compute(Transcript, MetricsConfig)` |
| `score.NoScoreGate` | pure | `Optional<NoScore> check(Facts, Transcript)` |
| `score.LabelValidator` | pure | rejects numbers in model text; drops items on low-confidence words (F-036) |
| `score.ScoreBuilder` | pure | `Score build(Facts, Labels, Style, FormulaConfig)` (45/35/20, `formula_version`) |
| `score.ScoreBands` | pure | bands (built: `ScoreBands`, `BandCutoffs`, `Band`) |
| `score.FixLibrary` | pure (built #14) | `FixSelection choose(TakeFacts current, Optional<PreviousTake> previous, FixSettings)` and `FixChoice firstFix(TakeFacts, FixSettings)`; a `FixChoice` is `(FixId id, int target, boolean carriedOver, boolean nothingMajor)`: `target` is the step (0 to 3) a step fix is about, or -1, and it becomes `fix_first.step` in the report (AD-99, AD-126) |
| `score.FocusCheck` | pure (built #14) | `FocusCheckResult check(PreviousTake, TakeFacts current, FixSettings)`: `result` done, better or not_yet, `evidence {measure, before, after}` (C-38) and `previous_status` for a fix about one step |
| `score.StrongestLine`, `score.Moments` | pure | sentence choice and up to 3 moments |
| `score.PaceWords`, `PaceWord`, `PaceWordSettings` | pure (built #15.1) | the pace word from the windows and the take's band (C-65, AD-126); no points |
| `score.ScoreDelta` | pure (built #15.1) | `delta.direction`: up, down, or same inside the noise band |
| `Pipeline` | internal (built #15.2) | `Result run(Input)`: speech to text, metrics, gate, the one call, label checks, score, fix, focus, moments, strongest line, pace word, delta; no database, clock or user (AD-128) |
| `vendor.SpeechToText`, `vendor.Judge` | ports (built #15.2) | `SpeechResult transcribe(byte[], durationMs, SttRequest)`; `JudgeLabels judge(JudgeInput)`, `JudgeVersions versions()` |
| `vendor.FakeSpeechToText`, `vendor.FakeJudge` | fakes (built #15.2) | deterministic per scenario; the STT fake records each `SttRequest` |
| `vendor.VendorConfig` | internal (built #15.2) | fakes only with `stageminute.analysis.fake-vendors=true`, never in prod or staging; otherwise every call fails |
| `SpeechDataView` | public record (built #15.2) | `speech_data` on the wire; `toString` is `[redacted]` |
| `AnalysisSettings` | internal (built #15.1) | reads the analysis limits by name from `Settings`; refused combinations fall back to the launch values |
| `AnalyzeController` | internal web | multipart, size/format checks, maps outcome to `200` / problem |

## reports

| Class | Kind | Role |
|---|---|---|
| `Reports` | public | `ReportId save(ReportDraft)`, `List<ReportSummary> recent(UserId, Duration)`, `Optional<ReportView> get(UserId, ReportId)`, `void delete(UserId, ReportId)`, `FactsSnapshot previousFacts(SessionId, int attempt)`, `Optional<NextFocus> nextFocus(UserId)` |
| `Disputes` | public (built #17.1) | `send(user, reportId, DisputeRequest)`: the 7-day read, the row, the count through `platform.DailyCaps` (5 a day), `dispute_state = sent`; never writes a score (AD-147) |
| `DisputeArea`, `DisputeRequest` | public enum and record (built #17.1) | the closed area list and the body; a step or suggestion is an `index`, never words |
| `DisputesRepository` | internal (built #17.1) | SQL of `score_disputes` and `reports.dispute_state` |
| `DisputesController` | internal web (built #17.1) | `POST /v1/reports/{analysisId}/dispute` (`202`) |
| `Patterns` | public | `PatternsView of(UserId, Style)` from the last 5 first takes |
| `ReportV2` | public record (built #15.1) | the Report v2 wire shape (06-API §9), nested records and enums; no text under `facts` or `findings` |
| `Reports` | public (built #15.3, #15.4, #17.1) | `delete(user, id)` at any age (cascades, drops the stored `/analyze` answer, AD-147); `recent(user)` and `get(user, id)` inside `report_window_days`; `save(user, ReportV2, FactsRecord, topicId, at)` in the caller's transaction (scored reports only); `attempts(user, session)`; `recentAverageWpm(user, n)` |
| `FactsRecord` | public record (built #15.3) | the numbers and labels of one scored take (D-11) |
| `ReportsRepository` | internal (built #15.3) | SQL of `reports` and `facts_records` |
| `ReportSummary` | public record (built #15.4) | one row of `GET /v1/analyses` (ids and numbers; `topic_id`, no topic text, AD-130) |
| `AnalysesController` | internal web (built #15.4, #17.1) | `GET /v1/analyses?window=7d`, `GET /v1/analyses/{id}` (404 after 7 days or for another person), `DELETE /v1/analyses/{id}` (204 for every id) |
| `ReportJson` | internal (built #15.1) | the one serializer of `ReportV2` (stored text and answer) |
| `ReportOpenApi` | internal (built #15.1) | adds the `Report` schemas to the OpenAPI file |
| `FixTexts` | public (built #16.2) | the Fix Library texts by fix id (`text(code)`, `codes()`): label of 22 characters or fewer and How line from `content/fixes/en.json`; passes them through as written (C-70) |
| `CarriedFocusService` | public (built #16.3) | the carried focus (F-034, AD-138): `forNextTopic(user)` for `/me`, `dismiss(user, fromSession)`; `Reports.save` calls `carryInto` (takes the row for the first scored take of another session, sets `carried_focus` and `next_focus`) and `remember` (writes or removes the row); listens to `account.StyleChanged`; flag `carry_fix_enabled` off: does nothing |
| `NextFocusRepository` | internal (built #16.3) | SQL of `next_focus` (one row per person, locked while a report is saved) |
| `NextFocusController` | internal web (built #16.3) | `POST /v1/me/next-focus/dismiss` (`204`, safe to repeat) |
| `FixTextsConfig` | internal (built #16.2) | reads the file at start from `stageminute.content.fixes-path` (default `classpath:content-fixes/en.json`, copied into the jar by Gradle); blank, missing or bad stops the start |
| `DisputePurgeJob` | internal job | removes dispute transcripts at 90 days |

## progress

| Class | Kind | Role |
|---|---|---|
| `Progress` | public | `StreakView streak(UserId)`, `Totals totals(UserId)` |
| `StreakCalculator` | internal, pure | `StreakView compute(SortedSet<LocalDate> qualifying, Set<LocalDate> frozen, LocalDate today, int storedBest)` |
| `FreezeRecorder` | internal listener | on `StartRefused(throttled)` and logged 5xx in an outage window → `streak_freezes` (D-45, D-78) |
| `OutageWindows` | public (admin uses) | mark a window; freezes affected users |

## money

| Class | Kind | Role |
|---|---|---|
| `Plans` | public | `Plan plan(UserId)` (tier, trial, expiry, state) |
| `AdPolicy` | public | `boolean exitAdAllowed(UserId, ExitContext)`, `boolean rewardOfferAllowed(UserId)` |
| `RewardTokens` | public | `PreparedReward prepare(UserId)` |
| `SoftSheet` | public | `boolean eligible(UserId)`, `shown`, `dismissed` |
| `Entitlements` | public | `Plan sync(UserId)` |
| `EntitlementChanged` | public event | |
| `RevenueCatClient` | internal port | `Subscriber get(String appUserId)`, `void delete(String appUserId)`; adapter + fake |
| `RevenueCatWebhookHandler` | internal | dedupe, order by event time, user lookup, apply |
| `AdMobSsvVerifier` | internal | key fetch (cache ≤ 24 h), ECDSA over the query minus `signature`/`key_id`, timestamp window, reverse DNS |
| `RewardGranter` | internal | `INSERT ... ON CONFLICT DO NOTHING` + capped counter in one transaction |
| `EntitlementReconcileJob` | internal job | daily pull of recently changed subscribers |
| `WebhookInbox` | internal | the shared inbox steps (verify → dedupe → handle → outcome) used by the three handlers |

## admin

| Class | Kind | Role |
|---|---|---|
| `AdminSecurityConfig` | internal | separate chain on `/admin/**`: Google sign-in with allow-list + second factor (AD-39) |
| `AdminPagesController` | internal | Thymeleaf pages: user lookup, sessions, streak restore, Pro grant, give-back, disputes, abuse list, jobs, flags, export, outage windows |
| `AdminAudit` | internal | every action written before it runs |

## Cross-module events (Spring Modulith, AD-07)

| Event | Published by | Listened by |
|---|---|---|
| `AccountDeletionRequested` | account | identity (Apple revoke), money (RevenueCat, Superwall delete), platform analytics (PostHog delete) |
| `SessionCommitted` / `SessionReleased` | practice | progress (totals), analytics |
| `StartRefused(reason)` | practice | progress (throttle freeze) |
| `EntitlementChanged` | money | analytics; practice cache invalidation |
| `UserSignedIn` | identity | account (`last_sign_in_at`) |
