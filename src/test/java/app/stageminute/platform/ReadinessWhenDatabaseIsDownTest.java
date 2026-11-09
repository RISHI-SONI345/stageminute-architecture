package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import app.stageminute.support.HttpProbe;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Gap G3: when the database goes away while the server runs, the server says "not ready" (so the deploy and the
 * uptime check stop trusting it) but stays "alive" (so nothing restarts it for a database problem).
 */
@Import(ReadinessWhenDatabaseIsDownTest.CutTheDatabase.class)
class ReadinessWhenDatabaseIsDownTest extends DatabaseTest {

    static final AtomicBoolean DATABASE_CUT = new AtomicBoolean();

    /** Wraps the application's data source so a test can make every connection attempt fail. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CutTheDatabase {
        @Bean
        static BeanPostProcessor databaseSwitch() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            DataSource.class.getClassLoader(),
                            new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                if (DATABASE_CUT.get() && method.getName().equals("getConnection")) {
                                    throw new SQLException("database is cut for this test");
                                }
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }
    }

    @Autowired
    Environment env;

    private int status(String path) throws Exception {
        return HttpProbe.get(Integer.parseInt(env.getRequiredProperty("local.server.port")), path)
                .status();
    }

    @AfterEach
    void putTheDatabaseBack() {
        DATABASE_CUT.set(false);
    }

    @Test
    void readyAndAliveWhileTheDatabaseIsThere() throws Exception {
        assertThat(status("/actuator/health/readiness")).isEqualTo(200);
        assertThat(status("/actuator/health/liveness")).isEqualTo(200);
    }

    @Test
    void notReadyButStillAliveWhenTheDatabaseIsGone() throws Exception {
        DATABASE_CUT.set(true);

        assertThat(status("/actuator/health/readiness")).isEqualTo(503);
        assertThat(status("/actuator/health/liveness")).isEqualTo(200);
        assertThat(status("/actuator/health")).isEqualTo(503);
    }

    @Test
    void readyAgainWhenTheDatabaseComesBack() throws Exception {
        DATABASE_CUT.set(true);
        assertThat(status("/actuator/health/readiness")).isEqualTo(503);

        DATABASE_CUT.set(false);

        assertThat(status("/actuator/health/readiness")).isEqualTo(200);
    }
}
