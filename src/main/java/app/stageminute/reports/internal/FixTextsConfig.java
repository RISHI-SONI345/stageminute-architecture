package app.stageminute.reports.internal;

import app.stageminute.reports.FixTexts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the Fix Library texts once, at start, from the path in {@code stageminute.content.fixes-path} (C-70). The
 * default, {@code classpath:content-fixes/en.json}, is the copy Gradle puts into the jar from {@code content/fixes}
 * (the same way as the topics, AD-103), so it works from {@code server/} and in the Docker image. Only {@code
 * classpath:} and {@code file:} paths are read, never a web address. A blank or other path, a path that names no file,
 * or a bad file throws here, so the server does not start (fails closed).
 */
@Configuration(proxyBeanMethods = false)
class FixTextsConfig {

    private static final String PROPERTY = "stageminute.content.fixes-path";

    @Bean
    FixTexts fixTexts(ResourceLoader resources, @Value("${" + PROPERTY + ":}") String path, JsonMapper json) {
        if (path.isBlank()) {
            throw new IllegalStateException(PROPERTY + " is not set: the server needs the fix texts");
        }
        if (!path.strip().startsWith("classpath:") && !path.strip().startsWith("file:")) {
            throw new IllegalStateException(PROPERTY + " must start with classpath: or file:");
        }
        Resource file = resources.getResource(path.strip());
        if (!file.exists()) {
            throw new IllegalStateException(PROPERTY + " names no file: " + path.strip());
        }
        try {
            return FixTexts.parse(file.getContentAsString(StandardCharsets.UTF_8), json);
        } catch (IOException e) {
            throw new IllegalStateException(PROPERTY + " cannot be read: " + path.strip(), e);
        }
    }
}
