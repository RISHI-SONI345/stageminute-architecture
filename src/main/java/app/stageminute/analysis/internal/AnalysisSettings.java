package app.stageminute.analysis.internal;

import app.stageminute.analysis.score.PaceWordSettings;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import org.springframework.stereotype.Component;

/**
 * The analysis limits, read by name from the server settings on every call (root CLAUDE.md "Numbers"), and turned
 * into the plain records the pure {@code analysis.score} code takes. A stored combination the score code refuses falls
 * back to the launch value, so a slip in the settings never stops every analysis.
 */
@Component
public class AnalysisSettings {

    private final Settings settings;

    public AnalysisSettings(Settings settings) {
        this.settings = settings;
    }

    /** The pace word's limits (C-65). */
    public PaceWordSettings paceWord() {
        try {
            return new PaceWordSettings(
                    settings.get(SettingKey.PACE_WORD_STEADY_IN_BAND_PERCENT),
                    settings.get(SettingKey.PACE_WORD_ONE_SIDE_PERCENT));
        } catch (IllegalArgumentException e) {
            return PaceWordSettings.LAUNCH_DEFAULT;
        }
    }

    /** The server's total time for the vendor calls of one analysis, in milliseconds (03 F-122: 45 s). */
    public int vendorBudgetMs() {
        return settings.get(SettingKey.ANALYSIS_BUDGET_MS);
    }

    /** {@code delta.direction} is {@code same} when the total moved fewer points than this (02b F-045). */
    public int deltaSameWithinPoints() {
        return settings.get(SettingKey.DELTA_SAME_WITHIN_POINTS);
    }
}
