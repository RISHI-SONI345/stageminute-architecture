package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Request id on every request: header, logging context, cleared afterwards. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private record Seen(String inContext, String onResponse) {}

    private Seen run(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        if (incoming != null) {
            request.addHeader("X-Request-Id", incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inContext = new AtomicReference<>();
        FilterChain chain = (req, res) -> inContext.set(MDC.get("requestId"));
        filter.doFilter(request, response, chain);
        assertThat(MDC.get("requestId")).as("cleared after the request").isNull();
        return new Seen(inContext.get(), response.getHeader("X-Request-Id"));
    }

    @Test
    void makesAnIdWhenNoneArrives() throws Exception {
        Seen seen = run(null);

        assertThat(seen.inContext()).isNotBlank().isEqualTo(seen.onResponse());
        assertThat(seen.onResponse()).hasSize(36);
    }

    @Test
    void keepsAWellFormedIdFromTheCaller() throws Exception {
        Seen seen = run("abc-123_DEF.456");

        assertThat(seen.inContext()).isEqualTo("abc-123_DEF.456");
        assertThat(seen.onResponse()).isEqualTo("abc-123_DEF.456");
    }

    @Test
    void replacesAnIdThatCouldBreakALogLine() throws Exception {
        for (String bad :
                new String[] {"short", "has space in it 123", "line\nbreak-123456", "x".repeat(65), "{\"a\":1}12345"}) {
            Seen seen = run(bad);

            assertThat(seen.onResponse()).as(bad).isNotEqualTo(bad).hasSize(36);
        }
    }

    @Test
    void userIdIsClearedToo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> RequestContext.bindUser("user_abc12345"));

        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void anEmailIsNotAnOpaqueUserId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RequestContext.bindUser("rishi@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("rishi");
    }
}
