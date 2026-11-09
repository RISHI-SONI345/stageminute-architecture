package app.stageminute.money.vendor;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorHttp;
import org.springframework.web.client.RestClient;

/**
 * {@code DELETE /v1/subscribers/{app_user_id}} on RevenueCat's REST API with a secret key as bearer. RevenueCat has no
 * account yet (ticket #22): the path was taken from RevenueCat's public documentation and community answers, and #22
 * checks it against the real project before launch (AD-149).
 */
class HttpRevenueCatSubscribers implements RevenueCatSubscribers {

    private static final String VENDOR = "revenuecat";

    private final RestClient client;

    HttpRevenueCatSubscribers(String baseUrl, String secretKey) {
        this.client = VendorHttp.client(baseUrl, secretKey);
    }

    @Override
    public DeleteOutcome delete(String appUserId) {
        return VendorHttp.send(VENDOR, client.delete().uri("/v1/subscribers/{id}", appUserId));
    }
}
