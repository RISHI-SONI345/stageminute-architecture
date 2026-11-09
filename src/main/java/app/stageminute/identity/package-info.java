/**
 * Sign-in through Clerk (AD-53): token check, first-call user, Clerk webhook, Apple server notices.
 * <p>Start here: {@code internal/SecurityConfig} (who may call what), then {@code internal/ClerkWebhookHandler} and {@code internal/ClerkDeletionListener} (port: {@code vendor/ClerkUsers}). Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "identity",
        allowedDependencies = {"account", "platform"})
package app.stageminute.identity;

import org.springframework.modulith.ApplicationModule;
