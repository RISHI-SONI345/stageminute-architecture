/**
 * Composition only: assembles {@code GET /me} from other modules' public types (AD-42).
 * It holds no rule and owns no table; no other module may depend on it.
 * <p>Start here: {@code MeController} (the {@code reminders} block of {@code GET} and {@code PATCH /v1/me} is built here from {@code Profile.Reminders}). Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "me",
        allowedDependencies = {"account", "practice", "money", "progress", "reports", "platform"})
package app.stageminute.me;

import org.springframework.modulith.ApplicationModule;
