package app.stageminute.account.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the account: the {@code users} row found by Clerk user id, the new-account counters
 * ({@code device_flags}, {@code ip_new_accounts}) and the deletion note ({@code deleted_users}). It holds no rule; the rules
 * (limits, relay addresses, event order) live in {@code AccountService}.
 */
@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findId(String clerkUserId) {
        return jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                .param("c", clerkUserId)
                .query(UUID.class)
                .optional();
    }

    /** {@code ON CONFLICT DO NOTHING}: false means another call created the row first. */
    public boolean insertIfAbsent(
            UUID id, String clerkUserId, String level, String style, String zone, OffsetDateTime now) {
        return jdbc.sql("INSERT INTO users (id, clerk_user_id, level, style, timezone, last_sign_in_at,"
                                + " created_at) VALUES (:id, :clerk, :level, :style, :zone, :now, :now)"
                                + " ON CONFLICT (clerk_user_id) DO NOTHING")
                        .param("id", id)
                        .param("clerk", clerkUserId)
                        .param("level", level)
                        .param("style", style)
                        .param("zone", zone)
                        .param("now", now)
                        .update()
                == 1;
    }

    /** Writes only when the last sign-in is older than {@code cutoff}, so a quiet call writes nothing. */
    public void touchSignIn(String clerkUserId, OffsetDateTime now, OffsetDateTime cutoff) {
        jdbc.sql("UPDATE users SET last_sign_in_at = :now WHERE clerk_user_id = :c AND last_sign_in_at < :cutoff")
                .param("now", now)
                .param("c", clerkUserId)
                .param("cutoff", cutoff)
                .update();
    }

    /** One statement, so two accounts created at once cannot both read the old count. */
    public int countDevice(
            byte[] deviceHash, String platform, LocalDate today, LocalDate windowEnd, OffsetDateTime now) {
        return jdbc.sql("INSERT INTO device_flags (device_hash, platform, accounts_created, window_start, updated_at)"
                        + " VALUES (:h, :platform, 1, :today, :now)"
                        + " ON CONFLICT (device_hash) DO UPDATE SET"
                        + " accounts_created = CASE WHEN device_flags.window_start <= :windowEnd THEN 1"
                        + " ELSE device_flags.accounts_created + 1 END,"
                        + " window_start = CASE WHEN device_flags.window_start <= :windowEnd THEN :today"
                        + " ELSE device_flags.window_start END,"
                        + " updated_at = :now"
                        + " RETURNING accounts_created")
                .param("h", deviceHash)
                .param("platform", platform)
                .param("today", today)
                .param("windowEnd", windowEnd)
                .param("now", now)
                .query(Integer.class)
                .single();
    }

    public int countAddress(byte[] ipHash, LocalDate day) {
        return jdbc.sql("INSERT INTO ip_new_accounts (ip_hash, day, accounts) VALUES (:h, :day, 1)"
                        + " ON CONFLICT (ip_hash, day) DO UPDATE SET accounts = ip_new_accounts.accounts + 1"
                        + " RETURNING accounts")
                .param("h", ipHash)
                .param("day", day)
                .query(Integer.class)
                .single();
    }

    public int deleteAddressCountsBefore(LocalDate limit) {
        return jdbc.sql("DELETE FROM ip_new_accounts WHERE day < :limit")
                .param("limit", limit)
                .update();
    }

    /** Applies the email only when {@code eventTime} is newer than the last one applied. */
    public boolean applyEmail(String clerkUserId, @Nullable String email, boolean relay, OffsetDateTime eventTime) {
        return jdbc.sql("UPDATE users SET email = :email, email_is_relay = :relay, clerk_updated_at = :t"
                                + " WHERE clerk_user_id = :c AND (clerk_updated_at IS NULL OR clerk_updated_at < :t)")
                        .param("email", email)
                        .param("relay", relay)
                        .param("t", eventTime)
                        .param("c", clerkUserId)
                        .update()
                == 1;
    }

    /** Remembers the Google or Apple id behind the Clerk account; a later link replaces an earlier one. */
    public void linkProvider(String clerkUserId, String provider, String subject) {
        jdbc.sql("UPDATE users SET auth_provider = :p, auth_subject = :s WHERE clerk_user_id = :c"
                        + " AND (auth_provider IS DISTINCT FROM :p OR auth_subject IS DISTINCT FROM :s)")
                .param("p", provider)
                .param("s", subject)
                .param("c", clerkUserId)
                .update();
    }

    /**
     * Locks the row of this user and answers its Clerk id; empty when the account is already gone. The lock makes a
     * second delete wait until the first one has committed, and then find nothing.
     */
    public Optional<String> lockClerkId(UUID id) {
        return jdbc.sql("SELECT clerk_user_id FROM users WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /** Same lock, found by Clerk id (the Clerk webhook knows only that). */
    public Optional<UUID> lockIdByClerkId(String clerkUserId) {
        return jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c FOR UPDATE")
                .param("c", clerkUserId)
                .query(UUID.class)
                .optional();
    }

    /** One {@code DELETE FROM users}; the database cascade removes everything of the person. */
    public void deleteUser(UUID id) {
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update();
    }

    /**
     * Writes the deletion note: our opaque id and a keyed hash of the Clerk id, nothing else. A clash on the hash
     * (the same Clerk id noted twice) is an error on purpose, so the whole deletion rolls back.
     */
    public void noteDeleted(UUID id, byte[] clerkHash, OffsetDateTime now) {
        jdbc.sql("INSERT INTO deleted_users (user_id, deleted_at, clerk_hash) VALUES (:id, :now, :hash)"
                        + " ON CONFLICT (user_id) DO NOTHING")
                .param("id", id)
                .param("now", now)
                .param("hash", clerkHash)
                .update();
    }

    public boolean clerkWasDeleted(byte[] clerkHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM deleted_users WHERE clerk_hash = :hash)")
                .param("hash", clerkHash)
                .query(Boolean.class)
                .single();
    }

    public boolean wasDeleted(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM deleted_users WHERE user_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }
}
