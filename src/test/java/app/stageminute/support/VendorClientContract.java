package app.stageminute.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorCallFailed;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What every outside "delete a person" client must do (AD-149), proved against a local WireMock and never a real
 * vendor: it sends the right request with the key as a bearer token, 2xx is done, 404 is done (already gone), and 429,
 * 5xx, a refused key, a redirect and no answer all fail loudly so the event is tried again. A subclass says how to
 * build its client, which request it sends, and which id to use.
 */
public abstract class VendorClientContract {

    protected static final String KEY = "test-mock-vendor-key";

    protected WireMockServer wire;

    /** Builds the real client for this vendor, pointed at {@code baseUrl}, and deletes this id. */
    protected abstract DeleteOutcome delete(String baseUrl, String id);

    /** The HTTP method of the vendor's delete call. */
    protected abstract String method();

    /** The path (with query) of the vendor's delete call for this id. */
    protected abstract String path(String id);

    /** The text the request body must be (JSON, compared as JSON) or null for no body check. */
    protected String expectedBody(String id) {
        return null;
    }

    protected String id() {
        return "id_123";
    }

    @BeforeEach
    void startWire() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
    }

    @AfterEach
    void stopWire() {
        wire.stop();
    }

    private String base() {
        return "http://localhost:" + wire.port();
    }

    private MappingBuilder stub() {
        return request(method(), urlEqualTo(path(id())));
    }

    @Test
    void sendsTheRightRequestWithTheKeyAsABearerToken() {
        wire.stubFor(stub().willReturn(aResponse().withStatus(200).withBody("{}")));

        DeleteOutcome outcome = delete(base(), id());

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        RequestPatternBuilder expected = new RequestPatternBuilder(
                        com.github.tomakehurst.wiremock.http.RequestMethod.fromString(method()), urlEqualTo(path(id())))
                .withHeader("Authorization", equalTo("Bearer " + KEY));
        if (expectedBody(id()) != null) {
            expected.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson(expectedBody(id())));
        }
        wire.verify(1, expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 204})
    void aSuccessIsDone(int status) {
        wire.stubFor(stub().willReturn(aResponse().withStatus(status)));

        assertThat(delete(base(), id())).isEqualTo(DeleteOutcome.DELETED);
    }

    @Test
    void notFoundMeansTheAimIsReached() {
        wire.stubFor(stub().willReturn(aResponse().withStatus(404)));

        assertThat(delete(base(), id())).isEqualTo(DeleteOutcome.ALREADY_GONE);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 502, 503})
    void everyOtherAnswerFailsWithTheStatusAndNothingElse(int status) {
        wire.stubFor(stub().willReturn(aResponse().withStatus(status).withBody("secret " + KEY)));

        assertThatThrownBy(() -> delete(base(), id())).isInstanceOfSatisfying(VendorCallFailed.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain(id()).doesNotContain("localhost");
        });
    }

    @Test
    void aRedirectIsNeverFollowedSoTheKeyGoesNowhereElse() {
        wire.stubFor(stub().willReturn(aResponse().withStatus(302).withHeader("Location", base() + "/elsewhere")));
        wire.stubFor(request("DELETE", urlEqualTo("/elsewhere"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> delete(base(), id()))
                .isInstanceOfSatisfying(
                        VendorCallFailed.class, e -> assertThat(e.status()).isEqualTo(302));
        wire.verify(
                0,
                new RequestPatternBuilder(
                        com.github.tomakehurst.wiremock.http.RequestMethod.ANY, urlEqualTo("/elsewhere")));
    }

    @Test
    void noAnswerFailsWithStatusMinusOne() {
        String dead = base();
        wire.stop();

        assertThatThrownBy(() -> delete(dead, id()))
                .isInstanceOfSatisfying(
                        VendorCallFailed.class, e -> assertThat(e.status()).isEqualTo(-1));
    }

    @Test
    void aSlowVendorFailsInsteadOfHangingTheListener() {
        wire.stubFor(stub().willReturn(aResponse().withStatus(200).withFixedDelay(12_000)));

        assertThatThrownBy(() -> delete(base(), id())).isInstanceOf(VendorCallFailed.class);
    }
}
