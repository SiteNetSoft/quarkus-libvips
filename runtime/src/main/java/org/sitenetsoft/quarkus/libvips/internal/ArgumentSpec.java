package org.sitenetsoft.quarkus.libvips.internal;

import java.lang.foreign.MemorySegment;

/**
 * One argument of a libvips operation, read from its {@code GParamSpec}: the value's {@code GType} and its
 * {@code VipsArgumentFlags}.
 *
 * @param valueType the {@code GType} the argument holds
 * @param flags the {@code VipsArgumentFlags} bits (see {@link #INPUT}, {@link #OUTPUT}, {@link #DEPRECATED},
 *        {@link #MODIFY})
 * @param pspec the {@code GParamSpec *}; owned by the operation's class, which lives as long as the process
 */
public record ArgumentSpec(long valueType, int flags, MemorySegment pspec) {

    /** {@code VIPS_ARGUMENT_INPUT}: the caller sets the argument. */
    public static final int INPUT = 16;

    /** {@code VIPS_ARGUMENT_OUTPUT}: the operation sets the argument. */
    public static final int OUTPUT = 32;

    /** {@code VIPS_ARGUMENT_DEPRECATED}: kept by libvips for compatibility only. */
    public static final int DEPRECATED = 64;

    /** {@code VIPS_ARGUMENT_MODIFY}: the operation changes the input in place (the {@code draw_*} operations). */
    public static final int MODIFY = 128;
}
