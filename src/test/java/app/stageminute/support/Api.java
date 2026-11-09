package app.stageminute.support;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/** A tiny HTTP caller for tests that talk to the running server like the app does. */
public final class Api {

    public record Reply(int status, String body, java.net.http.HttpHeaders headers) {
        /** The text value at this JSON path, for example {@code $.reason}. */
        public String text(String path) {
            return JsonPath.read(body, path);
        }

        /** Any value at this JSON path (number, boolean, list, or null). */
        public Object value(String path) {
            return JsonPath.read(body, path);
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private final int port;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public Api(int port) {
        this.port = port;
    }

    /** A copy of this caller that also sends this header on every request. */
    public Api with(String name, String value) {
        Api copy = new Api(port);
        copy.headers.putAll(headers);
        copy.headers.put(name, value);
        return copy;
    }

    public Api bearer(String token) {
        return with("Authorization", "Bearer " + token);
    }

    public Reply get(String path) {
        return send("GET", path, null);
    }

    public Reply post(String path, String json) {
        return send("POST", path, json);
    }

    public Reply patch(String path, String json) {
        return send("PATCH", path, json);
    }

    public Reply delete(String path) {
        return send("DELETE", path, null);
    }

    public Reply put(String path, String json) {
        return send("PUT", path, json);
    }

    /**
     * A multipart POST as the phone sends {@code /v1/analyze}: a file part {@code audio} (audio/mp4) and, when not
     * null, a text part {@code meta} (the phone's FormData sends it with no content type).
     */
    public Reply postAudio(String path, byte[] audio, String meta) {
        String boundary = "sm-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"take.m4a\""
                        + "\r\nContent-Type: audio/mp4\r\n\r\n")
                .getBytes(utf8));
        body.writeBytes(audio);
        if (meta != null) {
            body.writeBytes(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n" + meta)
                    .getBytes(utf8));
        }
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(utf8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(request::header);
        request.header("Content-Type", "multipart/form-data; boundary=" + boundary);
        request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return exchange(request);
    }

    public Reply send(String method, String path, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(request::header);
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(
                method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return exchange(request);
    }

    private static Reply exchange(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response =
                    HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException("The test server did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
