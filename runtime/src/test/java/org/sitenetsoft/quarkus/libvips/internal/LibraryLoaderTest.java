package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;

class LibraryLoaderTest {

    @Test
    void missingPathListsWhatWasTried() {
        assertThatThrownBy(() -> LibraryLoader.load(Optional.of("/nonexistent/libvips.so.42")))
                .isInstanceOf(LibvipsUnavailableException.class)
                .hasMessageContaining("/nonexistent/libvips.so.42")
                .hasMessageContaining("libvips42t64");
    }

    @Test
    @EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
    void sonameLoads() {
        LibraryLoader.Loaded l = LibraryLoader.load(Optional.empty());
        assertThat(l.library()).satisfiesAnyOf(
                s -> assertThat(s).isEqualTo("libvips.so.42"),
                s -> assertThat(s).endsWith("/libvips.so.42"));
        assertThat(System.getProperty(LibraryLoader.LOADED_PROPERTY))
                .startsWith(Long.toHexString(l.lookup().find("vips_init").orElseThrow().address()) + " ");
    }

    @Test
    @EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
    void configuredNameIsPassedUnchanged() {
        assertThat(LibraryLoader.load(Optional.of("libvips.so.42")).library()).isEqualTo("libvips.so.42");
    }

    @Test
    void searchListsEveryDirectoryWhenTheSonameFails(@TempDir Path dir) {
        String previous = System.getProperty("java.library.path");
        System.setProperty("java.library.path", dir.toString());
        try {
            assertThatThrownBy(() -> LibraryLoader.load(Optional.empty(), "libvips-none.so.42"))
                    .isInstanceOf(LibvipsUnavailableException.class)
                    .hasMessageContaining("tried: libvips-none.so.42 (")
                    .hasMessageContaining(dir.resolve("libvips-none.so.42") + " (not found)")
                    .hasMessageContaining("/usr/lib/x86_64-linux-gnu/libvips-none.so.42 (not found)")
                    .hasMessageContaining("/usr/lib/aarch64-linux-gnu/libvips-none.so.42 (not found)")
                    .hasMessageContaining("/usr/lib64/libvips-none.so.42 (not found)")
                    .hasMessageContaining("/usr/local/lib/libvips-none.so.42 (not found)")
                    .hasMessageEndingWith("install libvips (Debian/Ubuntu: libvips42t64) or set quarkus.libvips.library");
        } finally {
            System.setProperty("java.library.path", previous);
        }
    }

    @Test
    @EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
    void searchFindsTheLibraryInJavaLibraryPath(@TempDir Path dir) throws Exception {
        LibraryLoader.Loaded real = LibraryLoader.load(Optional.empty());
        Path link = dir.resolve("libvips-fallback.so.42");
        Files.createSymbolicLink(link, Path.of(VipsRuntimeTest.realPath()));
        String previous = System.getProperty("java.library.path");
        System.setProperty("java.library.path", dir.toString());
        try {
            LibraryLoader.Loaded l = LibraryLoader.load(Optional.empty(), "libvips-fallback.so.42");
            assertThat(l.library()).isEqualTo(link.toString());
            assertThat(l.lookup().find("vips_init").orElseThrow().address())
                    .isEqualTo(real.lookup().find("vips_init").orElseThrow().address());
        } finally {
            System.setProperty("java.library.path", previous);
        }
    }
}
