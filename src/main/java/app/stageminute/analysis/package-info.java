/**
 * The analysis pipeline: speech to text, one labels-only AI call, score, fixes.
 * <p>Start here: {@code score.ScoreBuilder}, {@code score.NoScoreGate}, {@code score.MetricsV2}, {@code score.PersonalPaceBand}, {@code score.LabelValidator}, {@code score.ScoreBands}, {@code score.FixLibrary}, {@code score.FocusCheck}, {@code score.FillerTally}, {@code score.StrongestLine}, {@code score.Moments}, {@code score.PaceWords}, {@code score.ScoreDelta}, {@code internal.AnalysisSettings}, {@code internal.Pipeline} (one take to Report v2; refuses to start when a fix id has no text), {@code vendor.SpeechToText}, {@code vendor.Judge}, {@code vendor.VendorConfig} (fails closed), {@code vendor.FakeSpeechToText}, {@code vendor.FakeJudge}, {@code vendor.DeepgramClient}, {@code vendor.GeminiJudge}, {@code vendor.VendorGuards}, {@code vendor.VendorBudget}, {@code vendor.UsageSink}, {@code vendor.SpendRefused}, {@code spend.SpendMeter} (reserved and confirmed AI spend; the {@code UsageSink} of the real clients and the platform {@code SpendGate} that {@code practice} asks), {@code spend.ThrottlePolicy} and {@code spend.SpendPrices} (pure), {@code spend.SpendStore} with {@code internal.SpendRepository} ({@code ai_spend_daily}), {@code SpeechDataView}, {@code Analyzer} ({@code POST /v1/analyze}), {@code AnalyzeCommand}, {@code AnalysisOutcome}, {@code internal.AnalysisRun}, {@code internal.M4aAudio}, {@code internal.SpeechDataCache}, {@code web.AnalyzeController}. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "analysis",
        allowedDependencies = {"practice", "account", "topics", "reports", "platform"})
package app.stageminute.analysis;

import org.springframework.modulith.ApplicationModule;
