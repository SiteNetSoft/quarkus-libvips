package org.sitenetsoft.quarkus.libvips;

import java.util.Locale;

/**
 * libvips's {@code VipsInteresting}: which part of an image a crop keeps.
 */
public enum Interesting implements VipsEnum {
    /** No crop (for a thumbnail: fit inside the box). */
    NONE,
    /** The centre. */
    CENTRE,
    /** The part with the most entropy. */
    ENTROPY,
    /** The part most likely to draw the eye (skin tones, saturated colour, edges). */
    ATTENTION,
    /** The top or left edge. */
    LOW,
    /** The bottom or right edge. */
    HIGH,
    /**
     * Keep everything. For a thumbnail the image covers the box and is not cropped, so it overflows the box in one
     * dimension (a 400&times;300 image in a 150&times;150 box gives 200&times;150); {@link Image#smartCrop} returns
     * the whole image.
     */
    ALL;

    /** {@return the libvips nick, such as {@code attention}} */
    @Override
    public String nick() {
        return name().toLowerCase(Locale.ROOT);
    }
}
