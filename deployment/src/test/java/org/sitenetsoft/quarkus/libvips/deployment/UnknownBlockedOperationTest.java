package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.test.QuarkusExtensionTest;

/** A block-list entry that names no libvips operation fails the start-up. */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class UnknownBlockedOperationTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.security.blocked-operations", "nosuchload")
            .assertException(t -> {
                assertThat(Causes.find(t, ConfigurationException.class)).isNotNull();
                assertThat(t).hasStackTraceContaining(
                        "quarkus.libvips.security.blocked-operations: unknown operation nosuchload");
            });

    @Test
    void unknownOperationFailsStartup() {
        fail("the application must not start");
    }
}
