package app.stageminute.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one server clock (rule 1, AD-05). Every class takes {@link Clock} by injection;
 * calling {@code Instant.now()} or {@code LocalDate.now()} elsewhere fails ArchitectureTest.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
