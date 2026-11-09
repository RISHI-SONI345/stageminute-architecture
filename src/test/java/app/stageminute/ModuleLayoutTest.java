package app.stageminute;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Module layout (server/CLAUDE.md "Module layout"). Spring Modulith checks who may use what; these checks keep
 * the inside of a module in shape so that its root package stays a small, deliberate interface.
 */
class ModuleLayoutTest {

    /** The business modules. {@code platform} is the shared kernel and is not held to the size limit. */
    private static final List<String> MODULES = List.of(
            "account", "identity", "me", "topics", "practice", "analysis", "reports", "progress", "money", "admin");

    /** More public types than this in one root package means working classes belong in {@code internal}. */
    private static final int MAX_PUBLIC_TYPES_IN_ROOT = 10;

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("app.stageminute");

    @Test
    void repositoriesLiveInAnInternalPackage() {
        classes()
                .that()
                .haveSimpleNameEndingWith("Repository")
                .and()
                .resideInAPackage("app.stageminute..")
                .and()
                .resideOutsideOfPackage("app.stageminute.platform..")
                .should()
                .resideInAPackage("..internal..")
                .because("SQL is hidden behind the module's service; other modules reach it by the root types only")
                .check(CLASSES);
    }

    @Test
    void aModuleRootKeepsAShortInterface() {
        for (String module : MODULES) {
            String root = "app.stageminute." + module;
            List<String> publicTypes = CLASSES.stream()
                    .filter(c -> c.getPackageName().equals(root))
                    .filter(c -> !c.isNestedClass() && !c.isAnonymousClass())
                    .filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
                    .map(JavaClass::getSimpleName)
                    .filter(name -> !name.equals("package-info"))
                    .sorted()
                    .toList();
            assertThat(publicTypes)
                    .as("public types in the root of module '%s' (move working classes to %s.internal)", module, root)
                    .hasSizeLessThanOrEqualTo(MAX_PUBLIC_TYPES_IN_ROOT);
        }
    }

    /**
     * ARCHITECTURE.md is the map a new teammate starts from. Every module, and the shared kernel, must appear in its
     * server code map, and must say in its {@code package-info.java} which type to open first.
     */
    @Test
    void everyModuleIsOnTheMapAndSaysWhereToStart() throws IOException {
        String map = Files.readString(Path.of("..", "ARCHITECTURE.md"));
        Path sources = Path.of("src", "main", "java", "app", "stageminute");
        for (String module : allModules()) {
            assertThat(map)
                    .as("ARCHITECTURE.md server code map has a row for module '%s' (Start with, Never)", module)
                    .contains("| `" + module + "` |");
            String info = Files.readString(sources.resolve(module).resolve("package-info.java"));
            assertThat(info)
                    .as("package-info.java of module '%s' needs a 'Start here:' line naming its entry types", module)
                    .contains("Start here:");
        }
    }

    /** Every package under app.stageminute that holds classes is a module in the lists above (no unmapped folder). */
    @Test
    void noModuleIsMissingFromTheLists() {
        List<String> found = CLASSES.stream()
                .map(JavaClass::getPackageName)
                .filter(name -> name.startsWith("app.stageminute."))
                .map(name -> name.substring("app.stageminute.".length()))
                .map(rest -> rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest)
                .distinct()
                .sorted()
                .toList();
        assertThat(allModules()).containsAll(found);
    }

    private static List<String> allModules() {
        return Stream.concat(MODULES.stream(), Stream.of("platform")).toList();
    }
}
