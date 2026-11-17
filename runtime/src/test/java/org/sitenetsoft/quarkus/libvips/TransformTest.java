package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Operation;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class TransformTest {

    Libvips libvips;

    @BeforeEach
    void start() {
        libvips = TestLibvips.start(Settings.defaults());
    }

    @Test
    void autorotate() {
        try (Image i = libvips.load(TestLibvips.portrait()); Image r = i.autorotate()) {
            assertThat(List.of(r.width(), r.height(), r.orientation())).containsExactly(300, 400, 1);
        }
    }

    @Test
    void thumbnailCropCentre() {
        try (Image i = libvips.load(TestLibvips.portrait());
                Image t = i.thumbnail(150, ThumbnailOptions.box(150, Interesting.CENTRE))) {
            assertThat(List.of(t.width(), t.height())).containsExactly(150, 150);
        }
    }

    @Test
    void thumbnailFromBytesAutorotatesAndKeepsAspect() {
        try (Image t = libvips.thumbnail(TestLibvips.portrait(), 200, ThumbnailOptions.DEFAULT)) {
            assertThat(t.width()).isEqualTo(200);
            assertThat(t.height()).isCloseTo(267, within(1));
        }
    }

    @Test
    void thumbnailNoRotate() {
        var o = new ThumbnailOptions(OptionalInt.empty(), Size.DOWN, Interesting.NONE, true, Optional.empty(),
                Intent.RELATIVE);
        try (Image t = libvips.thumbnail(TestLibvips.portraitPath(), 200, o)) {
            assertThat(List.of(t.width(), t.height())).containsExactly(200, 150);
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void thumbnailWithOutputProfile() { // output_profile on 8.17+, export_profile before
        var o = new ThumbnailOptions(OptionalInt.empty(), Size.DOWN, Interesting.NONE, false, Optional.of("srgb"),
                Intent.RELATIVE);
        try (Image t = libvips.thumbnail(TestLibvips.portrait(), 100, o)) {
            assertThat(t.interpretation()).isEqualTo("srgb");
            assertThat(t.width()).isEqualTo(100);
        }
        try (Image i = libvips.load(TestLibvips.portrait()); Image t = i.thumbnail(100, o)) {
            assertThat(t.interpretation()).isEqualTo("srgb");
            assertThat(List.of(t.width(), t.height())).containsExactly(100, 133); // autorotated first
        }
    }

    @Test
    void resizeCropSmartCrop() { // extract_area and smartcrop take their image as "input"
        try (Image i = libvips.load(TestLibvips.portrait()); Image h = i.resize(0.5); Image c = i.crop(10, 20, 100, 50);
                Image s = i.smartCrop(100, 100, Interesting.ATTENTION)) {
            assertThat(List.of(h.width(), h.height(), c.width(), c.height(), s.width(), s.height()))
                    .containsExactly(200, 150, 100, 50, 100, 100);
            assertThatThrownBy(() -> i.crop(350, 0, 100, 10)).isInstanceOfSatisfying(LibvipsException.class,
                    e -> assertThat(e.operation()).isEqualTo("extract_area"));
        }
    }

    @Test
    void colourProfiles() {
        try (Image i = libvips.load(TestLibvips.portrait()); Image lab = i.iccImport();
                Image srgb = i.iccTransform("srgb", Intent.RELATIVE)) {
            assertThat(lab.interpretation()).isEqualTo("lab");
            assertThat(srgb.interpretation()).isEqualTo("srgb");
            assertThat(srgb.bands()).isEqualTo(3);
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void fileNamesCannotCarryLoaderOptions(@TempDir Path dir) throws Exception {
        // libvips's thumbnail operation reads "name[options]" from its file name; each bracketed file below sits
        // next to a different, smaller "sib.jpg", so a thumbnail of the wrong file would be 64x48
        BufferedImage small = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(small, "jpg", dir.resolve("sib.jpg").toFile())).isTrue();
        for (String name : List.of("sib.jpg[access=sequential]", "sib.jpg[bogus_option=1]", "sib.jpg[shrink=8]",
                "sib.jpg[]", "sib.jpg[access=sequential][]")) {
            Path bracketed = dir.resolve(name);
            Files.write(bracketed, TestLibvips.portrait());
            try (Image t = libvips.thumbnail(bracketed, 200, ThumbnailOptions.DEFAULT)) {
                assertThat(List.of(t.width(), t.height())).as(name).containsExactly(200, 267);
            }
        }
        assertThat(libvips.openImages()).isZero();
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void fileNamesEndingInWhitespaceAreRefused(@TempDir Path dir) throws Exception {
        // refused defensively: libvips's file-name and option splitting skips these characters as white space
        for (String name : List.of("sib.jpg ", "sib.jpg\t", "sib.jpg\n", "sib.jpg\r")) {
            Path file = dir.resolve(name);
            Files.write(file, TestLibvips.portrait());
            assertThatThrownBy(() -> libvips.load(file)).as(name).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("white space");
            assertThatThrownBy(() -> libvips.loaderFor(file)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> libvips.thumbnail(file, 100, ThumbnailOptions.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void thumbnailOptionsCheckTheirValues() {
        assertThat(ThumbnailOptions.DEFAULT).isEqualTo(new ThumbnailOptions(OptionalInt.empty(), Size.DOWN,
                Interesting.NONE, false, Optional.empty(), Intent.RELATIVE));
        assertThat(ThumbnailOptions.box(80, Interesting.ENTROPY)).isEqualTo(new ThumbnailOptions(OptionalInt.of(80),
                Size.BOTH, Interesting.ENTROPY, false, Optional.empty(), Intent.RELATIVE));
        assertThat(List.of(Size.FORCE.nick(), Interesting.ATTENTION.nick(), Intent.ABSOLUTE.nick()))
                .containsExactly("force", "attention", "absolute");
        assertThatThrownBy(() -> new ThumbnailOptions(OptionalInt.empty(), Size.BOTH, Interesting.CENTRE, false,
                Optional.empty(), Intent.RELATIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ThumbnailOptions(OptionalInt.empty(), Size.FORCE, Interesting.NONE, false,
                Optional.empty(), Intent.RELATIVE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thumbnailFromFileWithSmartCrop() {
        try (Image t = libvips.thumbnail(TestLibvips.portraitPath(), 120,
                ThumbnailOptions.box(80, Interesting.ATTENTION))) {
            assertThat(List.of(t.width(), t.height())).containsExactly(120, 80);
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void thumbnailOfEmptyOrUnknownBytesHasNoLoader() {
        assertThatThrownBy(() -> libvips.thumbnail(new byte[0], 100, ThumbnailOptions.DEFAULT))
                .isInstanceOf(UnsupportedFormatException.class);
        assertThatThrownBy(() -> libvips.thumbnail(new byte[] { 1, 2, 3, 4 }, 100, ThumbnailOptions.DEFAULT))
                .isInstanceOfSatisfying(UnsupportedFormatException.class,
                        e -> assertThat(e.reason()).isEqualTo(UnsupportedFormatException.Reason.NO_LOADER));
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void byWidthHeightNeverExceedsTheReportedMaximum() {
        try (Operation op = Operation.create("thumbnail_image")) {
            OptionalInt maximum = op.intMaximum("height");
            assertThat(maximum).isPresent();
            assertThat(ThumbnailOptions.byWidthHeight(maximum)).isPositive().isLessThanOrEqualTo(maximum.getAsInt())
                    .isLessThanOrEqualTo(10_000_000);
        }
        assertThat(ThumbnailOptions.byWidthHeight(OptionalInt.of(50_000))).isEqualTo(50_000); // VIPS_MAX_COORD=50000
        assertThat(ThumbnailOptions.byWidthHeight(OptionalInt.of(100_000_000))).isEqualTo(10_000_000);
        assertThat(ThumbnailOptions.byWidthHeight(OptionalInt.empty())).isEqualTo(10_000_000);
        try (Operation op = Operation.create("thumbnail_image")) {
            assertThat(op.intMaximum("no_rotate")).isEmpty(); // a boolean
            assertThat(op.intMaximum("nope")).isEmpty();
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void sizeByHeightAlone() {
        try (Image t = libvips.thumbnail(TestLibvips.portrait(), 10_000_000, new ThumbnailOptions(OptionalInt.of(100),
                Size.DOWN, Interesting.NONE, false, Optional.empty(), Intent.RELATIVE))) {
            assertThat(List.of(t.width(), t.height())).containsExactly(75, 100);
        }
    }

    @Test
    void cropAllKeepsEverything() {
        try (Image i = libvips.load(TestLibvips.portrait()); Image s = i.smartCrop(100, 100, Interesting.ALL);
                Image t = i.thumbnail(150, ThumbnailOptions.box(150, Interesting.ALL))) {
            assertThat(List.of(s.width(), s.height())).containsExactly(400, 300);
            assertThat(List.of(t.width(), t.height())).containsExactly(150, 200); // autorotated, covers the box
        }
    }

    @Test
    void thumbnailWidthIsChecked() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThatThrownBy(() -> i.thumbnail(0, ThumbnailOptions.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }
}
