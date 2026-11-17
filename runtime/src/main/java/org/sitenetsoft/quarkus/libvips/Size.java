package org.sitenetsoft.quarkus.libvips;

import java.util.Locale;

/**
 * libvips's {@code VipsSize}: which way a thumbnail may be resized.
 */
public enum Size implements VipsEnum {
    /** Up or down (libvips's default). */
    BOTH,
    /** Only up: a larger image is left at its size. */
    UP,
    /** Only down: a smaller image is left at its size. */
    DOWN,
    /** To exactly the width and height given, changing the aspect ratio. */
    FORCE;

    /** {@return the libvips nick, such as {@code down}} */
    @Override
    public String nick() {
        return name().toLowerCase(Locale.ROOT);
    }
}
