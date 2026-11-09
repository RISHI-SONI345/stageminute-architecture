package app.stageminute.practice.internal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** {@code @Import(FakePlans.class)} on a test: the plan port answers from {@link PlanPortFake}, so a test can make someone Pro. */
@TestConfiguration(proxyBeanMethods = false)
public class FakePlans {

    @Bean
    @Primary
    PlanPortFake planPortFake() {
        return new PlanPortFake();
    }

    /** The AI-cost gate answers from {@link SpendGateFake}, so a test can say "paused" without spending a cent. */
    @Bean
    @Primary
    SpendGateFake spendGateFake() {
        return new SpendGateFake();
    }
}
