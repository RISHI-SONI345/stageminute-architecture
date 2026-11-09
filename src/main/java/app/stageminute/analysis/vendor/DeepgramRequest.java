package app.stageminute.analysis.vendor;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The one place that builds the Deepgram request address (05 F-080, 03 F-027). {@code mip_opt_out=true} and {@code
 * filler_words=true} are written here as fixed text, so no other code path can leave them out; a test scans the client
 * sources to prove that nothing else builds a Deepgram address.
 *
 * <p>The other options are the ones the requirements need to be answerable at all: {@code punctuate} and {@code
 * paragraphs} (F-027 keeps {@code paragraphs.sentences}) and {@code detect_language} (the signal behind {@code
 * not_english}, step3/CONFLICTS.md C-28 item 6 and C-84).
 */
final class DeepgramRequest {

    static final String PATH = "/v1/listen";
    static final String CONTENT_TYPE = "audio/mp4";

    private DeepgramRequest() {}

    /** Address of one transcription call. */
    static URI uri(String baseUrl, String model) {
        String query = "model=" + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + "&mip_opt_out=true"
                + "&filler_words=true"
                + "&punctuate=true"
                + "&paragraphs=true"
                + "&detect_language=true";
        return URI.create(baseUrl + PATH + "?" + query);
    }
}
