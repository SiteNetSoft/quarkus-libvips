package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.test.QuarkusExtensionTest;

/** A library that cannot be opened fails the start-up, naming what was tried. Needs no libvips. */
class MissingLibraryTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.library", "/nonexistent/libvips.so.42")
            .assertException(t -> {
                assertThat(Causes.find(t, ConfigurationException.class)).isNotNull();
                assertThat(t).hasStackTraceContaining("/nonexistent/libvips.so.42");
            });

    @Test
    void missingLibraryFailsStartup() {
        fail("the application must not start");
    }
}
