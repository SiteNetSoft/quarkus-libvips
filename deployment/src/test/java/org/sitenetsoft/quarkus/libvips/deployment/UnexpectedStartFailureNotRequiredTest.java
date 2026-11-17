package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/** With {@code required=false} a start-up failure of any kind leaves the application running without libvips. */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class UnexpectedStartFailureNotRequiredTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .setBeforeAllCustomizer(() -> MalformedDefaultConcurrency.set())
            .setAfterAllCustomizer(() -> MalformedDefaultConcurrency.restore())
            .overrideRuntimeConfigKey("quarkus.libvips.required", "false");

    @Inject
    Libvips libvips;

    @Test
    void startsWithoutLibvips() {
        assertThat(libvips.isAvailable()).isFalse();
        assertThat(libvips.status().error())
                .hasValueSatisfying(e -> assertThat(e).contains(MalformedDefaultConcurrency.PROPERTY));
    }
}
