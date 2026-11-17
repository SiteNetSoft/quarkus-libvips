package org.sitenetsoft.quarkus.libvips.it;

import org.junit.jupiter.api.condition.EnabledIf;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** {@link ImageResourceTest} against the packaged application: the native executable in the native build. */
@QuarkusIntegrationTest
@EnabledIf("org.sitenetsoft.quarkus.libvips.it.LibvipsAvailable#isAvailable")
class ImageResourceIT extends ImageResourceTest {
}
