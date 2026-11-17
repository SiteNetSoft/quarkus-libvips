package org.sitenetsoft.quarkus.libvips;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
