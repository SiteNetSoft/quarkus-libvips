package org.sitenetsoft.quarkus.libvips.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.DisabledOnIntegrationTest;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The packaged JVM application runs with native access enabled although it is started without
 * {@code --enable-native-access} and with {@code --illegal-native-access=deny} (the failsafe arguments): opening
 * libvips would throw otherwise, so every integration test depends on it. {@code quarkus-run.jar}'s manifest carries
 * {@code Enable-Native-Access: ALL-UNNAMED}, which the extension contributes. Not for the native executable, which has
 * no jar and no restricted-method checks.
 */
@QuarkusIntegrationTest
@DisabledOnIntegrationTest(forArtifactTypes = DisabledOnIntegrationTest.ArtifactType.NATIVE_BINARY)
class NativeAccessIT {

    @Test
    void runnerJarManifestEnablesNativeAccess() throws IOException {
        try (JarFile jar = new JarFile(Path.of("target/quarkus-app/quarkus-run.jar").toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Enable-Native-Access")).isEqualTo("ALL-UNNAMED");
        }
    }

    @Test
    void applicationRunsWithNativeAccessEnabled() {
        assertThat(System.getProperty("quarkus.test.arg-line", "")).doesNotContain("enable-native-access")
                .contains("--illegal-native-access=deny");
        given().when().get("/images/native-access").then().statusCode(200).body(is("true"));
    }
}
