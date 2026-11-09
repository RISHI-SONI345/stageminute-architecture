package app.stageminute.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Fail closed (AD-65): a server whose sign-in settings are missing or unsafe does not start. */
class ClerkPropertiesTest {

    private static final String ISSUER = "https://content-redbird-397.clerk.accounts.dev";
    private static final String KEYS = ISSUER + "/.well-known/jwks.json";

    private static ClerkProperties props(String issuer, String keys) {
        return new ClerkProperties(issuer, keys, List.of(), "");
    }

    @Test
    void goodSettingsPass() {
        assertThatCode(() -> props(ISSUER, KEYS).requireUsable()).doesNotThrowAnyException();
    }

    @Test
    void aMissingIssuerStopsTheServerAndNamesTheVariable() {
        assertThatThrownBy(() -> props("", KEYS).requireUsable())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLERK_ISSUER");
        assertThatThrownBy(() -> props(null, KEYS).requireUsable()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anIssuerThatIsNotHttpsOrHasATrailingSlashIsRefused() {
        assertThatThrownBy(() -> props("http://clerk.example", KEYS).requireUsable())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> props(ISSUER + "/", KEYS).requireUsable()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingKeyAddressStopsTheServer() {
        assertThatThrownBy(() -> props(ISSUER, "").requireUsable())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLERK_JWKS_URL");
    }

    @Test
    void keysOverPlainHttpAreAllowedOnlyOnThisMachine() {
        assertThatCode(() -> props(ISSUER, "http://localhost:9999/jwks.json").requireUsable())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> props(ISSUER, "http://clerk.example/jwks.json").requireUsable())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void partiesAreTrimmedAndEmptyOnesDropped() {
        ClerkProperties p = new ClerkProperties(ISSUER, KEYS, List.of(" app-one ", "", "app-two"), " ");

        assertThat(p.authorizedParties()).containsExactly("app-one", "app-two");
        assertThat(p.webhookSecret()).isEmpty();
    }
}
