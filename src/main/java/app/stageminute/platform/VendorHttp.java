package app.stageminute.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The one way the server calls an outside service to delete a person (Clerk, RevenueCat, PostHog). Choices that keep
 * it safe: the address must be https (plain http only to this machine, so a test can run its own server), the key
 * goes in a bearer header and nowhere else, short time-outs, redirects are never followed (a redirect would carry the
 * key to another address), and a failure carries the vendor name and the status only.
 */
public final class VendorHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int OK_FROM = 200;
    private static final int OK_BELOW = 300;
    private static final int NOT_FOUND = 404;

    private VendorHttp() {}

    /**
     * Stops the server at start, naming the setting but never its value, when a vendor address is not https (or
     * plain http to this machine).
     */
    public static String requireSafeBase(String setting, String url) {
        URI uri = URI.create(url);
        boolean https = "https".equals(uri.getScheme());
        boolean loopback = "http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!https && !loopback) {
            throw new IllegalStateException(setting + " must be an https address");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** A client for one vendor: its base address and its key as a bearer token. */
    public static RestClient client(String baseUrl, String secret) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                .build();
    }

    /**
     * Sends the request and turns the answer into an outcome: 2xx is {@link DeleteOutcome#DELETED}, 404 is {@link
     * DeleteOutcome#ALREADY_GONE}, everything else (429, 5xx, a refused key, no answer) is a {@link VendorCallFailed}.
     */
    public static DeleteOutcome send(String vendor, RestClient.RequestHeadersSpec<?> request) {
        int status;
        try {
            status = request.exchange((req, res) -> res.getStatusCode().value());
        } catch (RestClientException e) {
            throw new VendorCallFailed(vendor, -1);
        }
        if (status >= OK_FROM && status < OK_BELOW) {
            return DeleteOutcome.DELETED;
        }
        if (status == NOT_FOUND) {
            return DeleteOutcome.ALREADY_GONE;
        }
        throw new VendorCallFailed(vendor, status);
    }
}
