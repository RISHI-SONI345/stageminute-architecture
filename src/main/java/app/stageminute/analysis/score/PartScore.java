package app.stageminute.analysis.score;

/**
 * One part of the score as the report shows it (03 F-026 `score.parts[{id, points, max, band}]`).
 *
 * @param id `structure`, `language` or `delivery`
 * @param points whole points earned
 * @param max points the part can earn
 * @param band the part's band
 */
public record PartScore(String id, int points, int max, Band band) {}
