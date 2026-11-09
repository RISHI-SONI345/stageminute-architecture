package app.stageminute.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A planned refusal leaves as a problem with a reason code, never a sentence for the user (06-API §1). */
class BusinessRefusalTest {

    @RestController
    static class Refuses {
        @GetMapping("/zero")
        String zero() {
            throw new BusinessRefusal(Reason.QUOTA_ZERO, Map.of("resets_at", "2026-10-20T18:30:00Z"));
        }

        @GetMapping("/deleted")
        String deleted() {
            throw new BusinessRefusal(Reason.ACCOUNT_DELETED);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Refuses())
            .setControllerAdvice(new ProblemAdvice(error -> {}, new Settings(new ConfigStore() {
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
    void theReasonCodeAndStatusComeFromTheTable() throws Exception {
        mvc.perform(get("/zero").header("X-Request-Id", "req-abcdef12345"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("quota_zero"))
                .andExpect(jsonPath("$.type").value("https://stageminute.app/problems/quota_zero"))
                .andExpect(jsonPath("$.request_id").value("req-abcdef12345"))
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    void extraFieldsAppearOnlyWhenTheRefusalCarriesThem() throws Exception {
        mvc.perform(get("/zero")).andExpect(jsonPath("$.resets_at").value("2026-10-20T18:30:00Z"));
        mvc.perform(get("/deleted"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("account_deleted"))
                .andExpect(jsonPath("$.resets_at").doesNotExist());
    }

    @Test
    void everyReasonHasAUniqueCodeAndAKnownStatus() {
        List<String> codes =
                java.util.Arrays.stream(Reason.values()).map(Reason::code).toList();

        org.assertj.core.api.Assertions.assertThat(codes).doesNotHaveDuplicates();
        for (Reason reason : Reason.values()) {
            org.assertj.core.api.Assertions.assertThat(reason.status().value()).isBetween(400, 599);
            org.assertj.core.api.Assertions.assertThat(reason.code()).matches("[a-z_]+");
        }
    }
}
