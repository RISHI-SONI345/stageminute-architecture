/**
 * Entitlement, RevenueCat and AdMob webhooks, rewarded and exit ads, soft sheet.
 * <p>Start here: {@code internal/RevenueCatDeletionListener} (the only built part; port {@code vendor/RevenueCatSubscribers}); the rest is not built yet, see docs/architecture/02-SERVER-LLD.md section 2. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "money",
        allowedDependencies = {"account", "platform"})
package app.stageminute.money;

import org.springframework.modulith.ApplicationModule;
