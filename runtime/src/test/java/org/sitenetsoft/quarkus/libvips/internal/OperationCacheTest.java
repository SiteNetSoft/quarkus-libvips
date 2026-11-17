package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.TestLibvips;
import org.sitenetsoft.quarkus.libvips.ThumbnailOptions;

/**
 * Loaders keep the caller's data (a native copy of the whole upload, or the decoded pixels of a file) alive for as
 * long as the operation lives, so they never stay in libvips's operation cache. Cached transforms keep it alive too
 * (through their input images, also inside {@code thumbnail_buffer}), so the cache is off by default; an application
 * that turns it on still gets hits for repeated transforms.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class OperationCacheTest {

    Libvips libvips;

    /** Loaders are kept out of the cache even when it is on. */
    @BeforeEach
    void start() {
        libvips = TestLibvips.start(Settings.defaults().withCacheMaxOperations(100));
        emptyCache(); // so that a growing cache is seen (a full one would evict instead)
        assertThat(LibvipsNative.cacheGetSize()).isZero();
    }

    @AfterEach
    void restore() {
        VipsRuntime.start(Settings.defaults());
    }

    /** {@return the fixture with {@code n + 1} bytes after its end marker: another upload, decoding the same} */
    private static byte[] upload(int n) {
        byte[] portrait = TestLibvips.portrait();
        return Arrays.copyOf(portrait, portrait.length + n + 1);
    }

    @Test
    void loadsFromCallerBytesDoNotStayInTheCache() {
        for (int n = 0; n < 5; n++) {
            try (Image i = libvips.load(upload(n))) {
                assertThat(i.width()).isEqualTo(400);
            }
        }
        assertThat(LibvipsNative.cacheGetSize()).isZero();
    }

    @Test
    void byDefaultNothingStaysInTheCache() {
        assertThat(Settings.defaults().cacheMaxOperations()).isZero();
        Libvips defaults = TestLibvips.start(Settings.defaults());
        assertThat(LibvipsNative.cacheGetMax()).isZero(); // the real guarantee: libvips adds nothing at size 0
        for (int n = 0; n < 5; n++) {
            try (Image t = defaults.thumbnail(upload(n), 50, ThumbnailOptions.DEFAULT)) {
                assertThat(t.width()).isEqualTo(50);
            }
            try (Image i = defaults.load(upload(n + 10)); Image r = i.call("resize", Map.of("scale", 0.5))) {
                assertThat(r.width()).isEqualTo(200);
            }
        }
        assertThat(LibvipsNative.cacheGetSize()).isZero();
    }

    private static void emptyCache() {
        int max = LibvipsNative.cacheGetMax();
        LibvipsNative.cacheSetMax(0);
        LibvipsNative.cacheSetMax(max);
    }

    @Test
    void loadsFromFilesDoNotStayInTheCache() {
        for (int n = 0; n < 3; n++) {
            try (Image i = libvips.load(TestLibvips.portraitPath())) {
                assertThat(i.width()).isEqualTo(400);
            }
        }
        assertThat(LibvipsNative.cacheGetSize()).isZero();
    }

    @Test
    void transformsStillUseTheCache() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(LibvipsNative.cacheGetSize()).isZero();
            try (Image r = i.call("resize", Map.of("scale", 0.5))) {
                assertThat(r.width()).isEqualTo(200);
            }
            int afterFirst = LibvipsNative.cacheGetSize(); // resize and the operations it runs inside
            assertThat(afterFirst).isPositive();
            try (Image r = i.call("resize", Map.of("scale", 0.5))) { // a hit: nothing added
                assertThat(r.width()).isEqualTo(200);
            }
            assertThat(LibvipsNative.cacheGetSize()).isEqualTo(afterFirst);
            try (Image r = i.call("resize", Map.of("scale", 0.25))) {
                assertThat(r.width()).isEqualTo(100);
            }
            assertThat(LibvipsNative.cacheGetSize()).isGreaterThan(afterFirst);
        }
    }
}
