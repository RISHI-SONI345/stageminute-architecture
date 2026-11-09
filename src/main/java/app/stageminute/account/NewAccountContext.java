package app.stageminute.account;

import org.jspecify.annotations.Nullable;

/**
 * What the server knows about the request that creates an account: the random device id the app made at first
 * launch ({@code X-Device-Id}), the platform ({@code ios} or {@code android}) and the caller's address. Used
 * only for the new-account limits (F-086), and only as keyed hashes.
 */
public record NewAccountContext(
        @Nullable String deviceId,
        @Nullable String platform,
        @Nullable String clientIp) {}
