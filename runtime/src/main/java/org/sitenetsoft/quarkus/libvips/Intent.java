package org.sitenetsoft.quarkus.libvips;

import java.util.Locale;

/**
 * libvips's {@code VipsIntent}: the ICC rendering intent of a colour transform.
 */
public enum Intent implements VipsEnum {
    /** Perceptual. */
    PERCEPTUAL,
    /** Relative colorimetric (libvips's default). */
    RELATIVE,
    /** Saturation. */
    SATURATION,
    /** Absolute colorimetric. */
    ABSOLUTE;

    /** {@return the libvips nick, such as {@code relative}} */
    @Override
    public String nick() {
        return name().toLowerCase(Locale.ROOT);
    }
}
