package app.stageminute.identity.vendor;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorHttp;
import org.springframework.web.client.RestClient;

/**
 * {@code DELETE /v1/users/{user_id}} on Clerk's Backend API with the secret key as bearer. Answers 200 with a "deleted
 * object" for a user that existed and 404 for one that did not (the echo of our own earlier delete, or a user deleted
 * by hand). Note: this does NOT revoke the person's Sign in with Apple grant at Apple (Clerk's own guide says deleting
 * the user does not reset Apple's side); that is conflict C-78.
 */
class HttpClerkUsers implements ClerkUsers {

    private static final String VENDOR = "clerk";

    private final RestClient client;

    HttpClerkUsers(String baseUrl, String secretKey) {
        this.client = VendorHttp.client(baseUrl, secretKey);
    }

    @Override
    public DeleteOutcome delete(String clerkUserId) {
        return VendorHttp.send(VENDOR, client.delete().uri("/v1/users/{id}", clerkUserId));
    }
}
