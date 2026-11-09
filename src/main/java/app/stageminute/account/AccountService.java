package app.stageminute.account;

import app.stageminute.account.internal.AccountRepository;
import app.stageminute.platform.AbuseSignals;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.IdentifierHasher;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.platform.Uuids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds our user for a Clerk user id and creates the row on the first call (10-AUTH-CLERK section 3). The
 * new-account limits of F-086 run once, at creation: a limit that trips leaves an abuse signal and nothing
 * else, because the aim is to make abuse costly, not to block an honest person.
 *
 * <p>Safe under parallel first calls: {@code users.clerk_user_id} is unique and the insert says
 * {@code ON CONFLICT DO NOTHING}, so exactly one caller creates the row and counts the device. The SQL is in
 * {@link AccountRepository}; the rules are here.
 */
@Service
public class AccountService {

    private static final String DEFAULT_LEVEL = "starter";
    private static final String DEFAULT_STYLE = "prep";
    private static final String DEFAULT_ZONE = "UTC";
    private static final int DEVICE_WINDOW_DAYS = 30;
    private static final int ADDRESS_KEEP_DAYS = 1;
    private static final String APPLE_RELAY_DOMAIN = "@privaterelay.appleid.com";

    private final AccountRepository accounts;
    private final Clock clock;
    private final Settings settings;
    private final IdentifierHasher hasher;
    private final AbuseSignals signals;

    AccountService(
            AccountRepository accounts, Clock clock, Settings settings, IdentifierHasher hasher, AbuseSignals signals) {
        this.accounts = accounts;
        this.clock = clock;
        this.settings = settings;
        this.hasher = hasher;
        this.signals = signals;
    }

    public Optional<UserId> find(String clerkUserId) {
        return accounts.findId(clerkUserId).map(UserId::new);
    }

    /**
     * Our user for this Clerk user, created on the first call.
     *
     * @throws BusinessRefusal {@code account_deleted} when this Clerk user's account was deleted (05 F-082)
     */
    @Transactional
    public UserId resolve(String clerkUserId, NewAccountContext context) {
        Optional<UUID> existing = accounts.findId(clerkUserId);
        if (existing.isPresent()) {
            OffsetDateTime now = now();
            // The 24-month idle job reads this; once a day is precise enough, and a quiet call writes nothing.
            accounts.touchSignIn(clerkUserId, now, now.minusDays(1));
            return new UserId(existing.get());
        }
        if (accounts.clerkWasDeleted(hasher.hash(clerkUserId))) {
            // The token is still valid for a minute or so after the account went; it must not make a new one.
            throw new BusinessRefusal(Reason.ACCOUNT_DELETED);
        }
        UUID id = Uuids.v7(clock);
        if (!accounts.insertIfAbsent(id, clerkUserId, DEFAULT_LEVEL, DEFAULT_STYLE, DEFAULT_ZONE, now())) {
            // Another call created it between our look and our insert; it has committed by now.
            return new UserId(accounts.findId(clerkUserId).orElseThrow());
        }
        applyNewAccountLimits(id, context);
        return new UserId(id);
    }

    private void applyNewAccountLimits(UUID userId, NewAccountContext context) {
        byte[] deviceHash = null;
        String deviceId = context.deviceId();
        if (deviceId == null || deviceId.isBlank()) {
            signals.record("device_missing", userId, null);
        } else {
            deviceHash = hasher.hash(deviceId);
            String platform =
                    context.platform() == null || context.platform().isBlank() ? "unknown" : context.platform();
            LocalDate today = today();
            int onDevice =
                    accounts.countDevice(deviceHash, platform, today, today.minusDays(DEVICE_WINDOW_DAYS), now());
            if (onDevice > settings.get(SettingKey.NEW_ACCOUNTS_PER_DEVICE_30_DAYS)) {
                signals.record("velocity_device", userId, deviceHash);
            }
        }
        String ip = context.clientIp();
        if (ip != null && !ip.isBlank()) {
            int fromAddress = accounts.countAddress(hasher.hash(ip), today());
            if (fromAddress > settings.get(SettingKey.NEW_ACCOUNTS_PER_IP_PER_DAY)) {
                signals.record("velocity_ip", userId, deviceHash);
            }
        }
    }

    /** Retention job (ticket #28 schedules it): address counts older than yesterday are not needed. */
    public int purgeOldAddressCounts() {
        return accounts.deleteAddressCountsBefore(today().minusDays(ADDRESS_KEEP_DAYS));
    }

    /** What {@link #updateEmail} did. */
    public enum EmailUpdate {
        APPLIED,
        /** A newer update was already applied. */
        OLDER,
        UNKNOWN_USER
    }

    /**
     * Stores the primary email Clerk reports (and whether it is an Apple private-relay address). Clerk
     * webhooks can arrive out of order, so an update is applied only when its event time is newer than the
     * last one applied (03 F-092).
     */
    @Transactional
    public EmailUpdate updateEmail(String clerkUserId, @Nullable String email, long eventMillis) {
        OffsetDateTime eventTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(eventMillis), ZoneOffset.UTC);
        boolean relay = email != null && email.toLowerCase(Locale.ROOT).endsWith(APPLE_RELAY_DOMAIN);
        if (accounts.applyEmail(clerkUserId, email, relay, eventTime)) {
            return EmailUpdate.APPLIED;
        }
        return accounts.findId(clerkUserId).isPresent() ? EmailUpdate.OLDER : EmailUpdate.UNKNOWN_USER;
    }

    /**
     * Remembers which Google or Apple account this person signs in with (the id Clerk reports for it). It is
     * kept so sign-in could move away from Clerk later without losing anyone (10-AUTH-CLERK section 9, AD-94).
     * Anything but a non-empty id for {@code google} or {@code apple} is ignored.
     */
    @Transactional
    public void linkSignInProvider(String clerkUserId, String provider, String subject) {
        if (("google".equals(provider) || "apple".equals(provider)) && !subject.isBlank() && subject.length() <= 255) {
            accounts.linkProvider(clerkUserId, provider, subject);
        }
    }

    /** True while the account row exists (a run that began before a deletion asks this when its save fails). */
    public boolean exists(UserId id) {
        return accounts.exists(id.value());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
