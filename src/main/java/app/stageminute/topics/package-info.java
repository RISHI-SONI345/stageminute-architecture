/**
 * Read-only topic catalog loaded from content/topics.
 * <p>Start here: {@code TopicCatalog} (pool, get, pick, version), then {@code internal/TopicFileLoader} and
 * {@code web/TopicsController}. See docs/architecture/02-SERVER-LLD.md section 2. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "topics",
        allowedDependencies = {"platform"})
package app.stageminute.topics;

import org.springframework.modulith.ApplicationModule;
