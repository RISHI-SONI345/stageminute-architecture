package app.stageminute;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.DependencyType;
import org.springframework.modulith.docs.Documenter;

/** The walls between modules (AD-01, AD-02). Fails on cycles, internal access or a dependency not allowed in package-info. */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(StageMinuteApplication.class);

    @Test
    void modulesRespectTheirWalls() {
        MODULES.verify();
    }

    /** AD-42: `me` only assembles GET /me from five modules, and nothing depends on it. */
    @Test
    void meIsAComposerOfFiveModulesAndNothingUsesIt() {
        ApplicationModule me = MODULES.getModuleByName("me").orElseThrow();

        Set<String> uses = me.getDirectDependencies(MODULES, DependencyType.values())
                .uniqueModules()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(uses).isSubsetOf("account", "practice", "money", "progress", "reports", "platform");

        boolean someoneUsesMe = MODULES.stream()
                .filter(module -> !module.equals(me))
                .anyMatch(module -> module.getDirectDependencies(MODULES, DependencyType.values())
                        .contains(me));
        assertThat(someoneUsesMe).isFalse();
    }

    /**
     * The module diagram and canvases in {@code docs/architecture/generated} are written from the real code and must
     * equal it (ARCHITECTURE.md, "The rule"). After a change, run with {@code UPDATE_ARCH_DOCS=true} and commit.
     */
    @Test
    void generatedPicturesMatchTheCode() throws IOException {
        Path fresh = Files.createTempDirectory("modulith-docs");
        new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(fresh.toString())).writeDocumentation();

        if ("true".equals(System.getenv("UPDATE_ARCH_DOCS"))) {
            Files.createDirectories(COMMITTED);
            for (String name : names(COMMITTED)) {
                if (!names(fresh).contains(name)) {
                    Files.delete(COMMITTED.resolve(name));
                }
            }
            for (String name : names(fresh)) {
                // Keep a file as it is when only the line order differs, so a rerun gives no noisy diff.
                if (!Files.exists(COMMITTED.resolve(name))
                        || !sameLines(COMMITTED.resolve(name), fresh.resolve(name))) {
                    Files.writeString(COMMITTED.resolve(name), text(fresh.resolve(name)));
                }
            }
        }

        assertThat(names(COMMITTED))
                .as("files in docs/architecture/generated (run with UPDATE_ARCH_DOCS=true and commit)")
                .isEqualTo(names(fresh));
        for (String name : names(fresh)) {
            assertThat(sameLines(COMMITTED.resolve(name), fresh.resolve(name)))
                    .as(
                            "docs/architecture/generated/%s is out of date (run with UPDATE_ARCH_DOCS=true and commit)",
                            name)
                    .isTrue();
        }
    }

    private static final Path COMMITTED = Path.of("..", "docs", "architecture", "generated");

    private static List<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    /** The generator may list the same lines in another order from run to run; order and line endings do not count. */
    private static boolean sameLines(Path a, Path b) throws IOException {
        return lines(a).equals(lines(b));
    }

    private static List<String> lines(Path file) throws IOException {
        return text(file).lines().sorted().toList();
    }

    /** Line endings do not count as a difference. */
    private static String text(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
