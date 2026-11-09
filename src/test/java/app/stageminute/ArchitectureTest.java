package app.stageminute;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPort;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.TopicCatalog;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.random.RandomGenerator;

/** Rules from docs/architecture/02-SERVER-LLD.md §1 that Spring Modulith alone does not check. */
@AnalyzeClasses(packages = "app.stageminute", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The score is pure: it can never see the user's plan, a database, Spring or the clock ("your plan never changes your score"). */
    @ArchTest
    static final ArchRule scoreIsPure = classes()
            .that()
            .resideInAPackage("app.stageminute.analysis.score..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "app.stageminute.analysis.score..")
            .because("the score is a pure function of facts and labels (AD-04, F-030)");

    /**
     * Promise 3 of 11 section 1, "your plan never changes your score", for the AI-cost throttle (#19.2, 03 F-087): the
     * throttle reads the plan to choose a ceiling and nothing else does. The score, the fix choice and the pipeline that
     * builds them never see the tier, the throttle's decision or the plan.
     */
    @ArchTest
    static final ArchRule theScorePathNeverReadsTheTierOrTheThrottle = noClasses()
            .that()
            .haveSimpleNameStartingWith(
                    "Pipeline") // ArchUnit reads and/or left to right: (Pipeline and internal) or score
            .and()
            .resideInAPackage("app.stageminute.analysis.internal")
            .or()
            .resideInAPackage("app.stageminute.analysis.score..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                    SpendGate.class, SpendGate.Tier.class, SpendDecision.class, Plan.class, PlanPort.class))
            .because("a person's plan never changes a score, a band or a fix (11 section 1, promise 3)");

    /** Only {@code practice} asks whether someone may start a session; the meter answers. Nobody else needs the tier. */
    @ArchTest
    static final ArchRule onlyPracticeAsksTheSpendGate = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "app.stageminute.practice..", "app.stageminute.analysis.spend..", "app.stageminute.platform..")
            .should()
            .callMethod(SpendGate.class, "decide", SpendGate.Tier.class)
            .because("the throttle decision is asked once, before a session is reserved (03 F-087)");

    /** One clock: only platform may create the system clock; everyone else injects java.time.Clock (rule 1, AD-05). */
    @ArchTest
    static final ArchRule noHiddenClocks = noClasses()
            .that()
            .resideOutsideOfPackage("app.stageminute.platform..")
            .should()
            .callMethod(Instant.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now")
            .orShould()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(ZonedDateTime.class, "now")
            .orShould()
            .callMethod(OffsetDateTime.class, "now")
            .orShould()
            .callMethod(System.class, "currentTimeMillis")
            .because("the server is the only clock and tests must be able to set it (AD-05)");

    /** The pick needs the person's sessions, which only practice owns: nobody else asks the catalog for "next" (AD-102). */
    @ArchTest
    static final ArchRule onlyPracticePicksTopics = noClasses()
            .that()
            .resideOutsideOfPackage("app.stageminute.practice..")
            .should()
            .callMethod(
                    TopicCatalog.class, "pick", Style.class, Level.class, int.class, List.class, RandomGenerator.class)
            .because("topics cannot read sessions, so practice counts them and calls the pick (AD-102)");

    /** The shared kernel depends on no business module. */
    @ArchTest
    static final ArchRule platformStaysShared = noClasses()
            .that()
            .resideInAPackage("app.stageminute.platform..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "app.stageminute.identity..",
                    "app.stageminute.account..",
                    "app.stageminute.topics..",
                    "app.stageminute.practice..",
                    "app.stageminute.analysis..",
                    "app.stageminute.reports..",
                    "app.stageminute.progress..",
                    "app.stageminute.money..",
                    "app.stageminute.admin..")
            .because("platform is the shared kernel (AD-02)");
}
