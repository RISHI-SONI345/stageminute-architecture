package app.stageminute.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The frame of the generated OpenAPI file (AD-08): title, the Clerk bearer token every endpoint uses, the
 * problem body every error has, a 401 on every operation and a 400 on every operation that takes a body. The
 * paths and bodies come from the controllers. The file is committed as {@code server/openapi.json}; the app's
 * typed client is generated from it, and {@code OpenApiDriftTest} fails when the code and the file disagree.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER = "clerkSession";

    @Bean
    OpenAPI stageMinuteApi() {
        return new OpenAPI()
                .info(new Info().title("StageMinute API").version("1")) // copy-ok: API title for developers
                // Paths already start with /v1; a fixed server keeps the file the same on every machine.
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Clerk session token from getToken()")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Runs last, so the schemas other modules add are marked too. */
    @Bean
    OpenApiCustomizer problemResponses() {
        return new LastCustomizer(openApi -> {
            // Added here, not in the OpenAPI bean: springdoc rebuilds the schema list after the bean.
            openApi.getComponents().addSchemas("Problem", problemSchema());
            markRequiredFields(openApi);
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(operation -> {
                        operation
                                .getResponses()
                                .addApiResponse("401", problem("No usable session token (reason: session_expired)"));
                        if (operation.getRequestBody() != null) {
                            operation
                                    .getResponses()
                                    .addApiResponse("400", problem("Invalid body (reason: validation_failed)"));
                        }
                    }));
        });
    }

    /** An {@link OpenApiCustomizer} that springdoc applies after every other one. */
    private record LastCustomizer(OpenApiCustomizer step) implements OpenApiCustomizer, Ordered {
        @Override
        public void customise(io.swagger.v3.oas.models.OpenAPI openApi) {
            step.customise(openApi);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /**
     * In a response every field is always there unless it is marked nullable, and springdoc does not read
     * {@code @NullMarked}; without this the app's generated types would make every field optional. Request
     * bodies (named ...Request) stay optional because the server checks them and answers with the field name.
     */
    private static void markRequiredFields(io.swagger.v3.oas.models.OpenAPI openApi) {
        for (var entry : wildcard(openApi.getComponents().getSchemas()).entrySet()) {
            Schema<?> schema = entry.getValue();
            if (entry.getKey().endsWith("Request")
                    || "Problem".equals(entry.getKey())
                    || schema.getProperties() == null) {
                continue;
            }
            for (var property : wildcard(schema.getProperties()).entrySet()) {
                if (!nullable(property.getValue())) {
                    schema.addRequiredItem(property.getKey());
                }
            }
        }
    }

    /** swagger-models hands out raw {@code Schema} maps; this is the one place that says so. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static java.util.Map<String, Schema<?>> wildcard(java.util.Map<String, Schema> raw) {
        return (java.util.Map) raw;
    }

    private static boolean nullable(Schema<?> definition) {
        return Boolean.TRUE.equals(definition.getNullable())
                || (definition.getTypes() != null && definition.getTypes().contains("null"));
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                "application/problem+json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))));
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem. The app shows the copy mapped from `reason`, never `title`.")
                .addProperty("type", new StringSchema())
                .addProperty("title", new StringSchema())
                .addProperty("status", new Schema<Integer>().type("integer"))
                .addProperty("reason", new StringSchema())
                .addProperty("cause", new StringSchema().description("`expired`, only on session_expired"))
                .addProperty("request_id", new StringSchema())
                .addProperty("fields", new ArraySchema().items(new StringSchema()))
                .addRequiredItem("status");
    }
}
