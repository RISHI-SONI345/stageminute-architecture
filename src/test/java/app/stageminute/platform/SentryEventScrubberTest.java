package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.JsonSerializer;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The gate for "nothing personal reaches Sentry" (AD-23): an event full of audio, transcript, prompt and
 * email goes through the scrubber, and none of it is left in what would be sent.
 */
class SentryEventScrubberTest {

    private static final String[] PERSONAL = {
        "TRANSCRIPT-WORDS",
        "PROMPT-TEXT",
        "AUDIO-BYTES",
        "rishi@example.com",
        "203.0.113.7",
        "SESSION-COOKIE",
        "SECRET-TOKEN"
    };

    private final SentryEventScrubber scrubber = new SentryEventScrubber();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private SentryEvent dirtyEvent() {
        SentryEvent event =
                new SentryEvent(new IllegalStateException("could not read TRANSCRIPT-WORDS for rishi@example.com"));
        SentryException exception = new SentryException();
        exception.setType("IllegalStateException");
        exception.setValue("could not read TRANSCRIPT-WORDS for rishi@example.com");
        event.setExceptions(List.of(exception));

        Request request = new Request();
        request.setUrl("https://api.stageminute.app/analyze?prompt=PROMPT-TEXT");
        request.setQueryString("prompt=PROMPT-TEXT");
        request.setData("AUDIO-BYTES TRANSCRIPT-WORDS");
        request.setCookies("session=SESSION-COOKIE");
        request.setHeaders(Map.of("Authorization", "Bearer SECRET-TOKEN", "X-Forwarded-For", "203.0.113.7"));
        request.setEnvs(Map.of("REMOTE_ADDR", "203.0.113.7"));
        event.setRequest(request);

        User user = new User();
        user.setId("rishi@example.com");
        user.setEmail("rishi@example.com");
        user.setUsername("rishi");
        user.setIpAddress("203.0.113.7");
        event.setUser(user);

        Message message = new Message();
        message.setMessage("Failed on {}");
        message.setFormatted("Failed on TRANSCRIPT-WORDS");
        message.setParams(List.of("TRANSCRIPT-WORDS", "PROMPT-TEXT"));
        event.setMessage(message);

        event.setExtra("transcript", "TRANSCRIPT-WORDS");
        event.setExtra("audio", "AUDIO-BYTES");
        event.addBreadcrumb(new Breadcrumb("sent PROMPT-TEXT to rishi@example.com"));
        event.setServerName("rishi-pc");
        event.setTag("who", "rishi@example.com");
        event.setTransaction("POST /analyze/PROMPT-TEXT");
        event.getContexts().put("mdc", Map.of("note", "TRANSCRIPT-WORDS"));
        event.getContexts().put("os", Map.of("name", "Windows"));
        return event;
    }

    private static String serialized(SentryEvent event) throws Exception {
        StringWriter out = new StringWriter();
        new JsonSerializer(new SentryOptions()).serialize(event, out);
        return out.toString();
    }

    @Test
    void nothingPersonalIsLeftInTheEvent() throws Exception {
        SentryEvent event = scrubber.execute(dirtyEvent(), new Hint());

        assertThat(event).isNotNull();
        String json = serialized(event);
        for (String personal : PERSONAL) {
            assertThat(json).as(personal).doesNotContain(personal);
        }
        assertThat(event.getRequest()).isNull();
        assertThat(event.getUser()).isNull();
        assertThat(event.getBreadcrumbs()).isNullOrEmpty();
        assertThat(event.getExtras()).isNullOrEmpty();
        assertThat(event.getTags()).isEmpty();
        assertThat(event.getTransaction()).isNull();
        assertThat(java.util.Collections.list(event.getContexts().keys())).containsExactly("os");
    }

    @Test
    void keepsTheErrorTypeSoTheBugCanStillBeFound() {
        SentryEvent event = scrubber.execute(dirtyEvent(), new Hint());

        assertThat(event.getExceptions()).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("IllegalStateException");
            assertThat(e.getValue()).isNull();
        });
        assertThat(event.getMessage().getMessage()).isEqualTo("Failed on {}");
    }

    @Test
    void sendsOnlyAnOpaqueUserIdAndTheRequestId() {
        MDC.put("requestId", "req-1234567890");
        MDC.put("userId", "user_2abcDEF123");

        SentryEvent event = scrubber.execute(new SentryEvent(), new Hint());

        assertThat(event.getUser()).isNotNull();
        assertThat(event.getUser().getId()).isEqualTo("user_2abcDEF123");
        assertThat(event.getUser().getEmail()).isNull();
        assertThat(event.getUser().getIpAddress()).isNull();
        assertThat(event.getUser().getUsername()).isNull();
        assertThat(event.getTags()).containsEntry("request_id", "req-1234567890");
    }

    @Test
    void dropsTheEventThatProblemAdviceLogsBecauseSentryAlreadyHasTheError() {
        SentryEvent logged = new SentryEvent();
        logged.setLogger(ProblemAdvice.class.getName());

        assertThat(scrubber.execute(logged, new Hint())).isNull();
    }

    @Test
    void theStackOnlyCopyKeepsClassesAndStackButNoMessages() {
        Exception original =
                new IllegalStateException("outer TRANSCRIPT-WORDS", new RuntimeException("inner PROMPT-TEXT"));

        Throwable copy = ProblemAdvice.StackOnly.of(original);

        assertThat(copy.getMessage()).isEqualTo("java.lang.IllegalStateException");
        assertThat(copy.getCause()).hasMessage("java.lang.RuntimeException");
        assertThat(copy.getStackTrace()).isEqualTo(original.getStackTrace());
    }
}
