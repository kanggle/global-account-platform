package com.example.e2e;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.time.Duration;

/**
 * JUnit 5 extension that boots docker-compose.e2e.yml exactly once per JVM and
 * keeps it running for the whole test suite (per {@code docker-compose}
 * semantics, not per class — this is critical to hit the 5-minute wall clock
 * budget declared in TASK-BE-041c §7).
 *
 * <p>Per-class isolation is achieved in {@link E2EBase#resetDataBetweenClasses()}
 * through TRUNCATE statements rather than compose recreation.
 */
public final class ComposeFixture implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static final Object LOCK = new Object();
    private static ComposeContainer COMPOSE;
    private static boolean STARTED = false;

    // Ports mapped in docker-compose.e2e.yml.
    public static final int ADMIN_PORT = 18085;
    public static final int AUTH_PORT = 18081;
    public static final int ACCOUNT_PORT = 18082;
    public static final int SECURITY_PORT = 18084;
    public static final int MYSQL_PORT = 3306;
    public static final int KAFKA_PORT = 9092;

    public static final String HOST = "127.0.0.1";

    public static final String ADMIN_BASE_URL = "http://" + HOST + ":" + ADMIN_PORT;
    public static final String ACCOUNT_BASE_URL = "http://" + HOST + ":" + ACCOUNT_PORT;
    public static final String SECURITY_BASE_URL = "http://" + HOST + ":" + SECURITY_PORT;

    @Override
    public void beforeAll(ExtensionContext context) {
        start();
        // Register this resource with the root store so close() runs once the
        // test JVM shuts down — effectively compose down after the suite.
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .put("compose-fixture-singleton", this);
    }

    public static void start() {
        synchronized (LOCK) {
            if (STARTED) return;
            File composeFile = locateComposeFile();
            COMPOSE = new ComposeContainer(composeFile)
                    .withLocalCompose(true)
                    .withPull(false)
                    .waitingFor("mysql",
                            Wait.forLogMessage(".*ready for connections.*\\n", 1)
                                    .withStartupTimeout(Duration.ofMinutes(3)))
                    .waitingFor("kafka",
                            Wait.forLogMessage(".*Kafka Server started.*\\n", 1)
                                    .withStartupTimeout(Duration.ofMinutes(3)))
                    .waitingFor("auth-service",
                            Wait.forHttp("/actuator/health").forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(4)))
                    .waitingFor("account-service",
                            Wait.forHttp("/actuator/health").forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(4)))
                    .waitingFor("security-service",
                            Wait.forHttp("/actuator/health").forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(4)))
                    .waitingFor("admin-service",
                            Wait.forHttp("/actuator/health").forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(4)));
            COMPOSE.start();
            STARTED = true;
            Runtime.getRuntime().addShutdownHook(new Thread(ComposeFixture::stopQuietly, "compose-shutdown"));
        }
    }

    private static void stopQuietly() {
        try {
            if (COMPOSE != null) COMPOSE.stop();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static File locateComposeFile() {
        // Tests can run from :tests:e2e working dir or from the root; walk up
        // until we find the compose file.
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "docker-compose.e2e.yml");
            if (candidate.isFile()) return candidate;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("docker-compose.e2e.yml not found via directory walk");
    }

    @Override
    public void close() {
        // Suite-wide teardown. No-op here — JVM shutdown hook handles stop()
        // to avoid premature shutdown if JUnit creates multiple stores.
    }

    /** External Kafka bootstrap for host-side producers/consumers (DLQ scenario). */
    public static final String KAFKA_BOOTSTRAP_HOST = "127.0.0.1:19092";

    /** Host-mapped MySQL port (docker-compose.e2e.yml). */
    public static final int MYSQL_HOST_PORT = 13306;

    public static String mysqlJdbcUrl(String db, String user, String password) {
        return "jdbc:mysql://" + HOST + ":" + MYSQL_HOST_PORT + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&user="
                + user + "&password=" + password;
    }
}
