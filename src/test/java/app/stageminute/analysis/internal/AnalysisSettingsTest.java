package app.stageminute.analysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.stageminute.analysis.score.PaceWordSettings;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import org.junit.jupiter.api.Test;

/**
 * The analysis reads its limits by name from the server settings (root CLAUDE.md "Numbers"); a value that makes no
 * sense falls back to the launch value instead of stopping every analysis (fail closed: the launch value is the
 * approved one).
 */
class AnalysisSettingsTest {

    private final Settings settings = mock(Settings.class);
    private final AnalysisSettings analysis = new AnalysisSettings(settings);

    @Test
    void thePaceWordLimitsAreReadByName() {
        when(settings.get(SettingKey.PACE_WORD_STEADY_IN_BAND_PERCENT)).thenReturn(90);
        when(settings.get(SettingKey.PACE_WORD_ONE_SIDE_PERCENT)).thenReturn(60);

        assertThat(analysis.paceWord()).isEqualTo(new PaceWordSettings(90, 60));
    }

    @Test
    void aPaceWordLimitThatMakesNoSenseGivesTheLaunchValues() {
        when(settings.get(SettingKey.PACE_WORD_STEADY_IN_BAND_PERCENT)).thenReturn(80);
        when(settings.get(SettingKey.PACE_WORD_ONE_SIDE_PERCENT)).thenReturn(40); // both sides could win

        assertThat(analysis.paceWord()).isEqualTo(PaceWordSettings.LAUNCH_DEFAULT);
    }

    @Test
    void theNoiseBandIsReadByName() {
        when(settings.get(SettingKey.DELTA_SAME_WITHIN_POINTS)).thenReturn(5);

        assertThat(analysis.deltaSameWithinPoints()).isEqualTo(5);
    }
}
