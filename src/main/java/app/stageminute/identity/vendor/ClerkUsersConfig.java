package app.stageminute.identity.vendor;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Picks the Clerk adapter (AD-149). With no secret key there is nothing to call: under the {@code local} and {@code
 * test} profiles the adapter answers {@link DeleteOutcome#NOT_CONFIGURED} and the event is done; under {@code staging}
 * and {@code prod} the server does not start, because a Clerk user that stays holds the person's email.
 */
@Configuration(proxyBeanMethods = false)
class ClerkUsersConfig {

    @Bean
    ClerkUsers clerkUsers(
            Environment env,
            @Value("${stageminute.clerk.secret-key:}") String secretKey,
            @Value("${stageminute.clerk.api-url:https://api.clerk.com}") String apiUrl) {
        if (secretKey.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("staging", "prod"))) {
                throw new IllegalStateException("CLERK_SECRET_KEY is not set; the server will not start without it");
            }
            return clerkUserId -> DeleteOutcome.NOT_CONFIGURED;
        }
        return new HttpClerkUsers(VendorHttp.requireSafeBase("CLERK_API_URL", apiUrl), secretKey.trim());
    }
}
