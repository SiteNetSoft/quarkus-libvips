package org.sitenetsoft.quarkus.libvips.internal;

/**
 * One argument of a libvips operation, read from its {@code GParamSpec}: the value's {@code GType} and its
 * {@code VipsArgumentFlags}.
 *
 * @param valueType the {@code GType} the argument holds
 * @param flags the {@code VipsArgumentFlags} bits (see {@link #INPUT}, {@link #OUTPUT}, {@link #DEPRECATED})
 */
public record ArgumentSpec(long valueType, int flags) {

    /** {@code VIPS_ARGUMENT_INPUT}: the caller sets the argument. */
    public static final int INPUT = 16;

    /** {@code VIPS_ARGUMENT_OUTPUT}: the operation sets the argument. */
    public static final int OUTPUT = 32;

    /** {@code VIPS_ARGUMENT_DEPRECATED}: kept by libvips for compatibility only. */
    public static final int DEPRECATED = 64;
}
