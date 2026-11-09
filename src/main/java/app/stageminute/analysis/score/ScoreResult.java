package app.stageminute.analysis.score;

import java.util.List;

/**
 * The built score (03 F-026 `score {total, overall_band, parts[]}` plus the formula version stored on the report).
 *
 * @param total sum of the rounded parts, 0..100
 * @param overallBand band of the total
 * @param parts structure, language, delivery in that order
 * @param formulaVersion the formula that built it
 */
public record ScoreResult(int total, Band overallBand, List<PartScore> parts, String formulaVersion) {}
