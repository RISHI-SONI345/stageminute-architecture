package app.stageminute.money.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.support.VendorClientContract;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The real RevenueCat client against a local WireMock (AD-149), and the choice of "not configured". */
class HttpRevenueCatSubscribersTest extends VendorClientContract {

    private static final String USER = UUID.randomUUID().toString();

    @Override
    protected String id() {
        return USER;
    }

    @Override
    protected DeleteOutcome delete(String baseUrl, String id) {
        return new HttpRevenueCatSubscribers(baseUrl, KEY).delete(id);
    }

    @Override
    protected String method() {
        return "DELETE";
    }

    @Override
    protected String path(String id) {
        return "/v1/subscribers/" + id;
    }

    @Test
    void withNoKeyNothingIsSentAndTheEventIsDone() {
        RevenueCatSubscribers subscribers =
                new RevenueCatConfig().revenueCatSubscribers("", "https://api.revenuecat.com");

        assertThat(subscribers.delete(USER)).isEqualTo(DeleteOutcome.NOT_CONFIGURED);
    }
}
