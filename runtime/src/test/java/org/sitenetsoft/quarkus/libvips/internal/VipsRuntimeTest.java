package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.quarkus.libvips.FailOn;
import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class VipsRuntimeTest {

    @AfterEach
    void restore() {
        VipsRuntime.start(Settings.defaults()); // the settings are process-global
    }

    /**
     * {@return the file the process has mapped as libvips} (the real file behind the soname, never a copy in the
     * temporary directory)
     */
    static String realPath() {
        VipsRuntime.start(Settings.defaults());
        String tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString();
        try (Stream<String> lines = Files.lines(Path.of("/proc/self/maps"))) {
            return lines.map(line -> line.substring(line.indexOf('/') < 0 ? line.length() : line.indexOf('/')))
                    .filter(p -> Path.of(p).getFileName() != null
                            && Path.of(p).getFileName().toString().startsWith("libvips.so.42"))
                    .filter(p -> !p.startsWith(tmp))
                    .findFirst().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void startTwiceSameLibraryUnderAnotherName() {
        VipsRuntime a = VipsRuntime.start(Settings.defaults());
        VipsRuntime b = VipsRuntime.start(Settings.defaults().withLibrary(realPath()));
        assertThat(b.version()).isEqualTo(a.version());
        assertThat(a.version().atLeast(8, 15)).isTrue();
    }

    @Test
    void aSecondCopyOfLibvipsIsRefused(@TempDir Path dir) throws Exception {
        VipsRuntime.start(Settings.defaults());
        Path copy = dir.resolve("libvips.so.42");
        Files.copy(Path.of(realPath()), copy);
        assertThatThrownBy(() -> VipsRuntime.start(Settings.defaults().withLibrary(copy.toString())))
                .isInstanceOf(LibvipsUnavailableException.class).hasMessage("libvips is unavailable")
                .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING).contains("restart the JVM");
    }

    @Test
    void settingsAreApplied() {
        VipsRuntime r = VipsRuntime.start(new Settings(Optional.empty(), OptionalInt.of(2), 7, 3 * 1024 * 1024, 2,
                268402689, 134217728, FailOn.NONE, true, List.of(), List.of()));
        assertThat(r.effective()).isEqualTo(new VipsRuntime.Effective(2, 7, 3 * 1024 * 1024, 2));
    }

    @Test
    void restartResetsBlockedAndConcurrency() {
        VipsRuntime.start(
                Settings.defaults().withConcurrency(3).withSecurity(true, List.of("pngload_buffer"), List.of()));
        VipsRuntime r = VipsRuntime.start(Settings.defaults());
        assertThat(r.isBlocked("pngload_buffer")).isFalse();
        assertThat(r.effective().concurrency()).isEqualTo(2); // VIPS_CONCURRENCY=2 from the wrapper/surefire
    }

    @Test
    void heifLoadBlockedIsRecordedAtStart() { // read by every health probe: no operation is created per call
        VipsRuntime r = VipsRuntime.start(Settings.defaults());
        assumeTrue(LibvipsNative.typeFind("VipsOperation", "heifload_buffer") != 0,
                "no HEIF loader in this libvips");
        boolean atStart = r.isBlocked("heifload_buffer");
        assertThat(r.heifLoadBlocked()).isEqualTo(atStart);
        LibvipsNative.operationBlockSet("VipsForeignLoadHeif", !atStart); // undone by restore()
        assertThat(r.isBlocked("heifload_buffer")).isEqualTo(!atStart);
        assertThat(r.heifLoadBlocked()).isEqualTo(atStart);
    }

    @Test
    void blockListsAcceptNicknamesAndRejectUnknownNames() {
        VipsRuntime r = VipsRuntime.start(Settings.defaults().withSecurity(true, List.of("pngload_buffer"), List.of()));
        assertThat(r.isBlocked("pngload_buffer")).isTrue();
        assertThatThrownBy(
                () -> VipsRuntime.start(Settings.defaults().withSecurity(true, List.of("nosuchload"), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quarkus.libvips.security.blocked-operations: unknown operation nosuchload");
    }

    @Test
    void blockListsAcceptAbstractClassNames() {
        // vips_type_find skips abstract classes; the g_type_from_name fallback finds them
        VipsRuntime r = VipsRuntime.start(
                Settings.defaults().withSecurity(true, List.of("VipsForeignLoadPng"), List.of()));
        assertThat(r.isBlocked("pngload_buffer")).isTrue();
        assertThat(r.isBlocked("pngload")).isTrue();
        r = VipsRuntime.start(Settings.defaults()
                .withSecurity(true, List.of("VipsForeignLoadPng"), List.of("VipsForeignLoadPngBuffer")));
        assertThat(r.isBlocked("pngload_buffer")).isFalse();
        assertThat(r.isBlocked("pngload")).isTrue();
        assertThatThrownBy(
                () -> VipsRuntime.start(Settings.defaults().withSecurity(true, List.of(), List.of("VipsImage"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quarkus.libvips.security.allowed-operations: unknown operation VipsImage");
    }

    @Test
    void untrustedLoadersAreBlockedByDefault() {
        VipsRuntime r = VipsRuntime.start(Settings.defaults());
        assertBlocked(r, "ppmload", true); // ppmload_buffer only exists from libvips 8.17
        assertBlocked(r, "jpegload_buffer", false);
        r = VipsRuntime.start(Settings.defaults().withSecurity(false, List.of(), List.of()));
        assertBlocked(r, "ppmload", false);
    }

    /** isBlocked is false for an unknown nickname, so check the operation exists before trusting a false. */
    static void assertBlocked(VipsRuntime r, String operation, boolean blocked) {
        assertThat(LibvipsNative.typeFind("VipsOperation", operation)).as(operation + " exists").isNotZero();
        assertThat(r.isBlocked(operation)).as(operation + " blocked").isEqualTo(blocked);
    }

    @Test
    void stopEmptiesTheOperationCacheAndKeepsItsLimit() {
        VipsRuntime r = VipsRuntime.start(Settings.defaults().withCacheMaxOperations(100)); // off by default
        MemorySegment op = LibvipsNative.operationNew("black");
        LibvipsNative.setInt(op, "width", 8);
        LibvipsNative.setInt(op, "height", 8);
        MemorySegment built = LibvipsNative.cacheOperationBuild(op);
        assertThat(built).isNotEqualTo(MemorySegment.NULL);
        LibvipsNative.unrefOutputs(built);
        LibvipsNative.unref(built);
        LibvipsNative.unref(op);
        assertThat(LibvipsNative.cacheGetSize()).isPositive();
        r.stop(false);
        assertThat(LibvipsNative.cacheGetSize()).isZero();
        assertThat(LibvipsNative.cacheGetMax()).isEqualTo(100);
    }

    @Test
    void shutdownWaitsForInFlightCalls() {
        VipsRuntime r = VipsRuntime.start(Settings.defaults());
        r.enter();
        long started = System.nanoTime();
        try {
            assertThat(r.stop(true)).isFalse();
        } finally {
            r.exit();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }

    @Test
    void malformedRecordedDefaultConcurrencyIsNamed() {
        String previous = System.getProperty(VipsRuntime.DEFAULT_CONCURRENCY_PROPERTY);
        System.setProperty(VipsRuntime.DEFAULT_CONCURRENCY_PROPERTY, "two");
        try {
            assertThatThrownBy(() -> VipsRuntime.start(Settings.defaults()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("system property " + VipsRuntime.DEFAULT_CONCURRENCY_PROPERTY
                            + " is not a number: two");
        } finally {
            if (previous == null) {
                System.clearProperty(VipsRuntime.DEFAULT_CONCURRENCY_PROPERTY);
            } else {
                System.setProperty(VipsRuntime.DEFAULT_CONCURRENCY_PROPERTY, previous);
            }
        }
    }

    @Test
    void stoppedRuntimeRefusesWithoutShuttingLibvipsDown() {
        VipsRuntime r = VipsRuntime.start(Settings.defaults());
        assertThat(r.stop(false)).isFalse();
        assertThat(r.stopped()).isTrue();
        assertThatThrownBy(r::checkUsable).isInstanceOf(LibvipsUnavailableException.class);
        assertThat(VipsRuntime.start(Settings.defaults()).available()).isTrue(); // libvips still initialised
    }

    @Test
    void shutdownIsSkippedWhileACallIsInFlight() {
        VipsRuntime r = VipsRuntime.start(Settings.defaults());
        r.enter();
        try {
            assertThat(r.stop(true)).isFalse(); // waits 2 s, then skips vips_shutdown
        } finally {
            r.exit();
        }
        assertThat(VipsRuntime.start(Settings.defaults()).available()).isTrue();
    }

    @Test
    void unavailableRuntimeRefusesEverything() {
        VipsRuntime r = VipsRuntime.unavailable("no libvips here");
        assertThat(r.available()).isFalse();
        assertThat(r.failure()).contains("no libvips here");
        assertThatThrownBy(r::checkUsable).isInstanceOf(LibvipsUnavailableException.class)
                .hasMessage("libvips is unavailable")
                .extracting(e -> ((LibvipsUnavailableException) e).detail(), STRING).isEqualTo("no libvips here");
        assertThatThrownBy(r::version).isInstanceOf(LibvipsUnavailableException.class);
        assertThatThrownBy(r::enter).isInstanceOf(LibvipsUnavailableException.class);
        assertThat(r.stop(true)).isFalse();
    }
}
