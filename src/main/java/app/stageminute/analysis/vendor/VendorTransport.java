package app.stageminute.analysis.vendor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One POST to a vendor, with the choices that keep it safe (AD-151): the body goes from memory (no file is written, so
 * a crash leaves no audio), redirects are never followed (a redirect would carry the key to another address), the whole
 * call, answer body included, ends at the time given, and a failure names its kind only, never an address, a key or
 * vendor text.
 */
final class VendorTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_ANSWER_BYTES = 8 * 1024 * 1024;

    /** What came back: the status and the body bytes. */
    @SuppressWarnings("ArrayRecordComponent") // read once, never compared
    record Reply(int status, byte[] body) {}

    /** How a call failed before any answer: it ran out of time, the network failed, or the thread was stopped. */
    enum Kind {
        TIMEOUT,
        NETWORK,
        INTERRUPTED
    }

    /** No answer. The message is the kind only. */
    static final class Failure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Kind kind;

        Failure(Kind kind) {
            super(kind.name(), null, false, false);
            this.kind = kind;
        }

        Kind kind() {
            return kind;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Sends the body and waits at most {@code timeoutMs} for the whole answer.
     *
     * @throws Failure when there is no answer
     */
    Reply post(URI uri, Map<String, String> headers, String contentType, byte[] body, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new Failure(Kind.TIMEOUT);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> reply = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (reply.body().length > MAX_ANSWER_BYTES) {
                throw new Failure(Kind.NETWORK);
            }
            return new Reply(reply.statusCode(), reply.body());
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new Failure(Kind.TIMEOUT);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure(Kind.INTERRUPTED);
        } catch (CancellationException e) {
            throw new Failure(Kind.INTERRUPTED);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw new Failure(Kind.TIMEOUT);
            }
            throw new Failure(Kind.NETWORK);
        }
    }
}
