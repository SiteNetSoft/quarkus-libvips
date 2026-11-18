package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;
import org.sitenetsoft.quarkus.libvips.Version;

class LibraryLoaderTest {

    @Test
    void missingPathListsWhatWasTried() {
        assertThatThrownBy(() -> LibraryLoader.load(Optional.of("/nonexistent/libvips.so.42")))
                .isInstanceOf(LibvipsUnavailableException.class).hasMessage("libvips is unavailable")
                .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING)
                .contains("/nonexistent/libvips.so.42")
                .contains("libvips42t64");
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
                    .isInstanceOf(LibvipsUnavailableException.class).hasMessage("libvips is unavailable")
                    .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING)
                    .contains("tried: libvips-none.so.42 (")
                    .contains(dir.resolve("libvips-none.so.42") + " (not found)")
                    .contains("/usr/lib/x86_64-linux-gnu/libvips-none.so.42 (not found)")
                    .contains("/usr/lib/aarch64-linux-gnu/libvips-none.so.42 (not found)")
                    .contains("/usr/lib64/libvips-none.so.42 (not found)")
                    .contains("/usr/local/lib/libvips-none.so.42 (not found)")
                    .endsWith(
                            "install libvips (Debian/Ubuntu: libvips42t64) or set quarkus.libvips.library");
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

    /** Every symbol present: a stand-in address, never called. */
    private static Optional<MemorySegment> anySymbol(String name) {
        return Optional.of(MemorySegment.ofAddress(0x1000));
    }

    @Test
    void aLibraryOlderThanTheMinimumIsNamedAsSuch() {
        for (Version old : new Version[] { new Version(8, 14, 5), new Version(8, 10, 0), new Version(7, 42, 3) }) {
            assertThatThrownBy(() -> LibraryLoader.checkCompatible("/opt/old/libvips.so.42", old,
                    LibraryLoaderTest::anySymbol))
                    .isInstanceOf(LibvipsUnavailableException.class).hasMessage("libvips is unavailable")
                    .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING)
                    .isEqualTo("libvips " + old.major() + "." + old.minor() + " is older than the minimum 8.15 ("
                            + old + ", opened as /opt/old/libvips.so.42)");
        }
    }

    @Test
    void theVersionIsCheckedBeforeAnySymbol() {
        // an old libvips lacks newer functions too: the version is what the operator needs to read
        assertThatThrownBy(() -> LibraryLoader.checkCompatible("libvips.so.42", new Version(8, 10, 5),
                name -> Optional.empty()))
                .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING)
                .startsWith("libvips 8.10 is older than the minimum 8.15");
    }

    @Test
    void aMissingSymbolIsNamed() {
        assertThatThrownBy(() -> LibraryLoader.checkCompatible("libvips.so.42", new Version(8, 15, 0),
                name -> name.equals("vips_leak_set") || name.equals("g_try_malloc") ? Optional.empty()
                        : anySymbol(name)))
                .isInstanceOf(LibvipsUnavailableException.class).hasMessage("libvips is unavailable")
                .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING)
                .isEqualTo("libvips 8.15.0 (opened as libvips.so.42) lacks g_try_malloc, vips_leak_set, which the"
                        + " extension needs");
    }

    @Test
    void theMinimumAndNewerWithEverySymbolPass() {
        LibraryLoader.checkCompatible("libvips.so.42", new Version(8, 15, 0), LibraryLoaderTest::anySymbol);
        LibraryLoader.checkCompatible("libvips.so.42", new Version(8, 18, 7), LibraryLoaderTest::anySymbol);
        LibraryLoader.checkCompatible("libvips.so.42", new Version(9, 0, 0), LibraryLoaderTest::anySymbol);
    }

    @Test
    @EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
    void theVersionIsReadBeforeBinding() {
        Version v = LibvipsNative.versionOf(java.lang.foreign.SymbolLookup.libraryLookup(
                org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable.library(), java.lang.foreign.Arena.global())
                .find("vips_version").orElseThrow());
        assertThat(v.major()).isEqualTo(8);
        assertThat(v.atLeast(8, 15)).isTrue();
    }
}
