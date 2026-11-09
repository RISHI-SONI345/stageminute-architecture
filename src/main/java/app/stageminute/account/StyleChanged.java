package app.stageminute.account;

import app.stageminute.platform.UserId;

/**
 * The person's practice style changed (02a F-009, {@code PATCH /me}). Published inside the change's transaction, so a
 * synchronous listener (the carried focus in {@code reports}, 03 F-034) acts in the same transaction: the change and
 * its effects happen together or not at all.
 *
 * @param user whose style changed
 * @param style the new style, {@code prep} or {@code star}
 */
public record StyleChanged(UserId user, String style) {}
