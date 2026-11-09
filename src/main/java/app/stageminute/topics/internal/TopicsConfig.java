package app.stageminute.topics.internal;

import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import app.stageminute.topics.internal.TopicFileLoader.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Builds the topic catalog once, at startup, from the files Gradle copied from {@code content/topics} into the jar
 * (AD-103). A bad file throws here, so the server does not start (F-014).
 */
@Configuration(proxyBeanMethods = false)
class TopicsConfig {

    private static final String FOLDER = "classpath:content-topics/";

    @Bean
    TopicCatalog topicCatalog(
            Settings settings,
            ResourcePatternResolver resources,
            @Value("${stageminute.topics.enforce-min:}") String enforce)
            throws IOException {
        TopicSettings topicSettings = new TopicSettings(
                settings.get(SettingKey.TOPICS_RAMP_SESSIONS), settings.get(SettingKey.TOPICS_MIN_PER_CELL));
        List<SourceFile> files = Arrays.stream(resources.getResources(FOLDER + "*.json"))
                .filter(r -> !"ids.json".equals(r.getFilename()))
                .sorted(Comparator.comparing(Resource::getFilename))
                .map(TopicsConfig::read)
                .toList();
        return TopicFileLoader.load(
                files,
                text(resources.getResource(FOLDER + "ids.json")),
                text(resources.getResource(FOLDER + "schema/topic.schema.json")),
                topicSettings,
                "1".equals(enforce.strip()));
    }

    private static SourceFile read(Resource resource) {
        return new SourceFile(resource.getFilename(), text(resource));
    }

    private static String text(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the topic file " + resource.getFilename(), e);
        }
    }
}
