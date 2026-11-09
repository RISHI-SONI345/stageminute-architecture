package app.stageminute.identity.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.support.VendorClientContract;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The real Clerk client against a local WireMock (AD-149), and the choice between it and "not configured". */
class HttpClerkUsersTest extends VendorClientContract {

    @Override
    protected DeleteOutcome delete(String baseUrl, String id) {
        return new HttpClerkUsers(baseUrl, KEY).delete(id);
    }

    @Override
    protected String method() {
        return "DELETE";
    }

    @Override
    protected String path(String id) {
        return "/v1/users/" + id;
    }

    @Test
    void withNoKeyTheLocalAndTestProfilesSendNothingAndAreDone() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        ClerkUsers users = new ClerkUsersConfig().clerkUsers(env, "", "https://api.clerk.com");

        assertThat(users.delete("user_x")).isEqualTo(DeleteOutcome.NOT_CONFIGURED);
    }

    @Test
    void withNoKeyStagingAndProdRefuseToStart() {
        for (String profile : new String[] {"staging", "prod"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);

            assertThatThrownBy(() -> new ClerkUsersConfig().clerkUsers(env, " ", "https://api.clerk.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CLERK_SECRET_KEY");
        }
    }

    @Test
    void anAddressThatIsNotHttpsIsRefusedWithoutPrintingIt() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ClerkUsersConfig().clerkUsers(env, KEY, "http://clerk.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLERK_API_URL")
                .hasMessageNotContaining("example.com");
    }
}
