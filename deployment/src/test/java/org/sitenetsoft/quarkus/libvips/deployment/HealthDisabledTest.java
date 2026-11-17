package org.sitenetsoft.quarkus.libvips.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/** {@code quarkus.libvips.health.enabled=false}: no libvips readiness check. */
class HealthDisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideConfigKey("quarkus.libvips.health.enabled", "false")
            .overrideRuntimeConfigKey("quarkus.libvips.library", "/nonexistent/libvips.so.42")
            .overrideRuntimeConfigKey("quarkus.libvips.required", "false");

    @Test
    void noCheck() {
        given().get("/q/health/ready").then()
                .statusCode(200)
                .body("checks.find { it.name == 'libvips' }", nullValue());
    }
}
