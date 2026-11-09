package app.stageminute.analysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.spend.SpendStore;
import app.stageminute.support.Concurrently;
import app.stageminute.support.DatabaseTest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The confirmed daily counter in Postgres (03 F-087, F-088): one row per UTC date, added to by one atomic upsert, so a
 * restart keeps it and two callers at the same moment lose nothing.
 */
class SpendRepositoryTest extends DatabaseTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-02");

    @Autowired
    SpendRepository repository;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM ai_spend_daily").update();
    }

    @Test
    void aDayWithNoRowReadsAsZero() {
        assertThat(repository.read(DAY)).isEqualTo(new SpendStore.Day(0, 0));
    }

    @Test
    void eachAddedCallRaisesTheMicrosAndTheCallsOfItsOwnUtcDate() {
        repository.add(DAY, 4_650);
        repository.add(DAY, 350);
        repository.add(DAY.plusDays(1), 10);

        assertThat(repository.read(DAY)).isEqualTo(new SpendStore.Day(5_000, 2));
        assertThat(repository.read(DAY.plusDays(1))).isEqualTo(new SpendStore.Day(10, 1));
        assertThat(jdbc.sql("SELECT count(*) FROM ai_spend_daily")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void manyCallersAddingAtTheSameMomentLoseNothing() {
        Concurrently.Outcome<Void> outcome = Concurrently.run(24, () -> {
            for (int i = 0; i < 10; i++) {
                repository.add(DAY, 7);
            }
            return null;
        });

        assertThat(outcome.failures()).isEmpty();
        assertThat(repository.read(DAY)).isEqualTo(new SpendStore.Day(24 * 10 * 7, 240));
    }
}
