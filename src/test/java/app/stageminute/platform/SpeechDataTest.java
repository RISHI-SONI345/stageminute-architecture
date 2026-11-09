package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.MessageFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** AD-23: the transcript prints as [redacted] and refuses JSON. */
class SpeechDataTest {

    private static final String TEXT = "my secret transcript words";

    @Test
    void printsAsRedactedEverywhereItCouldBeLogged() {
        SpeechData data = new SpeechData(TEXT);

        assertThat(data.toString()).isEqualTo("[redacted]");
        assertThat("got " + data).isEqualTo("got [redacted]");
        assertThat(String.valueOf(data)).isEqualTo("[redacted]");
        assertThat(String.format("%s", data)).isEqualTo("[redacted]");
        assertThat(MessageFormat.format("{0}", data)).isEqualTo("[redacted]");
        assertThat(new RuntimeException("failed on " + data).getMessage()).doesNotContain(TEXT);
    }

    @Test
    void refusesToBecomeJsonAndTheErrorHoldsNoText() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThatThrownBy(() -> mapper.writeValueAsString(new SpeechData(TEXT)))
                .satisfies(error -> assertThat(messages(error)).doesNotContain(TEXT));
        assertThatThrownBy(() -> mapper.writeValueAsString(new Wrapper(new SpeechData(TEXT))))
                .satisfies(error -> assertThat(messages(error)).doesNotContain(TEXT));
    }

    @Test
    void revealGivesTheTextToCodeThatAsksOnPurpose() {
        assertThat(new SpeechData(TEXT).reveal()).isEqualTo(TEXT);
        assertThat(new SpeechData(TEXT)).isEqualTo(new SpeechData(TEXT)).hasSameHashCodeAs(new SpeechData(TEXT));
    }

    record Wrapper(SpeechData speech) {}

    private static String messages(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
