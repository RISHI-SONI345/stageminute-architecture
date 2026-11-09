package app.stageminute.money.vendor;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the RevenueCat adapter. With no secret key there is no vendor to call: the adapter answers {@link
 * DeleteOutcome#NOT_CONFIGURED} and the event is done (AD-149). RevenueCat has no account yet (ticket #22); that
 * ticket makes the key required in staging and prod.
 */
@Configuration(proxyBeanMethods = false)
class RevenueCatConfig {

    @Bean
    RevenueCatSubscribers revenueCatSubscribers(
            @Value("${stageminute.revenuecat.secret-key:}") String secretKey,
            @Value("${stageminute.revenuecat.api-url:https://api.revenuecat.com}") String apiUrl) {
        if (secretKey.isBlank()) {
            return appUserId -> DeleteOutcome.NOT_CONFIGURED;
        }
        return new HttpRevenueCatSubscribers(
                VendorHttp.requireSafeBase("REVENUECAT_API_URL", apiUrl), secretKey.trim());
    }
}
