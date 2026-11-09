package app.stageminute.platform;

import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Deletes a person in PostHog with one {@code POST /api/projects/{project}/persons/bulk_delete/} naming the distinct
 * id, with {@code delete_events} on so their events go too (PostHog queues those and removes them in its next batch).
 * Needs a personal API key with the {@code person:write} scope. A person PostHog does not know answers 404, which is
 * done. Checked against PostHog's persons API reference on 2026-11-01; the project id and key are set up with ticket #7.
 */
class HttpPostHogPersons implements PostHogPersons {

    private static final String VENDOR = "posthog";

    private final RestClient client;
    private final String projectId;

    HttpPostHogPersons(String baseUrl, String projectId, String personalApiKey) {
        this.client = VendorHttp.client(baseUrl, personalApiKey);
        this.projectId = projectId;
    }

    @Override
    public DeleteOutcome delete(String distinctId) {
        return VendorHttp.send(
                VENDOR,
                client.post()
                        .uri("/api/projects/{project}/persons/bulk_delete/", projectId)
                        .body(Map.of("distinct_ids", List.of(distinctId), "delete_events", true)));
    }
}
