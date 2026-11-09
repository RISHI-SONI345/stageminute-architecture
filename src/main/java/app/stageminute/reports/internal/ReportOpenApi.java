package app.stageminute.reports.internal;

import app.stageminute.reports.ReportV2;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Puts Report v2 and every type it holds into the OpenAPI file, so the app's typed client has the report shape even
 * where an endpoint returns it inside a wrapper the generator cannot see (AD-126). The endpoints that return it
 * ({@code POST /v1/analyze}, {@code GET /v1/analyses/{id}}) reference the same {@code Report} schema.
 */
@Configuration(proxyBeanMethods = false)
class ReportOpenApi {

    /** Runs before the platform step that marks the required fields. */
    @Bean
    OpenApiCustomizer reportSchemas() {
        return new FirstCustomizer();
    }

    private static final class FirstCustomizer implements OpenApiCustomizer, Ordered {
        @Override
        public void customise(OpenAPI openApi) {
            SpringDocAnnotationsUtils.extractSchema(
                    openApi.getComponents(), ReportV2.class, null, null, SpecVersion.V31);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
