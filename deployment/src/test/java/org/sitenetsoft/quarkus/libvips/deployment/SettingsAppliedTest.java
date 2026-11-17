package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/** The run-time configuration reaches libvips (read back through the public status). */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class SettingsAppliedTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.concurrency", "2")
            .overrideRuntimeConfigKey("quarkus.libvips.cache.max-operations", "7")
            .overrideRuntimeConfigKey("quarkus.libvips.cache.max-memory", "3M")
            .overrideRuntimeConfigKey("quarkus.libvips.cache.max-files", "2");

    @Inject
    Libvips libvips;

    @Test
    void settingsAreApplied() {
        Libvips.Status status = libvips.status();
        assertThat(status.concurrency()).isEqualTo(2);
        assertThat(status.cacheMaxOperations()).isEqualTo(7);
        assertThat(status.cacheMaxMemory()).isEqualTo(3145728);
        assertThat(status.cacheMaxFiles()).isEqualTo(2);
    }
}
