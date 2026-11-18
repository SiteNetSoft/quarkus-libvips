package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.TestLibvips;
import org.sitenetsoft.quarkus.libvips.ThumbnailOptions;
import org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable;

/**
 * {@code leak-check}: libvips's leak checking is switched on ({@code vips_leak_set}), and stopping the runtime reports
 * what is still alive: images never closed, and libvips's tracked memory and files.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class LeakCheckTest {

    @AfterEach
    void restore() {
        VipsRuntime.start(Settings.defaults());
    }

    @Test
    void offByDefault() {
        assertThat(Settings.defaults().leakCheck()).isFalse();
        assertThat(VipsRuntime.start(Settings.defaults()).leakCheck()).isFalse();
    }

    @Test
    void aCleanRunReportsNoLeak() {
        Libvips libvips = TestLibvips.start(Settings.defaults().withLeakCheck(true));
        try (Image t = libvips.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT)) {
            assertThat(t.width()).isEqualTo(50);
        }
        VipsRuntime.LeakReport report = runtime(libvips).leakReport();
        assertThat(report.openImages()).isZero();
        assertThat(report.leakedImages()).isZero();
        assertThat(report.allocations()).isZero();
        assertThat(report.bytes()).isZero();
        assertThat(report.files()).isZero();
        assertThat(report.highWater()).isPositive(); // the thumbnail's pixels were tracked while it ran
        assertThat(report.clean()).isTrue();
        assertThat(report.toString()).startsWith("libvips leak check: no leaks; tracked memory high-water mark ");
    }

    @Test
    void anImageNeverClosedIsReported() {
        Libvips libvips = TestLibvips.start(Settings.defaults().withLeakCheck(true));
        Image kept = libvips.load(TestLibvips.portrait());
        try {
            VipsRuntime.LeakReport report = runtime(libvips).leakReport();
            assertThat(report.openImages()).isEqualTo(1);
            assertThat(report.clean()).isFalse();
            assertThat(report.toString()).startsWith("libvips leak check: 1 image(s) still open, 0 released by the"
                    + " cleaner (never closed); libvips tracked memory: ");
            assertThat(report.toString()).contains("libvips lists the objects still alive on standard error");
        } finally {
            kept.close();
        }
    }

    /**
     * libvips adds an area to its leak list only while leak checking is on, and removes it only if it is still on when
     * the area is freed; switching it off in between leaves freed memory in the list, which the next report reads. So
     * the switch only ever goes on: a later start without leak-check leaves libvips checking, and only the extension's
     * report follows the setting.
     */
    @Test
    void libvipsLeakCheckingIsNeverSwitchedOff() {
        Libvips on = TestLibvips.start(Settings.defaults().withLeakCheck(true));
        assertThat(VipsRuntime.libvipsLeakChecking()).isTrue();
        Image kept = on.load(TestLibvips.portrait()); // created while checking is on
        VipsRuntime off = VipsRuntime.start(Settings.defaults());
        assertThat(off.leakCheck()).isFalse();
        assertThat(VipsRuntime.libvipsLeakChecking()).as("still on in libvips").isTrue();
        kept.close(); // freed after a start without leak-check: libvips still removes it from its list
        VipsRuntime again = VipsRuntime.start(Settings.defaults().withLeakCheck(true));
        assertThat(again.stop(false)).isFalse(); // report + vips_object_print_all over libvips's lists
        assertThat(java.util.Arrays.stream(LibvipsNative.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("leak")))
                .as("no way to pass FALSE to vips_leak_set")
                .allSatisfy(m -> assertThat(m.getParameterCount()).isZero());
    }

    @Test
    void stopReportsOnlyWhenOn() {
        assertThat(VipsRuntime.start(Settings.defaults().withLeakCheck(true)).stop(false)).isFalse();
        assertThat(VipsRuntime.start(Settings.defaults()).stop(false)).isFalse();
    }

    /**
     * libvips's own report proves {@code vips_leak_set(TRUE)} reached it: {@code vips_shutdown} prints its summary
     * ("memory: high-water mark ...") to standard error only with leak checking on. Shutdown cannot be undone, so each
     * case runs in a JVM of its own.
     */
    @Test
    void libvipsLeakCheckingIsSwitchedOn() throws Exception {
        assertThat(stderrOfShutdown(true)).contains("memory: high-water mark");
        assertThat(stderrOfShutdown(false)).doesNotContain("memory: high-water mark");
    }

    private static String stderrOfShutdown(boolean leakCheck) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process p = new ProcessBuilder(List.of(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("java.class.path"), ShutdownMain.class.getName(), Boolean.toString(leakCheck),
                LibvipsAvailable.library()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(stderr).isZero();
        assertThat(stderr).as(stderr).contains("shutdown ran: true");
        return stderr;
    }

    private static VipsRuntime runtime(Libvips libvips) {
        return TestLibvips.runtime(libvips);
    }

    /** Starts libvips with or without leak checking and shuts it down. */
    public static final class ShutdownMain {

        private ShutdownMain() {
        }

        /**
         * @param args leak check ({@code true}/{@code false}), the library
         */
        public static void main(String[] args) {
            VipsRuntime r = VipsRuntime.start(Settings.defaults().withLibrary(args[1])
                    .withLeakCheck(Boolean.parseBoolean(args[0])));
            System.err.println("shutdown ran: " + r.stop(true));
        }
    }
}
