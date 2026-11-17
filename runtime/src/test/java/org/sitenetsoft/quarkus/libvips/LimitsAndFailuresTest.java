package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class LimitsAndFailuresTest {

    /** Limits are kept per runtime, but the next test class must start from the defaults. */
    @AfterEach
    void restore() {
        TestLibvips.start(Settings.defaults());
    }

    @Test
    void sizeLimitBeforeAnyNativeCopy() {
        Libvips libvips = TestLibvips.start(Settings.defaults().withLimits(268402689, 1000));
        assertThatThrownBy(() -> libvips.load(TestLibvips.portrait()))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.limit()).isEqualTo(1000);
                    assertThat(e.actual()).isEqualTo(28029);
                    assertThat(e.operation()).isEqualTo("limits.max-input-size");
                    assertThat(e.getMessage()).contains("28029", "1000", "quarkus.libvips.limits.max-input-size");
                });
        assertThatThrownBy(() -> libvips.load(TestLibvips.portraitPath())).isInstanceOf(LimitExceededException.class);
        assertThatThrownBy(() -> libvips.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT))
                .isInstanceOf(LimitExceededException.class);
        assertThatThrownBy(() -> libvips.thumbnail(TestLibvips.portraitPath(), 50, ThumbnailOptions.DEFAULT))
                .isInstanceOf(LimitExceededException.class);
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void pixelLimitRefusesBeforeDecode() {
        Libvips libvips = TestLibvips.start(Settings.defaults().withLimits(1000, 134217728));
        assertThatThrownBy(() -> libvips.load(TestLibvips.portrait()))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.actual()).isEqualTo(120000);
                    assertThat(e.limit()).isEqualTo(1000);
                    assertThat(e.operation()).isEqualTo("limits.max-input-pixels");
                    assertThat(e.getMessage()).contains("quarkus.libvips.limits.max-input-pixels");
                });
        assertThatThrownBy(() -> libvips.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT))
                .isInstanceOf(LimitExceededException.class);
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void thumbnailChecksThePixelsBeforeShrinkOnLoad() {
        // thumbnail_buffer shrinks a JPEG while decoding and the 50 px result is far under the limit: the limit must
        // apply to the size the header declares
        Libvips tight = TestLibvips.start(Settings.defaults().withLimits(119999, 0));
        assertThatThrownBy(() -> tight.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT))
                .isInstanceOfSatisfying(LimitExceededException.class,
                        e -> assertThat(e.actual()).isEqualTo(120000));
        assertThatThrownBy(() -> tight.thumbnail(TestLibvips.portraitPath(), 50, ThumbnailOptions.DEFAULT))
                .isInstanceOf(LimitExceededException.class);
        assertThat(tight.openImages()).isZero();

        Libvips exact = TestLibvips.start(Settings.defaults().withLimits(120000, 0));
        try (Image t = exact.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT)) {
            assertThat(t.width()).isEqualTo(50);
        }
        assertThat(exact.openImages()).isZero();
    }

    @Test
    void decompressionBombRefusedAtTheHeader() {
        // a few dozen bytes declaring 100000 × 100000 pixels: refused with the default limit before any decode
        Libvips libvips = TestLibvips.start(Settings.defaults());
        byte[] bomb = pngHeader(100_000, 100_000);
        assertThat(bomb.length).isLessThan(100);
        for (var attempt : List.<ThrowingCallable> of(() -> libvips.load(bomb).close(),
                () -> libvips.thumbnail(bomb, 50, ThumbnailOptions.DEFAULT).close())) {
            assertThatThrownBy(attempt).isInstanceOfSatisfying(LimitExceededException.class, e -> {
                assertThat(e.actual()).isEqualTo(10_000_000_000L);
                assertThat(e.limit()).isEqualTo(268402689);
            });
        }
        assertThat(libvips.openImages()).isZero();
        ErrorBuffer.take();
    }

    /** A PNG signature, an IHDR for an 8-bit RGB image of the given size, an empty IDAT and IEND. */
    static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' });
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[0]);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void zeroMeansNoLimit() {
        Libvips libvips = TestLibvips.start(Settings.defaults().withLimits(0, 0));
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.width()).isEqualTo(400);
        }
    }

    @Test
    void truncatedFailsAtSaveNotAtLoad() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        byte[] truncated = TestLibvips.truncatedPortrait(); // cut inside the scan data
        var strict = new LoadOptions(Access.RANDOM, Optional.of(FailOn.TRUNCATED), 0, 1);
        ErrorBuffer.take();
        try (Image i = libvips.load(truncated, strict)) {
            assertThat(i.width()).isEqualTo(400);
            assertThatThrownBy(() -> i.save(SaveOptions.jpeg(80)))
                    .isInstanceOfSatisfying(LibvipsException.class, e -> {
                        assertThat(e.operation()).isNotBlank().doesNotContain("load"); // the lazy path, not the loader
                        assertThat(e.detail()).isNotBlank();
                    });
        }
        try (Image lenient = libvips.load(truncated)) {
            assertThat(lenient.save(SaveOptions.jpeg(80))).isNotEmpty();
        }
        assertThat(libvips.openImages()).isZero();
        // a successful lenient decode still leaves libjpeg's warning in the global buffer; record it
        assertThat(ErrorBuffer.take()).containsIgnoringCase("premature end");
    }
}
