package org.sitenetsoft.quarkus.libvips;

import java.util.Locale;

/**
 * libvips's {@code VipsFailOn}: the error level at which a load fails.
 */
public enum FailOn implements VipsEnum {
    /** Never fail (libvips's default): truncated or damaged images load as far as they can. */
    NONE,
    /** Fail on truncated images. */
    TRUNCATED,
    /** Fail on errors. */
    ERROR,
    /** Fail on anything, warnings included. */
    WARNING;

    /** {@return the libvips nick, such as {@code none}} */
    @Override
    public String nick() {
        return name().toLowerCase(Locale.ROOT);
    }
}
