package org.sitenetsoft.quarkus.libvips.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code required=false} and no library the application is meant to serve without libvips: the check stays UP
 * and says that libvips is unavailable. The reason in detail (directories tried, loader errors) stays in the startup
 * log: the health endpoint is often public.
 */
class HealthNotRequiredTest {

    private static final String CHECK = "checks.find { it.name == 'libvips' }";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.library", "/nonexistent/libvips.so.42")
            .overrideRuntimeConfigKey("quarkus.libvips.required", "false");

    @Test
    void upAndUnavailableWithTheError() {
        given().get("/q/health/ready").then()
                .statusCode(200)
                .body(CHECK + ".status", is("UP"))
                .body(CHECK + ".data.available", is(false))
                .body(CHECK + ".data.error", is("libvips is unavailable; see the startup log"))
                .body(CHECK + ".data.error", allOf(not(containsString("/")), not(containsString("nonexistent"))))
                .body(CHECK + ".data.version", nullValue());
    }
}
