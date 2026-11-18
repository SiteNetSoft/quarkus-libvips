package org.sitenetsoft.quarkus.libvips.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/** The readiness check reports the libvips version and settings. */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class HealthTest {

    private static final String CHECK = "checks.find { it.name == 'libvips' }";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.concurrency", "2")
            .overrideRuntimeConfigKey("quarkus.libvips.cache.max-operations", "7")
            .overrideRuntimeConfigKey("quarkus.libvips.cache.max-memory", "3M")
            .overrideRuntimeConfigKey("quarkus.libvips.cache.max-files", "2");

    @Inject
    Libvips libvips;

    @Test
    void readinessReportsVersionAndSettings() {
        Libvips.Status status = libvips.status();
        given().get("/q/health/ready").then()
                .statusCode(200)
                .body(CHECK + ".status", is("UP"))
                .body(CHECK + ".data.version", startsWith("8."))
                .body(CHECK + ".data.version", is(status.version().orElseThrow().toString()))
                .body(CHECK + ".data.library", is(fileName(status.library().orElseThrow())))
                .body(CHECK + ".data.concurrency", is(2))
                .body(CHECK + ".data.'cache.max-operations'", is(7))
                .body(CHECK + ".data.'cache.max-memory'", is(3145728))
                .body(CHECK + ".data.'cache.max-files'", is(2))
                .body(CHECK + ".data.'block-untrusted'", is(true))
                .body(CHECK + ".data.'heif-load-blocked'", is(status.heifLoadBlocked()))
                .body(CHECK + ".data.'open-images'", is(0))
                .body(CHECK + ".data.'tracked-memory'", greaterThanOrEqualTo(0));
    }

    private static String fileName(String library) {
        return library.substring(library.lastIndexOf('/') + 1);
    }
}
