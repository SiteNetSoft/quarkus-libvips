package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.logging.Level;

import jakarta.inject.Inject;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;

import io.quarkus.test.QuarkusExtensionTest;

/** With {@code required=false} a missing library leaves the application running without libvips. */
class NotRequiredTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.library", "/nonexistent/libvips.so.42")
            .overrideRuntimeConfigKey("quarkus.libvips.required", "false")
            .setLogRecordPredicate(r -> r.getLoggerName() != null
                    && r.getLoggerName().startsWith("org.sitenetsoft.quarkus.libvips")
                    && r.getLevel().intValue() >= Level.WARNING.intValue())
            .assertLogRecords(records -> assertThat(records).singleElement().satisfies(r -> {
                assertThat(r.getLevel().intValue()).isEqualTo(Level.WARNING.intValue());
                assertThat(((ExtLogRecord) r).getFormattedMessage()).contains("quarkus.libvips.required=false")
                        .contains("/nonexistent/libvips.so.42");
            }));

    @Inject
    Libvips libvips;

    @Test
    void startsWithoutLibvips() {
        assertThat(libvips.isAvailable()).isFalse();
        Libvips.Status status = libvips.status();
        assertThat(status.available()).isFalse();
        assertThat(status.error()).hasValueSatisfying(e -> assertThat(e).contains("/nonexistent/libvips.so.42"));
        assertThat(status.version()).isEmpty();
        assertThat(status.library()).isEmpty();
        // the message is fixed: the library path and the directories tried are only in the WARN and the status
        assertThatThrownBy(libvips::version).isInstanceOf(LibvipsUnavailableException.class)
                .hasMessage("libvips is unavailable");
        assertThatThrownBy(libvips::library).isInstanceOf(LibvipsUnavailableException.class);
    }
}
