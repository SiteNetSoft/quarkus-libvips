package org.sitenetsoft.quarkus.libvips.deployment;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.ProdBuildResults;
import io.quarkus.test.ProdModeTestResults;
import io.quarkus.test.QuarkusProdModeTest;

/** The uber-jar carries {@code Enable-Native-Access: ALL-UNNAMED} too. */
class NativeAccessUberJarTest {

    @RegisterExtension
    static final QuarkusProdModeTest TEST = new QuarkusProdModeTest()
            .withEmptyApplication()
            .setApplicationName("native-access-uber-jar")
            .setApplicationVersion("0.1")
            .overrideConfigKey("quarkus.package.jar.type", "uber-jar")
            .setRun(false);

    @ProdBuildResults
    ProdModeTestResults results;

    @Test
    void uberJarEnablesNativeAccess() throws IOException {
        NativeAccessFastJarTest.assertManifest(results.getBuiltArtifactPath(), "native-access-uber-jar-runner.jar");
    }
}
