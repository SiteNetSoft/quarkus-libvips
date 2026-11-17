package org.sitenetsoft.quarkus.libvips;

import java.util.Set;

/**
 * libvips's {@code VipsForeignKeep} (libvips 8.15 and later): which metadata a saver writes. An empty set keeps none.
 * libvips 8.18 also has a gain-map flag (32) that this enum does not expose, so gain maps are always dropped.
 */
public enum Keep {
    /** The EXIF block. */
    EXIF(1),
    /** The XMP block. */
    XMP(2),
    /** The IPTC block. */
    IPTC(4),
    /** The ICC colour profile. */
    ICC(8),
    /** libvips's "other" metadata class, such as PNG text comments; not everything the other flags leave out. */
    OTHER(16);

    private final int bit;

    Keep(int bit) {
        this.bit = bit;
    }

    /** {@return the flag bit} */
    public int bit() {
        return bit;
    }

    /**
     * {@return the flags value of {@code keep}; 0 (libvips's {@code NONE}) for an empty set}
     *
     * @param keep the metadata to keep
     */
    public static int bits(Set<Keep> keep) {
        int bits = 0;
        for (Keep k : keep) {
            bits |= k.bit;
        }
        return bits;
    }
}
