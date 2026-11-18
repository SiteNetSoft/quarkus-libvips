package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code quarkus.libvips.leak-check=true} (test mode), the start-up line says so and stopping the application
 * logs a WARN naming the image that was never closed.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class LeakCheckTest {

    /**
     * What the runtime logs, through the application's shutdown. The logger is process-wide; this class (as JUnit
     * loads it, not the application's copy) holds the records.
     */
    static final List<LogRecord> SHUTDOWN_RECORDS = new CopyOnWriteArrayList<>();
    static final Logger RUNTIME_LOGGER = Logger.getLogger("org.sitenetsoft.quarkus.libvips.internal.VipsRuntime");
    static final Handler CAPTURE = new Handler() {
        @Override
        public void publish(LogRecord record) {
            SHUTDOWN_RECORDS.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.libvips.leak-check", "true")
            .setBeforeAllCustomizer(() -> RUNTIME_LOGGER.addHandler(CAPTURE))
            .setLogRecordPredicate(r -> r.getLoggerName() != null
                    && r.getLoggerName().startsWith("org.sitenetsoft.quarkus.libvips"))
            .assertLogRecords(records -> {
                assertThat(records).as("the start-up line").anySatisfy(r -> {
                    assertThat(r.getLevel().intValue()).isEqualTo(Level.INFO.intValue());
                    assertThat(r.getParameters()).isNotEmpty();
                    assertThat(r.getParameters()[r.getParameters().length - 1]).isEqualTo(", leak-check on");
                });
            })
            // the report comes when the application stops, after the records above are taken
            .setAfterUndeployListener(() -> {
                RUNTIME_LOGGER.removeHandler(CAPTURE);
                assertThat(SHUTDOWN_RECORDS).as("the report at shutdown").anySatisfy(r -> {
                    assertThat(r.getLevel().intValue()).isEqualTo(Level.WARNING.intValue());
                    assertThat(r.getMessage()).startsWith("libvips leak check: 1 image(s) still open, 0 released by"
                            + " the cleaner (never closed)");
                });
            });

    /** Kept open on purpose until the application stops. */
    static Image kept;

    @Inject
    Libvips libvips;

    @Test
    void anImageLeftOpenIsReportedAtShutdown() throws Exception {
        kept = libvips.load(Files.readAllBytes(Path.of("../runtime/src/test/resources/portrait.jpg")));
        assertThat(libvips.openImages()).isEqualTo(1);
    }
}
