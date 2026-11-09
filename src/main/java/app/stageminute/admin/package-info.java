/**
 * Support console with an audit log.
 * <p>Start here: not built yet; see docs/architecture/02-SERVER-LLD.md section 2. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "admin",
        allowedDependencies = {"account", "practice", "reports", "progress", "money", "platform"})
package app.stageminute.admin;

import org.springframework.modulith.ApplicationModule;
