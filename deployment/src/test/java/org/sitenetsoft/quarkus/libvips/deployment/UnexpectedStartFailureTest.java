package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A start-up failure of another kind than "unavailable" or "bad block list" (here an {@link IllegalStateException}
 * from a malformed process-wide record) is still reported as a {@link ConfigurationException}.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class UnexpectedStartFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .setBeforeAllCustomizer(() -> MalformedDefaultConcurrency.set())
            .setAfterAllCustomizer(() -> MalformedDefaultConcurrency.restore())
            .assertException(t -> {
                ConfigurationException ce = Causes.find(t, ConfigurationException.class);
                assertThat(ce).isNotNull();
                assertThat(ce).hasMessageStartingWith("libvips could not be started: ");
                assertThat(Causes.find(t, IllegalStateException.class)).isNotNull();
                assertThat(t).hasStackTraceContaining(MalformedDefaultConcurrency.PROPERTY + " is not a number");
            });

    @Test
    void unexpectedFailureFailsStartup() {
        fail("the application must not start");
    }
}
