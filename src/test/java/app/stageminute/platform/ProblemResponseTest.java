package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every problem response carries the request id, in the header and as request_id (AD-08, AD-86). */
class ProblemResponseTest {

    @RestController
    static class Boom {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret transcript words");
        }

        @GetMapping("/needs")
        String needs(@RequestParam String required) {
            return required;
        }

        @PostMapping("/only-get-here")
        String post() {
            return "ok";
        }
    }

    private final java.util.List<Throwable> reported = new java.util.ArrayList<>();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Boom())
            .setControllerAdvice(new ProblemAdvice(reported::add, new Settings(new ConfigStore() {
                @Override
                public java.util.Optional<String> settingValue(String key) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<Boolean> flagEnabled(String key) {
                    return java.util.Optional.empty();
                }
            })))
            .addFilters(new RequestIdFilter())
            .build();

    @Test
    void anUnplannedErrorIsAPlain500WithTheRequestIdAndNoMessage() throws Exception {
        MvcResult result = mvc.perform(get("/boom").header("X-Request-Id", "req-abcdef12345"))
                .andExpect(header().string("X-Request-Id", "req-abcdef12345"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.request_id").value("req-abcdef12345"))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("problem+json");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret transcript words");
        assertThat(reported).singleElement().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBadRequestCarriesTheRequestId() throws Exception {
        mvc.perform(get("/needs"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void aWrongMethodCarriesTheRequestId() throws Exception {
        mvc.perform(get("/only-get-here").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.request_id").isNotEmpty());
    }

    @Test
    void theIdInTheBodyIsTheIdInTheHeader() throws Exception {
        MvcResult result = mvc.perform(post("/boom")).andReturn();

        String header = result.getResponse().getHeader("X-Request-Id");
        assertThat(result.getResponse().getContentAsString()).contains(header);
    }
}
