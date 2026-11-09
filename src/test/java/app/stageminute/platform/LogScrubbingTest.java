package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * F-081 / AD-23: no multipart body and no transcript ever reaches a log line, even with the loudest log
 * level switched on, and every line of a request carries its request id as one JSON object.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(LogScrubbingTest.Probe.class)
// A loud root level. Tomcat's wire loggers, Spring's argument and body loggers print requests and exception texts
// at DEBUG or TRACE; application.yml pins them quiet, and this test proves the pins hold.
@TestPropertySource(properties = "logging.level.root=DEBUG")
class LogScrubbingTest extends DatabaseTest {

    private static final String AUDIO = "AUDIO-BYTES-9f3c2a";
    private static final String TRANSCRIPT = "TRANSCRIPT-WORDS-7d41b8";
    private static final String PROMPT = "PROMPT-TEXT-55aa01";

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @RestController
        static class UploadController {
            @PostMapping("/probe/json")
            String json(@RequestBody JsonBody body) {
                return body.transcript().length() + "";
            }

            @PostMapping("/probe/upload")
            String upload(@RequestParam("recording") MultipartFile recording, @RequestParam String transcript) {
                throw new IllegalStateException("could not analyse " + transcript);
            }
        }
    }

    record JsonBody(String transcript, String prompt) {}

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    /** Everything except health needs a signed-in person now; the probes sign in as a test user. */
    private String bearer() {
        return "Bearer " + FakeClerk.token("user_probe", clock.instant()).sign();
    }

    private HttpResponse<String> upload(String requestId) throws Exception {
        String boundary = "----probe" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"recording\"; filename=\"take.m4a\"\r\n"
                + "Content-Type: audio/mp4\r\n\r\n"
                + AUDIO + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"transcript\"\r\n\r\n"
                + TRANSCRIPT + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n"
                + PROMPT + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + env.getRequiredProperty("local.server.port") + "/probe/upload"))
                .header("Authorization", bearer())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noBodyAudioTranscriptOrPromptReachesTheLogs(CapturedOutput output) throws Exception {
        HttpResponse<String> reply = upload("req-scrub-0001");

        assertThat(reply.statusCode()).isEqualTo(500);
        assertThat(reply.body()).doesNotContain(TRANSCRIPT).doesNotContain(AUDIO);
        // Not the error text of the plan-less exception either: only the class and stack are logged.
        String logs = output.getAll();
        assertThat(logs).doesNotContain(AUDIO).doesNotContain(TRANSCRIPT).doesNotContain(PROMPT);
    }

    @Test
    void theErrorLineIsOneJsonObjectWithTheRequestId(CapturedOutput output) throws Exception {
        HttpResponse<String> reply = upload("req-scrub-0002");

        assertThat(reply.headers().firstValue("X-Request-Id")).contains("req-scrub-0002");
        assertThat(reply.body()).contains("\"request_id\":\"req-scrub-0002\"");
        List<String> errorLines = output.getAll()
                .lines()
                .filter(line -> line.contains("Unplanned error"))
                .toList();
        assertThat(errorLines).isNotEmpty().allSatisfy(line -> {
            assertThat(line).startsWith("{").endsWith("}");
            assertThat(line).contains("\"requestId\":\"req-scrub-0002\"");
        });
    }

    @Test
    void aJsonBodyIsNotLoggedEither(CapturedOutput output) throws Exception {
        String body = "{\"transcript\":\"" + TRANSCRIPT + "\",\"prompt\":\"" + PROMPT + "\"}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + env.getRequiredProperty("local.server.port") + "/probe/json"))
                .header("Authorization", bearer())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> reply = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain(TRANSCRIPT).doesNotContain(PROMPT);
    }

    @Test
    void theRequestIdIsOnEveryKindOfAnswer() throws Exception {
        String base = "http://localhost:" + env.getRequiredProperty("local.server.port");
        for (String path : new String[] {"/no/such/path", "/probe/upload"}) {
            HttpResponse<String> reply = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(base + path))
                                    .header("Authorization", bearer())
                                    .header("X-Request-Id", "req-every-0001")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(reply.statusCode()).as(path).isIn(404, 405);
            assertThat(reply.headers().firstValue("X-Request-Id")).as(path).contains("req-every-0001");
            assertThat(reply.body()).as(path).contains("\"request_id\":\"req-every-0001\"");
        }
        // A request with no token is refused by sign-in itself, before any controller; it carries the id too.
        HttpResponse<String> refused = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(base + "/no/such/path"))
                                .header("X-Request-Id", "req-every-0002")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("X-Request-Id")).contains("req-every-0002");
        assertThat(refused.body()).contains("\"request_id\":\"req-every-0002\"");
    }
}
