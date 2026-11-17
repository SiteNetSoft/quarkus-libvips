package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;

import jakarta.inject.Inject;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/** With {@code required=false} an unknown block-list entry logs a WARN and the application runs without libvips. */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class UnknownBlockedOperationNotRequiredTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.security.blocked-operations", "nosuchload")
            .overrideRuntimeConfigKey("quarkus.libvips.required", "false")
            .setLogRecordPredicate(r -> r.getLoggerName() != null
                    && r.getLoggerName().startsWith("org.sitenetsoft.quarkus.libvips")
                    && r.getLevel().intValue() >= Level.WARNING.intValue())
            .assertLogRecords(records -> assertThat(records).singleElement()
                    .satisfies(r -> assertThat(((ExtLogRecord) r).getFormattedMessage())
                            .contains("quarkus.libvips.security.blocked-operations: unknown operation nosuchload")));

    @Inject
    Libvips libvips;

    @Test
    void startsWithoutLibvips() {
        assertThat(libvips.isAvailable()).isFalse();
        assertThat(libvips.status().error()).hasValueSatisfying(
                e -> assertThat(e)
                        .contains("quarkus.libvips.security.blocked-operations: unknown operation nosuchload"));
    }
}
