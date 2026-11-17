package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class SecurityTest {

    // ppmload_buffer only exists from libvips 8.17: the PPM tests go through a file (ppmload exists on 8.15)
    @TempDir
    static Path dir;
    static Path PPM;

    @BeforeAll
    static void ppm() throws IOException {
        byte[] header = "P6\n2 2\n255\n".getBytes(StandardCharsets.US_ASCII);
        byte[] data = Arrays.copyOf(header, header.length + 12);
        Arrays.fill(data, header.length, data.length, (byte) 0x80);
        PPM = Files.write(dir.resolve("tiny.ppm"), data);
    }

    /** Blocking is process-global: put the defaults back for the next test. */
    @AfterEach
    void restore() {
        TestLibvips.start(Settings.defaults());
    }

    @Test
    void untrustedLoaderBlockedByDefault() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        assertThat(libvips.isBlocked("ppmload")).isTrue();
        assertThatThrownBy(() -> libvips.load(PPM))
                .isInstanceOfSatisfying(UnsupportedFormatException.class, e -> { // a blocked format looks unknown
                    assertThat(e.reason()).isEqualTo(UnsupportedFormatException.Reason.NO_LOADER);
                    assertThat(e.getMessage()).contains("quarkus.libvips.security.");
                });
    }

    @Test
    void allowedOperationsUnblock() {
        Libvips libvips = TestLibvips
                .start(Settings.defaults().withSecurity(true, List.of(), List.of("VipsForeignLoadPpm")));
        try (Image i = libvips.load(PPM)) {
            assertThat(i.width()).isEqualTo(2);
        }
    }

    @Test
    void blockedOperationsAddToTheList() {
        Libvips libvips = TestLibvips
                .start(Settings.defaults().withSecurity(true, List.of("pngload_buffer"), List.of()));
        byte[] png;
        try (Image i = libvips.load(TestLibvips.portrait())) {
            png = i.save(SaveOptions.png());
        }
        assertThatThrownBy(() -> libvips.load(png)).isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void blockedOperationsAcceptNicknames() {
        Libvips libvips = TestLibvips
                .start(Settings.defaults().withSecurity(true, List.of("pngload_buffer"), List.of()));
        // class names work too: allowedOperationsUnblock uses VipsForeignLoadPpm
        assertThat(libvips.isBlocked("pngload_buffer")).isTrue();
    }

    @Test
    void isBlockedTakesNicknamesAndConcreteClassNames() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        assertThat(libvips.isBlocked("VipsForeignLoadPpmFile")).isTrue();
        assertThat(libvips.isBlocked("jpegload_buffer")).isFalse();
        assertThat(libvips.isBlocked("VipsForeignLoadJpegBuffer")).isFalse();
        assertThat(libvips.isBlocked("nosuchoperation")).isFalse();
        // an abstract class has no flags of its own to read: refused rather than answered "not blocked"
        assertThatThrownBy(() -> libvips.isBlocked("VipsForeignLoadPpm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abstract");
    }

    @Test
    void heifLoadFollowsLibvipsOwnMarking() { // no rule of our own
        Libvips libvips = TestLibvips.start(Settings.defaults());
        assumeTrue(libvips.hasOperation("heifload_buffer"));
        assertThat(libvips.status().heifLoadBlocked()).isEqualTo(libvips.isBlocked("heifload_buffer"));
        Libvips open = TestLibvips
                .start(Settings.defaults().withSecurity(true, List.of(), List.of("VipsForeignLoadHeif")));
        assertThat(open.isBlocked("heifload_buffer")).isFalse(); // the documented opt-in works where libvips blocks it
    }

    @Test
    void blockUntrustedOff() {
        Libvips libvips = TestLibvips.start(Settings.defaults().withSecurity(false, List.of(), List.of()));
        assertThat(libvips.isBlocked("ppmload")).isFalse();
    }

    @Test
    void profileArgumentsTakeBuiltInNamesOrAbsoluteRegularFiles() throws IOException {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        Path icc;
        try (Image i = libvips.load(TestLibvips.portrait())) {
            icc = Files.write(dir.resolve("embedded.icc"), i.iccProfile().orElseThrow());
            try (Image a = i.iccTransform("sRGB", Intent.RELATIVE);
                    Image b = i.iccTransform(icc.toAbsolutePath().toString(), Intent.RELATIVE);
                    Image c = i.call("icc_transform", Map.of("output_profile", "srgb", "embedded", true))) {
                assertThat(List.of(a.bands(), b.bands(), c.bands())).containsExactly(3, 3, 3);
            }
        }
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void profileArgumentsRefuseDevicesRelativeNamesAndMissingFiles() throws Exception {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        ErrorBuffer.take();
        assertThat(Path.of("pom.xml")).isRegularFile(); // a relative name that exists from the module directory
        Path fifo = fifo("profile.fifo"); // opening it would block the thread until a writer appears
        // every spelling GLib resolves to the same argument: '-' for '_', a "Type::" prefix, a NUL that C stops at
        List<String> inputSpellings = List.of("input_profile", "input-profile", "VipsIccImport::input_profile",
                "VipsIccImport::input-profile", "input_profile\0", "input-profile\0x");
        List<String> outputSpellings = List.of("output_profile", "output-profile", "VipsIccTransform::output_profile",
                "VipsIccTransform::output-profile", "output_profile\0");
        // export_profile is the 8.15 name of thumbnail_image's output profile (deprecated, not settable, later)
        List<String> exportSpellings = List.of("export_profile", "export-profile",
                "VipsThumbnailImage::export_profile", "VipsThumbnailImage::export-profile", "export_profile\0");
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                for (String name : List.of("/dev/zero", "/", "embedded.icc", "../etc/passwd", "pom.xml",
                        dir.resolve("missing.icc").toString(), "/" + "a".repeat(5000), fifo.toString())) {
                    assertThatThrownBy(() -> i.iccTransform(name, Intent.RELATIVE).close())
                            .as(name).isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("output_profile");
                    var options = new ThumbnailOptions(OptionalInt.empty(), Size.DOWN, Interesting.NONE, false,
                            Optional.of(name), Intent.RELATIVE);
                    assertThatThrownBy(() -> i.thumbnail(50, options).close()).as(name)
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> libvips.thumbnail(TestLibvips.portrait(), 50, options).close())
                            .as(name).isInstanceOf(IllegalArgumentException.class);
                    for (String argument : inputSpellings) {
                        assertThatThrownBy(() -> i.call("icc_import", Map.of(argument, name)).close())
                                .as(argument + " = " + name).isInstanceOf(IllegalArgumentException.class);
                    }
                    for (String argument : outputSpellings) {
                        assertThatThrownBy(() -> i.call("icc_transform", Map.of(argument, name)).close())
                                .as(argument + " = " + name).isInstanceOf(IllegalArgumentException.class);
                    }
                    for (String argument : exportSpellings) {
                        assertThatThrownBy(
                                () -> i.call("thumbnail_image", Map.of("width", 50, argument, name)).close())
                                .as(argument + " = " + name).isInstanceOf(IllegalArgumentException.class);
                    }
                }
            });
        }
        assertThat(libvips.openImages()).isZero();
        assertThat(ErrorBuffer.take()).isEmpty(); // refused before libvips opened anything
    }

    @Test
    void relativeFilePathsStillLoad() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        Path relative = Path.of("").toAbsolutePath().relativize(TestLibvips.portraitPath());
        assertThat(relative.isAbsolute()).isFalse();
        try (Image i = libvips.load(relative);
                Image t = libvips.thumbnail(relative, 50, ThumbnailOptions.DEFAULT)) {
            assertThat(i.width()).isEqualTo(400);
            assertThat(t.width()).isEqualTo(50);
        }
        assertThat(libvips.loaderFor(relative)).contains("VipsForeignLoadJpegFile");
    }

    static Path fifo(String name) throws Exception {
        Path fifo = dir.resolve(name);
        if (!Files.exists(fifo)) {
            Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
            assertThat(mkfifo.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mkfifo.exitValue()).isZero();
        }
        return fifo;
    }

    @Test
    void overLongFileNamesRefusedBeforeSniffing() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        // libvips cuts file names at 4096 bytes when it splits off [options], so the sniff would see another name
        Path longName = Path.of("/" + "a".repeat(4100) + ".jpg");
        assertThatThrownBy(() -> libvips.load(longName)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4093");
        assertThatThrownBy(() -> libvips.loaderFor(longName)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> libvips.thumbnail(longName, 50, ThumbnailOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemIsAlwaysBlocked() {
        for (Settings settings : List.of(Settings.defaults(), Settings.defaults().withSecurity(false, List.of(),
                List.of()), Settings.defaults().withSecurity(true, List.of(), List.of("VipsOperation")),
                Settings.defaults())) { // the last one is a restart after the others (block state reset)
            Libvips libvips = TestLibvips.start(settings);
            assertThat(libvips.isBlocked("system")).as(settings.toString()).isTrue();
            assertThat(libvips.isBlocked("VipsSystem")).isTrue();
        }
    }

    @Test
    void allowedOperationsCannotUnblockSystem() {
        for (String name : List.of("VipsSystem", "system")) {
            assertThatThrownBy(() -> TestLibvips
                    .start(Settings.defaults().withSecurity(true, List.of(), List.of(name))))
                    .as(name).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("system cannot be allowed");
        }
    }

    @Test
    void callRefusesOperationsThatRunCommandsOrOpenFilesFromMetadata() {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        Path marker = dir.resolve("ran");
        ErrorBuffer.take();
        List<String> names = new java.util.ArrayList<>(List.of("system", "VipsSystem", "globalbalance",
                "VipsGlobalbalance"));
        if (libvips.hasOperation("remosaic")) { // libvips 8.18 and later
            names.addAll(List.of("remosaic", "VipsRemosaic"));
        }
        try (Image i = libvips.load(TestLibvips.portrait())) {
            for (String name : names) {
                assertThatThrownBy(() -> i.call(name, Map.of()).close()).as(name)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("is not available");
            }
            assertThatThrownBy(() -> i.call("system", Map.of("cmd_format", "touch " + marker)).close())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not available");
        }
        assertThat(marker).doesNotExist();
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void filesThatAreNotRegularAreRefused() throws Exception {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        Path fifo = fifo("upload.fifo");
        // a FIFO would block the sniffing open for ever: refused without opening it, so this cannot hang
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (Path p : List.of(fifo, Path.of("/dev/zero"), dir)) {
                assertThatThrownBy(() -> libvips.load(p).close()).as(p.toString())
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a regular file");
                assertThatThrownBy(() -> libvips.thumbnail(p, 50, ThumbnailOptions.DEFAULT).close())
                        .as(p.toString()).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> libvips.loaderFor(p)).as(p.toString())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        });
        // a missing file is still reported as no loader
        assertThatThrownBy(() -> libvips.load(dir.resolve("missing.jpg")))
                .isInstanceOf(UnsupportedFormatException.class);
        ErrorBuffer.take();
    }
}
