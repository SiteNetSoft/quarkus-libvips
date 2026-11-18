package org.sitenetsoft.quarkus.libvips;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class SaveTest {

    Libvips libvips;

    @BeforeEach
    void start() {
        libvips = TestLibvips.start(Settings.defaults());
    }

    @Test
    void jpegPngWebp() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            byte[] jpg = i.save(SaveOptions.jpeg(85));
            assertThat(Arrays.copyOf(jpg, 2)).containsExactly(0xff, 0xd8);
            byte[] png = i.save(SaveOptions.png());
            assertThat(Arrays.copyOf(png, 4)).containsExactly(0x89, 'P', 'N', 'G');
            byte[] webp = i.save(SaveOptions.webp(75));
            assertThat(new String(webp, 0, 4, US_ASCII) + new String(webp, 8, 4, US_ASCII)).isEqualTo("RIFFWEBP");
            try (Image back = libvips.load(webp)) {
                assertThat(back.width()).isEqualTo(400);
            }
        }
    }

    @Test
    void avif() {
        boolean expected = "true".equals(System.getenv("LIBVIPS_EXPECT_AVIF"));
        assumeTrue(expected || libvips.hasOperation("heifsave_buffer"), "no HEIF/AVIF saver in this libvips");
        try (Image i = libvips.load(TestLibvips.portrait())) {
            byte[] avif = i.save(SaveOptions.avif(50));
            assertThat(new String(avif, 4, 8, US_ASCII)).isEqualTo("ftypavif");
        }
    }

    @Test
    void keepIccOnlyByDefault() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            try (Image back = libvips.load(i.save(SaveOptions.jpeg(85)))) {
                assertThat(back.iccProfile()).isPresent();
                assertThat(back.field("exif-data")).isEmpty();
            }
            try (Image bare = libvips.load(i.save(new SaveOptions.Jpeg(85, false, Set.of())))) {
                assertThat(bare.iccProfile()).isEmpty();
            }
        }
    }

    @Test
    void rangesAreValidated() {
        assertThatThrownBy(() -> new SaveOptions.Jpeg(0, false, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quality");
        assertThatThrownBy(() -> new SaveOptions.Webp(80, false, 7, Set.of())).hasMessageContaining("effort");
        assertThatThrownBy(() -> new SaveOptions.Avif(80, false, 10, Set.of())).hasMessageContaining("effort");
        assertThatThrownBy(() -> new SaveOptions.Png(10, false, Set.of())).hasMessageContaining("compression");
    }

    @Test
    void jpegInterlaceWritesProgressive() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(jpegFrameMarker(i.save(new SaveOptions.Jpeg(85, true, Set.of())))).isEqualTo(0xc2); // SOF2
            assertThat(jpegFrameMarker(i.save(new SaveOptions.Jpeg(85, false, Set.of())))).isEqualTo(0xc0); // SOF0
        }
    }

    @Test
    void jpegQualityChangesSize() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.save(SaveOptions.jpeg(10)).length).isLessThan(i.save(SaveOptions.jpeg(95)).length);
        }
    }

    @Test
    void pngInterlaceSetsIhdr() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.save(new SaveOptions.Png(6, true, Set.of()))[28]).isEqualTo((byte) 1); // Adam7
            assertThat(i.save(SaveOptions.png())[28]).isEqualTo((byte) 0);
        }
    }

    @Test
    void webpLosslessWritesVp8l() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(webpChunks(i.save(new SaveOptions.Webp(75, true, 4, Set.of())))).contains("VP8L")
                    .doesNotContain("VP8 ");
            assertThat(webpChunks(i.save(SaveOptions.webp(75)))).contains("VP8 ").doesNotContain("VP8L");
        }
    }

    @Test
    void webpQualityRange() {
        assertThatThrownBy(() -> new SaveOptions.Webp(0, false, 4, Set.of())).hasMessageContaining("quality");
        assertThatThrownBy(() -> new SaveOptions.Webp(101, false, 4, Set.of())).hasMessageContaining("quality");
    }

    @Test
    void avifQualityRange() {
        assertThatThrownBy(() -> new SaveOptions.Avif(0, false, 4, Set.of())).hasMessageContaining("quality");
        assertThatThrownBy(() -> new SaveOptions.Avif(101, false, 4, Set.of())).hasMessageContaining("quality");
    }

    @Test
    void gifOptionsNameLibvipsArguments() {
        assertThat(SaveOptions.gif().operation()).isEqualTo("gifsave_buffer");
        assertThat(SaveOptions.gif().suffix()).isEqualTo(".gif");
        assertThat(SaveOptions.gif().arguments()).containsExactlyInAnyOrderEntriesOf(
                Map.of("effort", 7, "bitdepth", 8, "interlace", false, "keep", 0));
        assertThat(new SaveOptions.Gif(1, 1, true, Set.of(Keep.ICC)).arguments()).containsExactlyInAnyOrderEntriesOf(
                Map.of("effort", 1, "bitdepth", 1, "interlace", true, "keep", 8));
    }

    @Test
    void gifRangesAreValidated() {
        assertThatThrownBy(() -> new SaveOptions.Gif(0, 8, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("effort must be 1 to 10, got 0");
        assertThatThrownBy(() -> new SaveOptions.Gif(11, 8, false, Set.of()))
                .hasMessage("effort must be 1 to 10, got 11");
        assertThatThrownBy(() -> new SaveOptions.Gif(7, 0, false, Set.of()))
                .hasMessage("bitdepth must be 1 to 8, got 0");
        assertThatThrownBy(() -> new SaveOptions.Gif(7, 9, false, Set.of()))
                .hasMessage("bitdepth must be 1 to 8, got 9");
        assertThat(new SaveOptions.Gif(10, 8, false, Set.of()).effort()).isEqualTo(10);
        assertThatThrownBy(() -> new SaveOptions.Gif(7, 8, false, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aGifRoundTripKeepsItsSize() {
        assumeTrue(expectGif() || libvips.hasOperation("gifsave_buffer"),
                "SKIPPED (libvips lacks gifsave_buffer): built without cgif");
        try (Image i = libvips.load(TestLibvips.portrait()); Image small = i.crop(0, 0, 16, 9)) {
            byte[] gif = small.save(SaveOptions.gif());
            assertThat(new String(gif, 0, 4, US_ASCII)).isEqualTo("GIF8");
            try (Image back = libvips.load(gif)) {
                assertThat(back.width()).isEqualTo(16);
                assertThat(back.height()).isEqualTo(9);
                assertThat(back.loader()).isEqualTo("gifload_buffer");
            }
        }
    }

    @Test
    void gifArgumentsReachTheSaver() {
        assumeTrue(expectGif() || libvips.hasOperation("gifsave_buffer"),
                "SKIPPED (libvips lacks gifsave_buffer): built without cgif");
        try (Image i = libvips.load(TestLibvips.portrait()); Image small = i.crop(0, 0, 64, 48)) {
            byte[] plain = small.save(SaveOptions.gif());
            byte[] interlaced = small.save(new SaveOptions.Gif(7, 8, true, Set.of()));
            byte[] twoColours = small.save(new SaveOptions.Gif(7, 1, false, Set.of()));
            // the colour table holds the colours used (rounded up to a power of two), at most 2^bitdepth
            assertThat(gifFirstImage(plain)[0]).isZero();
            assertThat(gifFirstImage(plain)[1]).isGreaterThan(1);
            assertThat(gifFirstImage(interlaced)[0]).isOne();
            assertThat(gifFirstImage(twoColours)).containsExactly(0, 1);
        }
    }

    @Test
    void formatDiscovery() {
        assertThat(libvips.saverFor(".webp")).isPresent();
        assertThat(libvips.saverFor(".nope")).isEmpty();
        assertThat(ErrorBuffer.take()).isEmpty();
        assertThat(libvips.hasOperation("webpsave_buffer")).isTrue();
        assertThat(libvips.hasOperation("nosuchsave_buffer")).isFalse();
        assertThat(libvips.saveSuffixes()).contains(".jpg", ".png", ".webp");
    }

    /** {@return the second byte of the first start-of-frame marker (0xc0 baseline, 0xc2 progressive)} */
    private static int jpegFrameMarker(byte[] jpg) {
        int at = 2; // after SOI
        while (at + 4 <= jpg.length && (jpg[at] & 0xff) == 0xff) {
            int marker = jpg[at + 1] & 0xff;
            if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return marker;
            }
            at += 2 + (((jpg[at + 2] & 0xff) << 8) | (jpg[at + 3] & 0xff));
        }
        throw new AssertionError("no start-of-frame marker");
    }

    /**
     * {@return the first image of a GIF file as {interlaced (0 or 1), colour table bits (1 to 8)}}; the colour table is
     * the image's local one when it has one, else the global one
     */
    private static int[] gifFirstImage(byte[] gif) {
        int screenFlags = gif[10] & 0xff;
        int globalBits = (screenFlags & 0x80) != 0 ? (screenFlags & 0x07) + 1 : 0;
        int at = 13 + (globalBits == 0 ? 0 : 3 << globalBits);
        while (at < gif.length) {
            int block = gif[at] & 0xff;
            if (block == 0x2c) { // image descriptor
                int flags = gif[at + 9] & 0xff;
                int bits = (flags & 0x80) != 0 ? (flags & 0x07) + 1 : globalBits;
                return new int[] { (flags & 0x40) != 0 ? 1 : 0, bits };
            }
            if (block != 0x21) { // extension
                break;
            }
            at += 2;
            while ((gif[at] & 0xff) != 0) { // data sub-blocks
                at += 1 + (gif[at] & 0xff);
            }
            at++;
        }
        throw new AssertionError("no image descriptor");
    }

    /** {@return whether this run must save GIF (CI sets it): then a missing saver fails instead of skipping} */
    private static boolean expectGif() {
        return "true".equals(System.getenv("LIBVIPS_EXPECT_GIF"));
    }

    /** {@return the RIFF chunk names of a WebP file, in order} */
    private static List<String> webpChunks(byte[] webp) {
        List<String> chunks = new ArrayList<>();
        int at = 12;
        while (at + 8 <= webp.length) {
            chunks.add(new String(webp, at, 4, US_ASCII));
            int size = (webp[at + 4] & 0xff) | (webp[at + 5] & 0xff) << 8 | (webp[at + 6] & 0xff) << 16
                    | (webp[at + 7] & 0xff) << 24;
            at += 8 + size + (size & 1);
        }
        return chunks;
    }
}
