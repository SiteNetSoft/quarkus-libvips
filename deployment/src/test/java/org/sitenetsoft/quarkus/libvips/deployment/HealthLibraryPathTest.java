package org.sitenetsoft.quarkus.libvips.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * A library configured by absolute path: the readiness check names only its file name, never a host directory, while
 * {@link Libvips.Status#library()} keeps the configured value.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.HealthLibraryPathTest#hasAbsolutePath")
class HealthLibraryPathTest {

    private static final String CHECK = "checks.find { it.name == 'libvips' }";

    private static final Optional<String> PATH = absolutePath();

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideRuntimeConfigKey("quarkus.libvips.library", PATH.orElse("libvips.so.42"));

    @Inject
    Libvips libvips;

    static boolean hasAbsolutePath() {
        return LibvipsAvailable.isAvailable() && PATH.isPresent();
    }

    /**
     * The configured library if it is a path, else the soname's file as {@code ldconfig} resolves it. Same lookup as
     * {@link LibvipsAvailable#library()}, repeated here because this class is also initialised in the application's
     * class loader, which does not see that class.
     */
    private static Optional<String> absolutePath() {
        String configured = System.getProperty("quarkus.libvips.library");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("QUARKUS_LIBVIPS_LIBRARY");
        }
        if (configured == null || configured.isBlank()) {
            configured = "libvips.so.42";
        }
        String soname = configured;
        if (configured.startsWith("/")) {
            return Optional.of(configured);
        }
        try {
            Process p = new ProcessBuilder("ldconfig", "-p").redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor();
            return out.lines().map(String::strip).filter(l -> l.startsWith(soname + " "))
                    .map(l -> l.substring(l.lastIndexOf("=> ") + 3).strip())
                    .filter(l -> l.startsWith("/") && Files.isRegularFile(Path.of(l))).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Test
    void readinessNamesOnlyTheLibraryFileName() {
        String path = PATH.orElseThrow();
        assertThat(libvips.status().library()).contains(path);
        Map<String, Object> data = given().get("/q/health/ready").then().statusCode(200)
                .body(CHECK + ".status", is("UP"))
                .body(CHECK + ".data.library", is(Path.of(path).getFileName().toString()))
                .extract().path(CHECK + ".data");
        assertThat(data).isNotEmpty();
        assertThat(data.values()).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain("/"));
    }
}
