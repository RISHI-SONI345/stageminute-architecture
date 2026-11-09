package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.support.VendorClientContract;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The real PostHog client against a local WireMock (AD-149), and the choice of "not configured". */
class HttpPostHogPersonsTest extends VendorClientContract {

    private static final String USER = UUID.randomUUID().toString();

    @Override
    protected String id() {
        return USER;
    }

    @Override
    protected DeleteOutcome delete(String baseUrl, String id) {
        return new HttpPostHogPersons(baseUrl, "4242", KEY).delete(id);
    }

    @Override
    protected String method() {
        return "POST";
    }

    @Override
    protected String path(String id) {
        return "/api/projects/4242/persons/bulk_delete/";
    }

    @Override
    protected String expectedBody(String id) {
        return "{\"distinct_ids\":[\"" + id + "\"],\"delete_events\":true}";
    }

    @Test
    void withAnyPartMissingNothingIsSentAndTheEventIsDone() {
        PostHogConfig config = new PostHogConfig();

        assertThat(config.postHogPersons("https://us.posthog.com", "4242", "").delete(USER))
                .isEqualTo(DeleteOutcome.NOT_CONFIGURED);
        assertThat(config.postHogPersons("https://us.posthog.com", "", KEY).delete(USER))
                .isEqualTo(DeleteOutcome.NOT_CONFIGURED);
    }

    @Test
    void anAddressThatIsNotHttpsIsRefusedWithoutPrintingIt() {
        assertThatThrownBy(() -> new PostHogConfig().postHogPersons("http://posthog.example.com", "1", KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTHOG_API_HOST")
                .hasMessageNotContaining("example.com");
    }
}
