package org.sitenetsoft.quarkus.libvips.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The packaged application (JVM and native) starts in production mode, where {@code quarkus.libvips.leak-check} runs
 * only with {@code quarkus.libvips.leak-check-in-production}; both are set for these tests, so the start-up line says
 * the check is on. Its report comes when the application stops, after the tests.
 */
@QuarkusIntegrationTest
class LeakCheckIT {

    @Test
    void theLeakCheckRunsInThePackagedApplication() throws IOException {
        String log = Files.readString(Path.of("target/quarkus.log"));
        assertThat(log).contains("quarkus.libvips.leak-check is on in production").contains(", leak-check on");
    }
}
