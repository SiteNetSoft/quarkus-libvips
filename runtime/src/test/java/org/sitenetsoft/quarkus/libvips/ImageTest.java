package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class ImageTest {

    Libvips libvips;

    @BeforeEach
    void start() {
        libvips = TestLibvips.start(Settings.defaults());
    }

    @Test
    void loadsJpegHeaderFromBytesAndFile() {
        try (Image i = libvips.load(TestLibvips.portrait()); Image f = libvips.load(TestLibvips.portraitPath())) {
            assertThat(List.of(i.width(), i.height(), i.bands(), i.pages())).containsExactly(400, 300, 3, 1);
            assertThat(i.hasAlpha()).isFalse();
            assertThat(f.width()).isEqualTo(400);
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void loaderForBytesAndFile() {
        assertThat(libvips.loaderFor(TestLibvips.portrait())).contains("VipsForeignLoadJpegBuffer");
        assertThat(libvips.loaderFor(TestLibvips.portraitPath())).contains("VipsForeignLoadJpegFile");
        assertThat(libvips.loaderFor(new byte[] { 1, 2, 3, 4 })).isEmpty();
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void garbageHasNoLoader() {
        assertThatThrownBy(() -> libvips.load(new byte[] { 1, 2, 3, 4 }))
                .isInstanceOfSatisfying(UnsupportedFormatException.class, e -> {
                    assertThat(e.reason()).isEqualTo(UnsupportedFormatException.Reason.NO_LOADER);
                    assertThat(e.operation()).isEqualTo("vips_foreign_find_load_buffer");
                });
    }

    @Test
    void emptyInputRefusedWithoutNativeCall() {
        assertThatThrownBy(() -> libvips.load(new byte[0]))
                .isInstanceOfSatisfying(UnsupportedFormatException.class,
                        e -> assertThat(e.operation()).isEqualTo("load"));
    }

    @Test
    void closeIsIdempotentAndUseAfterCloseThrows() {
        Image i = libvips.load(TestLibvips.portrait());
        i.close();
        i.close();
        assertThatThrownBy(i::width).isInstanceOf(IllegalStateException.class).hasMessage("image is closed");
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void derivedImageOutlivesItsSource() {
        Image src = libvips.load(TestLibvips.portrait());
        Image half = src.call("resize", Map.of("scale", 0.5));
        src.close();
        try (half) {
            assertThat(half.width()).isEqualTo(200);
        }
    }

    @Test
    void callValidatesAgainstGParamSpec() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThatThrownBy(() -> i.call("resize", Map.of("nosuch", 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("resize has no argument nosuch");
            assertThatThrownBy(() -> i.call("resize", Map.of("scale", "x")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scale expects gdouble");
            assertThatThrownBy(() -> i.call("resize", Map.of("scale", 0.5, "out", i)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("resize: out is not an input");
            assertThatThrownBy(() -> i.call("jpegsave_buffer", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("VipsForeign");
            try (Image cut = i.call("extract_area", Map.of("left", 0, "top", 0, "width", 10, "height", 10))) {
                assertThat(cut.width()).isEqualTo(10); // image input named "input" (N4)
            }
            assertThat(ErrorBuffer.take()).isEmpty(); // validation took its own "no property named" text (N27)
            assertThatThrownBy(() -> i.call("no_such_operation", Map.of()))
                    .isInstanceOfSatisfying(LibvipsException.class, e -> {
                        assertThat(e.operation()).isEqualTo("no_such_operation");
                        assertThat(e.detail()).contains("no_such_operation");
                    });
        }
    }

    @Test
    void buildFailureCarriesOperationAndDetail() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThatThrownBy(
                    () -> i.call("extract_area", Map.of("left", 0, "top", 0, "width", 10000, "height", 1)))
                    .isInstanceOfSatisfying(LibvipsException.class, e -> {
                        assertThat(e.operation()).isEqualTo("extract_area");
                        assertThat(e.detail()).contains("bad extract area");
                    });
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void operationReferencesDoNotLeak() { // N12
        try (Image i = libvips.load(TestLibvips.portrait())) {
            try (Image warm = i.call("resize", Map.of("scale", 0.5))) {
                assertThat(warm.width()).isEqualTo(200);
            }
            long memory = libvips.trackedMemory();
            for (int n = 0; n < 1000; n++) {
                try (Image r = i.call("resize", Map.of("scale", 0.25 + (n % 7) * 0.01))) {
                    r.width();
                }
                if (n % 100 == 0) {
                    assertThatThrownBy(
                            () -> i.call("extract_area", Map.of("left", 0, "top", 0, "width", 10000, "height", 1)))
                            .isInstanceOf(LibvipsException.class); // failure path unrefs outputs too
                }
            }
            assertThat(libvips.openImages()).isEqualTo(1);
            assertThat(libvips.trackedMemory()).isLessThanOrEqualTo(memory + 8L * 1024 * 1024); // operation cache only
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void callRefusesValuesOutsideTheGParamSpecBeforeBuilding() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThatThrownBy(() -> i.call("extract_area", Map.of("left", 0, "top", 0, "width", 0, "height", 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    // the maximum is 100000000 on libvips 8.18, 10000000 on 8.15
                    .hasMessageStartingWith("extract_area: width = 0 is out of range (1 to ")
                    .hasMessageEndingWith("00000)");
            assertThatThrownBy(() -> i.call("resize", Map.of("scale", Double.NaN)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("resize: scale = NaN is out of range");
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void callRefusesOperationsWithoutAnImageOutput() {
        try (Image i = libvips.load(TestLibvips.portrait()); Image other = libvips.load(TestLibvips.portrait())) {
            // draw_rect draws into its "image" argument in place; avg's "out" is a number
            assertThatThrownBy(() -> i.call("draw_rect",
                    Map.of("image", other, "ink", new double[] { 0 }, "left", 0, "top", 0, "width", 5, "height", 5)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("draw_rect has no image output out");
            assertThatThrownBy(() -> i.call("avg", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("avg has no image output out");
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void fileNamesCannotCarryLoaderOptions(@TempDir Path dir) throws Exception {
        // libvips reads "name[options]" when it parses file names; a sibling without the brackets must not be sniffed
        Path bracketed = dir.resolve("upload.jpg[shrink=2]");
        Files.write(bracketed, TestLibvips.portrait());
        Files.writeString(dir.resolve("upload.jpg"), "not an image", StandardCharsets.US_ASCII);
        assertThat(libvips.loaderFor(bracketed)).contains("VipsForeignLoadJpegFile");
        try (Image i = libvips.load(bracketed)) {
            assertThat(List.of(i.width(), i.height())).containsExactly(400, 300); // shrink=2 would give 200x150
        }
        Path alone = dir.resolve("alone.jpg[shrink=4]");
        Files.write(alone, TestLibvips.portrait());
        try (Image i = libvips.load(alone)) {
            assertThat(i.width()).isEqualTo(400);
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void leakedImageIsReleasedByCleaner() throws Exception {
        long before = libvips.leakedImages();
        libvips.load(TestLibvips.portrait()); // never closed
        for (int n = 0; n < 50 && libvips.leakedImages() == before; n++) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat(libvips.leakedImages()).isEqualTo(before + 1);
    }
}
