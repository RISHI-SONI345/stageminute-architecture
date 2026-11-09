package app.stageminute.analysis.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.stageminute.account.AccountService;
import app.stageminute.analysis.AnalyzeCommand;
import app.stageminute.analysis.vendor.FakeJudge;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.vendor.FakeSpeechToText;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.reports.FixTextsFixtures;
import app.stageminute.reports.Reports;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When the vendors ran but saving the result fails, the take was not delivered: the session is given back as a
 * server-side failure (D-38) and the analysis mark goes, so the person neither loses a session nor waits for the
 * mark to go stale (found by the #15 differential review).
 */
class AnalysisRunTest {

    private static final String TOPIC = "prep-ele-001";

    @Test
    void aSaveThatFailsGivesTheSessionBack() {
        Settings settings = mock(Settings.class);
        for (SettingKey key : SettingKey.values()) {
            when(settings.get(key)).thenReturn(key.launchDefault());
        }
        Practice practice = mock(Practice.class);
        Reports reports = mock(Reports.class);
        UserId user = new UserId(UUID.randomUUID());
        UUID session = UUID.randomUUID();
        when(practice.beginAnalysis(user, session))
                .thenReturn(new Practice.AnalysisStart(session, TOPIC, "prep", 0, false));
        when(reports.attempts(user, session)).thenReturn(List.of());
        when(reports.recentAverageWpm(any(), anyInt())).thenReturn(List.of());
        doThrow(new IllegalStateException("database gone"))
                .when(reports)
                .save(any(), any(), any(), anyString(), any(), any(), any());
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);
        Topic topic = new Topic(TOPIC, Style.PREP, Level.ELEMENTARY, 1, "Topic?", null, 30, 60, true, false);
        AnalysisRun run = new AnalysisRun(
                new Pipeline(
                        new FakeSpeechToText(FakeScenario.OK, 0),
                        new FakeJudge(),
                        new AnalysisSettings(settings),
                        FixTextsFixtures.content()),
                practice,
                reports,
                TopicCatalog.of(List.of(topic), TopicSettings.LAUNCH_DEFAULT),
                settings,
                new SpeechDataCache(settings, clock),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                clock,
                mock(AccountService.class));

        assertThatThrownBy(() -> run.analyze(
                        new AnalyzeCommand(user, "t_1", session, M4aFiles.ofLength(55_000), 55_000, null),
                        answer -> {}))
                .isInstanceOf(IllegalStateException.class);

        verify(practice).giveBack(user, session, Practice.Failure.SERVER_ERROR);
        verify(practice).endAnalysis(user, session);
        verify(practice, never()).recordScoredAttempt(eq(user), eq(session), any(), anyInt());
    }

    @Test
    void aConstraintErrorWhileTheAccountStillExistsIsAServerFailureAndGivesTheSessionBack() {
        Settings settings = mock(Settings.class);
        for (SettingKey key : SettingKey.values()) {
            when(settings.get(key)).thenReturn(key.launchDefault());
        }
        Practice practice = mock(Practice.class);
        Reports reports = mock(Reports.class);
        AccountService accounts = mock(AccountService.class);
        UserId user = new UserId(UUID.randomUUID());
        UUID session = UUID.randomUUID();
        when(practice.beginAnalysis(user, session))
                .thenReturn(new Practice.AnalysisStart(session, TOPIC, "prep", 0, false));
        when(reports.attempts(user, session)).thenReturn(List.of());
        when(reports.recentAverageWpm(any(), anyInt())).thenReturn(List.of());
        when(accounts.exists(user)).thenReturn(true);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"))
                .when(reports)
                .save(any(), any(), any(), anyString(), any(), any(), any());
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);
        Topic topic = new Topic(TOPIC, Style.PREP, Level.ELEMENTARY, 1, "Topic?", null, 30, 60, true, false);
        AnalysisRun run = new AnalysisRun(
                new Pipeline(
                        new FakeSpeechToText(FakeScenario.OK, 0),
                        new FakeJudge(),
                        new AnalysisSettings(settings),
                        FixTextsFixtures.content()),
                practice,
                reports,
                TopicCatalog.of(List.of(topic), TopicSettings.LAUNCH_DEFAULT),
                settings,
                new SpeechDataCache(settings, clock),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                clock,
                accounts);

        assertThatThrownBy(() -> run.analyze(
                        new AnalyzeCommand(user, "t_1", session, M4aFiles.ofLength(55_000), 55_000, null),
                        answer -> {}))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(practice).giveBack(user, session, Practice.Failure.SERVER_ERROR);
    }
}
