package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import org.sitenetsoft.quarkus.libvips.internal.Settings;
import org.sitenetsoft.quarkus.libvips.internal.VipsRuntime;

/** Starts libvips without Quarkus and gives the test fixture. */
public final class TestLibvips {

    /** Offset of the fixture's SOS marker: everything before it is header. */
    static final int SOS_OFFSET = 23276;

    private TestLibvips() {
    }

    /**
     * {@return a {@link Libvips} attached to a runtime started with {@code settings}}
     *
     * @param settings the settings (process-global in libvips: the last start wins)
     */
    public static Libvips start(Settings settings) {
        Libvips libvips = new Libvips();
        libvips.attach(VipsRuntime.start(settings));
        return libvips;
    }

    /**
     * {@return the runtime {@code libvips} is attached to}
     *
     * @param libvips a started {@link Libvips}
     */
    public static VipsRuntime runtime(Libvips libvips) {
        return libvips.runtime();
    }

    /** {@return the bytes of {@code portrait.jpg}} (400×300, EXIF orientation 6, embedded sRGB profile) */
    public static byte[] portrait() {
        try (InputStream in = Objects.requireNonNull(TestLibvips.class.getResourceAsStream("/portrait.jpg"))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@return the file {@code portrait.jpg}} */
    public static Path portraitPath() {
        try {
            return Path.of(Objects.requireNonNull(TestLibvips.class.getResource("/portrait.jpg")).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@return {@code portrait.jpg} without its last 2000 bytes: cut inside the scan data, so the header loads} */
    public static byte[] truncatedPortrait() {
        byte[] full = portrait();
        assertThat(full.length - 2000).isGreaterThan(SOS_OFFSET + 64);
        return Arrays.copyOf(full, full.length - 2000);
    }
}
