package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/** An application with the extension starts libvips and exposes it as a bean. */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class StartupTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication();

    @Inject
    Libvips libvips;

    @Test
    void libvipsIsAvailable() {
        assertThat(libvips.isAvailable()).isTrue();
        assertThat(libvips.version().major()).isEqualTo(8);
        assertThat(libvips.version().atLeast(8, 15)).isTrue();
        assertThat(libvips.library()).isNotBlank();
        assertThat(libvips.status().available()).isTrue();
        assertThat(libvips.status().error()).isEmpty();
        assertThat(libvips.status().blockUntrusted()).isTrue();
    }
}
