package org.sitenetsoft.quarkus.libvips;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.sitenetsoft.quarkus.libvips.internal.Operation;

/**
 * Options of the thumbnail methods ({@link Image#thumbnail}, {@link Libvips#thumbnail(byte[], int, ThumbnailOptions)},
 * {@link Libvips#thumbnail(java.nio.file.Path, int, ThumbnailOptions)}).
 *
 * @param height the box height; empty to size by width alone (the height follows the aspect ratio), which a crop
 *        and {@link Size#FORCE} do not allow
 * @param size which way the image may be resized
 * @param crop what to keep when cropping to fill the box; {@link Interesting#NONE} to fit inside it
 * @param noRotate {@code true} to ignore the EXIF orientation instead of turning the image upright
 * @param outputProfile the ICC profile to convert to, a file name or a built-in name such as {@code srgb}; empty to
 *        keep the image's colour space. Name files only from trusted configuration: libvips reads the file.
 * @param intent the rendering intent of that conversion
 */
public record ThumbnailOptions(OptionalInt height, Size size, Interesting crop, boolean noRotate,
        Optional<String> outputProfile, Intent intent) {

    /** {@code VIPS_MAX_COORD} of libvips 8.15 (8.18's default is ten times more, and configurable). */
    private static final int UNLIMITED_HEIGHT = 10_000_000;

    /** By width alone, only down, no crop, upright, no profile conversion, relative intent. */
    public static final ThumbnailOptions DEFAULT = new ThumbnailOptions(OptionalInt.empty(), Size.DOWN,
            Interesting.NONE, false, Optional.empty(), Intent.RELATIVE);

    /**
     * Checks the values.
     *
     * @throws NullPointerException when a value is {@code null}
     * @throws IllegalArgumentException when {@code crop} is not {@link Interesting#NONE} or {@code size} is
     *         {@link Size#FORCE} without a height
     */
    public ThumbnailOptions {
        Objects.requireNonNull(height, "height");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(crop, "crop");
        Objects.requireNonNull(outputProfile, "outputProfile");
        Objects.requireNonNull(intent, "intent");
        if (height.isEmpty() && (crop != Interesting.NONE || size == Size.FORCE)) {
            throw new IllegalArgumentException("a crop or Size.FORCE needs a height");
        }
    }

    /**
     * {@return options that fill a box of the thumbnail's width and {@code height}, up or down, cropped by
     * {@code crop}}
     *
     * @param height the box height
     * @param crop what to keep, such as {@link Interesting#CENTRE} or {@link Interesting#ATTENTION}
     */
    public static ThumbnailOptions box(int height, Interesting crop) {
        return new ThumbnailOptions(OptionalInt.of(height), Size.BOTH, crop, false, Optional.empty(),
                Intent.RELATIVE);
    }

    /**
     * Sets these options on a {@code thumbnail}, {@code thumbnail_buffer} or {@code thumbnail_image} operation. The
     * output profile goes to {@code output_profile} (libvips 8.17 and later, where {@code export_profile} is
     * deprecated) or {@code export_profile} (before). The input profile is never set.
     */
    void applyTo(Operation op) {
        // libvips's default height is the width (a square box); by width alone is a box as tall as libvips allows,
        // as vipsthumbnail does for a "128x" geometry
        op.set("height", height.orElseGet(() -> byWidthHeight(op.intMaximum("height"))));
        op.set("size", size);
        op.set("crop", crop);
        op.set("no_rotate", noRotate);
        outputProfile.ifPresent(profile -> op.set(op.firstInput("output_profile", "export_profile")
                .orElseThrow(() -> new IllegalStateException(op.nickname() + " has no output profile argument")),
                profile));
        op.set("intent", intent);
    }

    /**
     * {@return the height that sizes by width alone: 10 000 000, or less when the {@code height} argument's maximum is
     * lower (libvips 8.18 with a smaller {@code VIPS_MAX_COORD})}
     *
     * @param maximum the {@code height} argument's maximum, empty when unknown
     */
    static int byWidthHeight(OptionalInt maximum) {
        return Math.min(UNLIMITED_HEIGHT, maximum.orElse(UNLIMITED_HEIGHT));
    }
}
