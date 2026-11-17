package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/** Without configuration the libvips operation cache is off, and the start-up line says so. */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class CacheOffByDefaultTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .setLogRecordPredicate(r -> r.getMessage() != null && r.getMessage().startsWith("libvips %s"))
            .assertLogRecords(records -> assertThat(records).singleElement()
                    .satisfies(r -> assertThat(r.getParameters()[3]).isEqualTo(0)));

    @Inject
    Libvips libvips;

    @Test
    void cacheIsOff() {
        assertThat(libvips.status().cacheMaxOperations()).isZero();
    }
}
