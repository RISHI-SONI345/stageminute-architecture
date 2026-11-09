package app.stageminute.account;

import app.stageminute.account.internal.AccountRepository;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.IdentifierHasher;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes an account (05 F-082, ticket #18.1). In ONE transaction it publishes {@link Requested}, writes the opaque
 * deletion note ({@code deleted_users}) and removes the {@code users} row; the database cascade removes everything of
 * the person. If any step throws, nothing changed: no account loss without the event, no event without the loss.
 *
 * <p>The event goes into Spring Modulith's event table inside the same transaction (AD-07), so the outside work (Clerk,
 * RevenueCat, Superwall, PostHog) is promised by the commit and retried until done (AD-87). It carries ids only: our
 * user id, the Clerk user id and the id RevenueCat knows the person by. The listeners live in the modules that own
 * those vendors; this class imports none of them.
 */
@Service
public class Deletion {

    /**
     * An account was deleted; the listeners now remove the person at the outside services. Ids only: no email, no
     * name, no token.
     *
     * @param user our id of the deleted account (opaque)
     * @param clerkUserId the Clerk user to delete at Clerk
     * @param revenueCatId the app user id RevenueCat (and Superwall) know the person by
     */
    public record Requested(UserId user, String clerkUserId, String revenueCatId) {}

    private final AccountRepository accounts;
    private final IdentifierHasher hasher;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    Deletion(AccountRepository accounts, IdentifierHasher hasher, Clock clock, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.hasher = hasher;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Deletes the account of this signed-in person ({@code DELETE /v1/me}).
     *
     * @throws BusinessRefusal {@code account_deleted} when the account is already gone (the app counts it as done)
     */
    @Transactional
    public void delete(UserId user) {
        String clerkUserId =
                accounts.lockClerkId(user.value()).orElseThrow(() -> new BusinessRefusal(Reason.ACCOUNT_DELETED));
        remove(user.value(), clerkUserId);
    }

    /**
     * Deletes the account of a Clerk user who was deleted at Clerk (the {@code user.deleted} webhook). The Clerk call
     * in the listener then finds nobody, which counts as done.
     *
     * @return false when we have no such account (our own Clerk delete comes back as this webhook)
     */
    @Transactional
    public boolean deleteByClerkUserId(String clerkUserId) {
        Optional<UUID> id = accounts.lockIdByClerkId(clerkUserId);
        id.ifPresent(found -> remove(found, clerkUserId));
        return id.isPresent();
    }

    /**
     * True when this account was deleted (its note is still kept). Webhooks for a person look this up so that an event
     * for a deleted account is answered 200 and ignored (03 F-092, EC-104).
     */
    public boolean wasDeleted(UUID userId) {
        return accounts.wasDeleted(userId);
    }

    private void remove(UUID id, String clerkUserId) {
        events.publishEvent(new Requested(new UserId(id), clerkUserId, id.toString()));
        accounts.noteDeleted(id, hasher.hash(clerkUserId), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        accounts.deleteUser(id);
    }
}
