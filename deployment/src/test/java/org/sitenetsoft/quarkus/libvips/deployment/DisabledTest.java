package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;

/** {@code quarkus.libvips.enabled=false}: no bean, nothing loaded. */
class DisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideConfigKey("quarkus.libvips.enabled", "false");

    @Test
    void noBean() {
        assertThat(Arc.container().instance(Libvips.class).isAvailable()).isFalse();
    }
}
