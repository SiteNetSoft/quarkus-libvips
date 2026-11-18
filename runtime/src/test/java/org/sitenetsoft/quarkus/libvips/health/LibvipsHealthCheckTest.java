package org.sitenetsoft.quarkus.libvips.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The readiness check reports a library's file name, never its directory. */
class LibvipsHealthCheckTest {

    @Test
    void fileNameDropsEveryDirectory() {
        assertThat(LibvipsHealthCheck.fileName("/lib/x86_64-linux-gnu/libvips.so.42")).isEqualTo("libvips.so.42");
        assertThat(LibvipsHealthCheck.fileName("./vendor/libvips.so.42")).isEqualTo("libvips.so.42");
        assertThat(LibvipsHealthCheck.fileName("libvips.so.42")).isEqualTo("libvips.so.42");
    }
}
