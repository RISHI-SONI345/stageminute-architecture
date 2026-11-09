package app.stageminute.support;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.UserId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The quota invariant (ticket #11, AD-106): for every day of a person, the ledger and the counter row tell
 * the same story. Every quota test ends with this check.
 *
 * <p>The convention (AD-107): a reserve is +1, a release or a give-back is -1 and a commit is 0, so the sum
 * of those deltas is {@code sessions_used}; an {@code ad_credit} is +1 and its sum is {@code ad_credits}.
 */
public final class LedgerAssertions {

    private LedgerAssertions() {}

    public static void assertLedgerMatchesCounters(JdbcClient jdbc, UserId... users) {
        for (UserId user : users) {
            List<String> mismatches =
                    jdbc.sql("""
                            SELECT coalesce(c.local_date, l.local_date) || ': counter sessions_used='
                                   || coalesce(c.sessions_used, 0) || ' ledger=' || coalesce(l.session_sum, 0)
                                   || ', counter ad_credits=' || coalesce(c.ad_credits, 0)
                                   || ' ledger=' || coalesce(l.credit_sum, 0)
                              FROM (SELECT * FROM daily_counters WHERE user_id = :u) c
                              FULL OUTER JOIN (
                                SELECT local_date,
                                       sum(delta) FILTER (WHERE kind IN
                                         ('reserve','commit','release','give_back','expire')) AS session_sum,
                                       sum(delta) FILTER (WHERE kind = 'ad_credit') AS credit_sum
                                  FROM quota_events WHERE user_id = :u GROUP BY local_date) l
                                ON c.local_date = l.local_date
                             WHERE coalesce(c.sessions_used, 0) <> coalesce(l.session_sum, 0)
                                OR coalesce(c.ad_credits, 0) <> coalesce(l.credit_sum, 0)
                            """).param("u", user.value()).query(String.class).list();
            assertThat(mismatches)
                    .as("days where the ledger and the counters of user %s disagree", user)
                    .isEmpty();
        }
    }
}
