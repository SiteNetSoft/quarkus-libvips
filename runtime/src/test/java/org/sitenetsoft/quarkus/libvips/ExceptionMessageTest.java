package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;
import org.sitenetsoft.quarkus.libvips.internal.VipsRuntime;

/**
 * Exception messages are fixed text: libvips's error text (which may be another request's, and names host paths) stays
 * in {@link LibvipsException#detail()}. Successful calls leave libvips's error buffer alone.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class ExceptionMessageTest {

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        TestLibvips.start(Settings.defaults());
        ErrorBuffer.take();
    }

    @Test
    void missingFile() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        Path missing = dir.resolve("tenant-42").resolve("secret.jpg");
        assertThatThrownBy(() -> libvips.load(missing))
                .isInstanceOfSatisfying(UnsupportedFormatException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("vips_foreign_find_load: no loader accepts this input "
                            + UnsupportedFormatException.BLOCKED_HINT);
                    assertFixed(e);
                    // the real name, not the sniffing name with "[]" appended
                    assertThat(e.detail()).contains(missing.toString()).doesNotContain("secret.jpg[]");
                });
        assertThatThrownBy(() -> libvips.thumbnail(missing, 10, ThumbnailOptions.DEFAULT))
                .isInstanceOfSatisfying(UnsupportedFormatException.class, e -> {
                    assertFixed(e);
                    assertThat(e.detail()).contains(missing.toString()).doesNotContain("secret.jpg[]");
                });
    }

    @Test
    void uploadInNoKnownFormat() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        byte[] text = "this is not an image, it only claims to be one".getBytes(StandardCharsets.US_ASCII);
        for (Runnable call : new Runnable[] { () -> libvips.load(text).close(),
                () -> libvips.thumbnail(text, 10, ThumbnailOptions.DEFAULT).close() }) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(UnsupportedFormatException.class, e -> {
                assertThat(e.getMessage()).isEqualTo("vips_foreign_find_load_buffer: no loader accepts this input "
                        + UnsupportedFormatException.BLOCKED_HINT);
                assertFixed(e);
            });
        }
    }

    @Test
    void corruptUpload() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        // a JPEG signature, then garbage: sniffed as JPEG, refused by the loader while it reads the header
        byte[] corrupt = new byte[256];
        corrupt[0] = (byte) 0xFF;
        corrupt[1] = (byte) 0xD8;
        corrupt[2] = (byte) 0xFF;
        corrupt[3] = (byte) 0xE0;
        for (int i = 4; i < corrupt.length; i++) {
            corrupt[i] = (byte) (i * 7);
        }
        assertThatThrownBy(() -> libvips.load(corrupt).close())
                .isInstanceOfSatisfying(LibvipsException.class, e -> {
                    assertThat(e).isNotInstanceOf(UnsupportedFormatException.class);
                    assertThat(e.operation()).isEqualTo("VipsForeignLoadJpegBuffer"); // the loader's class name
                    assertThat(e.getMessage()).isEqualTo("VipsForeignLoadJpegBuffer failed");
                    assertFixed(e);
                });
    }

    @Test
    void failedOperation() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThatThrownBy(() -> i.crop(390, 290, 50, 50))
                    .isInstanceOfSatisfying(LibvipsException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("extract_area failed");
                        assertFixed(e);
                        assertThat(e.detail()).contains("bad extract area");
                    });
        }
    }

    @Test
    void unavailableLibrary() {
        String reason = "libvips could not be loaded; tried: /opt/private/lib/libvips.so.42 (not found)";
        Libvips libvips = new Libvips();
        libvips.attach(VipsRuntime.unavailable(reason));
        Consumer<LibvipsUnavailableException> fixed = e -> {
            assertThat(e.getMessage()).isEqualTo("libvips is unavailable").doesNotContain("/");
            assertThat(e.operation()).isEqualTo("libvips");
            assertThat(e.detail()).isEqualTo(reason);
        };
        assertThatThrownBy(() -> libvips.load(TestLibvips.portrait()))
                .isInstanceOfSatisfying(LibvipsUnavailableException.class, fixed);
        assertThatThrownBy(libvips::trackedMemory).isInstanceOfSatisfying(LibvipsUnavailableException.class, fixed);
        // the reason stays available to diagnostics
        assertThat(libvips.status().error()).contains(reason);
    }

    @Test
    void successfulCallsLeaveTheErrorBufferAlone() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        byte[] webp;
        try (Image i = libvips.load(TestLibvips.portrait())) {
            webp = i.save(SaveOptions.webp(80));
        }
        var paged = new LoadOptions(Access.RANDOM, Optional.empty(), 0, -1);
        var withProfile = new ThumbnailOptions(OptionalInt.empty(), Size.DOWN, Interesting.NONE, false,
                Optional.of("srgb"), Intent.RELATIVE);
        for (Consumer<Image> call : java.util.List.<Consumer<Image>> of(
                i -> i.crop(0, 0, 10, 10).close(),
                i -> i.smartCrop(10, 10, Interesting.CENTRE).close(),
                i -> i.thumbnail(10, withProfile).close(),
                i -> libvips.load(webp, paged).close(),
                i -> libvips.thumbnail(webp, 10, withProfile).close())) {
            ErrorBuffer.take();
            leaveAWarning(libvips);
            try (Image i = libvips.load(TestLibvips.portrait())) {
                call.accept(i);
            }
            assertThat(ErrorBuffer.take()).containsIgnoringCase("premature end");
        }
    }

    /** A lenient decode of a truncated JPEG succeeds and leaves libjpeg's warning in the global buffer. */
    private static void leaveAWarning(Libvips libvips) {
        try (Image lenient = libvips.load(TestLibvips.truncatedPortrait())) {
            lenient.save(SaveOptions.jpeg(80));
        }
    }

    private static void assertFixed(LibvipsException e) {
        assertThat(e.getMessage()).doesNotContain("/");
        assertThat(e.detail()).isNotBlank();
        for (String line : e.detail().strip().split("\n")) {
            assertThat(e.getMessage()).doesNotContain(line.strip());
        }
    }
}
