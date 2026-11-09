package app.stageminute.support;

import app.stageminute.identity.vendor.ClerkUsers;
import app.stageminute.money.vendor.RevenueCatSubscribers;
import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.PostHogPersons;
import app.stageminute.platform.VendorCallFailed;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Fake deletion vendors for tests: each remembers the ids it was asked to delete and can be told to fail its next
 * calls, so a test can prove the retry without any real vendor (#18.1). Import with {@code @Import}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingVendors {

    /** One fake vendor: records ids, fails on demand. */
    public static class Vendor {
        private final String name;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();

        Vendor(String name) {
            this.name = name;
        }

        DeleteOutcome call(String id) {
            calls.add(id);
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new VendorCallFailed(name, 503);
            }
            return DeleteOutcome.DELETED;
        }

        /** The next {@code times} calls throw {@link VendorCallFailed}. */
        public void failNext(int times) {
            failuresLeft.set(times);
        }

        /** Every id asked for so far, in order (a failed try is listed too). */
        public List<String> calls() {
            return List.copyOf(calls);
        }

        public void reset() {
            calls.clear();
            failuresLeft.set(0);
        }
    }

    public static final class Clerk extends Vendor implements ClerkUsers {
        Clerk() {
            super("clerk");
        }

        @Override
        public DeleteOutcome delete(String clerkUserId) {
            return call(clerkUserId);
        }
    }

    public static final class RevenueCat extends Vendor implements RevenueCatSubscribers {
        RevenueCat() {
            super("revenuecat");
        }

        @Override
        public DeleteOutcome delete(String appUserId) {
            return call(appUserId);
        }
    }

    public static final class PostHog extends Vendor implements PostHogPersons {
        PostHog() {
            super("posthog");
        }

        @Override
        public DeleteOutcome delete(String distinctId) {
            return call(distinctId);
        }
    }

    @Bean
    @Primary
    Clerk recordingClerk() {
        return new Clerk();
    }

    @Bean
    @Primary
    RevenueCat recordingRevenueCat() {
        return new RevenueCat();
    }

    @Bean
    @Primary
    PostHog recordingPostHog() {
        return new PostHog();
    }
}
