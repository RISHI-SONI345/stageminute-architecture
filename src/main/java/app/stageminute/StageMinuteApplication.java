package app.stageminute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * StageMinute server: a modular monolith (docs/architecture/DECISIONS.md AD-01).
 * Each direct sub-package is a module; {@code platform} is the shared kernel.
 */
@SpringBootApplication
@Modulithic(sharedModules = "platform")
public class StageMinuteApplication {

    public static void main(String[] args) {
        SpringApplication.run(StageMinuteApplication.class, args);
    }
}
