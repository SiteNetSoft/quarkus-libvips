package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.ProdBuildResults;
import io.quarkus.test.ProdModeTestResults;
import io.quarkus.test.QuarkusProdModeTest;

/**
 * The packaged application (fast-jar, the default) carries {@code Enable-Native-Access: ALL-UNNAMED} in
 * {@code quarkus-run.jar}'s manifest, so {@code java -jar} needs no flag. The integration tests run that jar without
 * {@code --enable-native-access} and with {@code --illegal-native-access=deny}, so the application could not open
 * libvips without the manifest entry, and check {@code Module.isNativeAccessEnabled()} from inside it.
 */
class NativeAccessFastJarTest {

    @RegisterExtension
    static final QuarkusProdModeTest TEST = new QuarkusProdModeTest()
            .withEmptyApplication()
            .setApplicationName("native-access-fast-jar")
            .setApplicationVersion("0.1")
            .setRun(false);

    @ProdBuildResults
    ProdModeTestResults results;

    @Test
    void runnerJarEnablesNativeAccess() throws IOException {
        assertManifest(results.getBuiltArtifactPath(), "quarkus-run.jar");
    }

    static void assertManifest(Path jar, String name) throws IOException {
        assertThat(jar.getFileName()).hasToString(name);
        try (JarFile j = new JarFile(jar.toFile())) {
            assertThat(j.getManifest().getMainAttributes().getValue("Enable-Native-Access")).isEqualTo("ALL-UNNAMED");
        }
    }
}
