package app.stageminute.practice.internal;

import java.util.Locale;

/** The kinds a {@code quota_events} row can have; the database allows exactly these words. */
public enum QuotaEventKind {
    RESERVE,
    COMMIT,
    RELEASE,
    AD_CREDIT,
    GIVE_BACK,
    ADMIN_GRANT,
    EXPIRE;

    /** The word stored in the {@code kind} column. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
