package app.stageminute.identity.internal;

import app.stageminute.account.AccountService;
import app.stageminute.account.Deletion;
import app.stageminute.platform.WebhookInbox;
import app.stageminute.platform.WebhookInbox.Outcome;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * What a verified Clerk event does (10-AUTH-CLERK section 3):
 *
 * <ul>
 *   <li>{@code user.deleted}: the person deleted their account at Clerk, so we delete ours (the full deletion, with the
 *       events for the other vendors). The echo of our own Clerk delete finds no account and is ignored;
 *   <li>{@code user.updated}: store the primary email and the Apple relay flag, if this update is the newest,
 *       and the Google or Apple id behind the account (AD-94).
 * </ul>
 *
 * Every other event type is ignored without a trace. The claim in the inbox, the work and the outcome are one
 * transaction: a failure rolls all of it back, so Clerk's retry starts clean; a repeat of a finished message
 * does nothing. Nothing from the payload is logged.
 */
@Component
class ClerkWebhookHandler {

    static final String SOURCE = "clerk";

    private final WebhookInbox inbox;
    private final AccountService accounts;
    private final Deletion deletion;

    ClerkWebhookHandler(WebhookInbox inbox, AccountService accounts, Deletion deletion) {
        this.inbox = inbox;
        this.accounts = accounts;
        this.deletion = deletion;
    }

    @Transactional
    void handle(String messageId, JsonNode event, long receivedMillis) {
        String type = text(event.path("type"));
        if (!"user.deleted".equals(type) && !"user.updated".equals(type)) {
            return;
        }
        if (!inbox.claim(SOURCE, messageId)) {
            return; // already handled
        }
        String clerkUserId = text(event.path("data").path("id"));
        if (clerkUserId == null) {
            inbox.finish(SOURCE, messageId, Outcome.IGNORED_UNKNOWN_USER);
            return;
        }
        Outcome outcome = switch (type) {
            case "user.deleted" ->
                deletion.deleteByClerkUserId(clerkUserId) ? Outcome.APPLIED : Outcome.IGNORED_UNKNOWN_USER;
            default -> updated(clerkUserId, event, receivedMillis);
        };
        inbox.finish(SOURCE, messageId, outcome);
    }

    private Outcome updated(String clerkUserId, JsonNode event, long receivedMillis) {
        JsonNode timestamp = event.path("timestamp");
        long eventMillis = timestamp.isNumber() ? timestamp.asLong() : receivedMillis;
        linkProvider(clerkUserId, event.path("data"));
        return switch (accounts.updateEmail(clerkUserId, primaryEmail(event.path("data")), eventMillis)) {
            case APPLIED -> Outcome.APPLIED;
            case OLDER -> Outcome.IGNORED_OLDER;
            case UNKNOWN_USER -> Outcome.IGNORED_UNKNOWN_USER;
        };
    }

    /** The first linked account that is Google or Apple. Clerk writes {@code oauth_google} in some places. */
    private void linkProvider(String clerkUserId, JsonNode user) {
        for (JsonNode account : user.path("external_accounts")) {
            String provider = text(account.path("provider"));
            String subject = text(account.path("provider_user_id"));
            if (provider == null || subject == null) {
                continue;
            }
            String name = provider.startsWith("oauth_") ? provider.substring("oauth_".length()) : provider;
            if ("google".equals(name) || "apple".equals(name)) {
                accounts.linkSignInProvider(clerkUserId, name, subject);
                return;
            }
        }
    }

    private static @Nullable String primaryEmail(JsonNode user) {
        String primaryId = text(user.path("primary_email_address_id"));
        for (JsonNode address : user.path("email_addresses")) {
            if (primaryId != null && primaryId.equals(text(address.path("id")))) {
                return text(address.path("email_address"));
            }
        }
        return null;
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }
}
