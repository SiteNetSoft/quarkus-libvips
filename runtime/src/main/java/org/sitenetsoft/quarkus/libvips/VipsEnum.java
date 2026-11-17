package org.sitenetsoft.quarkus.libvips;

/**
 * A Java enum that stands for a libvips enum or flag value. The C value is resolved by nick against the argument's
 * own {@code GType} when the value is set, so no C value is hard-coded.
 */
public interface VipsEnum {

    /** {@return the libvips nick, such as {@code random}} */
    String nick();
}
