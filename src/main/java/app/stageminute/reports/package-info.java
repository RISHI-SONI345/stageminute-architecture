/**
 * Reports, facts, disputes, patterns, carried focus.
 * <p>Start here: {@code ReportV2} (the Report v2 wire shape), {@code internal.ReportJson} (its one serializer), {@code internal.ReportOpenApi}, {@code Reports} (save, attempts, recent paces), {@code FactsRecord}, {@code internal.ReportsRepository}, {@code web.AnalysesController} (the 7-day list and one report), {@code ReportSummary} (a list row, with {@code can_continue}), {@code Patterns} (F-056: {@code Reports.patterns}, {@code internal.PatternsCalculator}, {@code web.PatternsController}), {@code FixTexts} (the Fix Library texts by fix id), {@code internal.FixTextsConfig}, {@code CarriedFocusService} (the carried focus, F-034), {@code internal.NextFocusRepository}, {@code web.NextFocusController} (Not today), {@code Disputes} (a dispute and the one-tap, F-042, C-67), {@code DisputeArea}, {@code DisputeRequest}, {@code internal.DisputesRepository}, {@code web.DisputesController}. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "reports",
        allowedDependencies = {"account", "platform"})
package app.stageminute.reports;

import org.springframework.modulith.ApplicationModule;
