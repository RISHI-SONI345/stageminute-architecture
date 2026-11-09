package app.stageminute.reports.internal;

import app.stageminute.reports.ReportV2;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one serializer of Report v2 (AD-13, AD-126): the text kept in {@code reports.report} and the {@code report} in
 * the idempotency entry are both written here, from the same {@link ReportV2} the endpoints return, so the stored and
 * the sent shape cannot drift. Field names come from {@code @JsonProperty} on {@link ReportV2} (the server's snake_case
 * rule); {@code ReportContractTest} keeps them equal to 06-API section 9 and the OpenAPI file.
 */
@Component
public class ReportJson {

    private final JsonMapper mapper;

    public ReportJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(ReportV2 report) {
        return mapper.writeValueAsString(report);
    }

    public ReportV2 read(String json) {
        return mapper.readValue(json, ReportV2.class);
    }
}
