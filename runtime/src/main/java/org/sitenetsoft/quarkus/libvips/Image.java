package org.sitenetsoft.quarkus.libvips;

import java.util.Map;
import java.util.Objects;

import org.sitenetsoft.quarkus.libvips.internal.NativeImage;
import org.sitenetsoft.quarkus.libvips.internal.Operation;
import org.sitenetsoft.quarkus.libvips.internal.VipsRuntime;

/**
 * A libvips image. Images are immutable: every transform returns a new {@code Image} and leaves this one open. A
 * derived image keeps what it needs alive inside libvips, so closing its source early is safe.
 * <p>
 * Always {@link #close()} an image (try-with-resources). {@code close} is idempotent and thread-safe, and any use
 * after it throws {@link IllegalStateException}. An image that is never closed is released by a cleaner after it
 * becomes unreachable and counted in {@link Libvips#leakedImages()}; that is a safety net, not a way to manage memory.
 * <p>
 * An image may be read from several threads at once.
 */
public final class Image implements AutoCloseable {

    private final NativeImage image;
    private final Libvips libvips;

    Image(NativeImage image, Libvips libvips) {
        this.image = image;
        this.libvips = libvips;
    }

    /** {@return the width in pixels} */
    public int width() {
        return image.width();
    }

    /** {@return the height in pixels (of every page together, for a multi-page image)} */
    public int height() {
        return image.height();
    }

    /** {@return the number of bands (3 for sRGB, 4 with alpha, 1 for grey)} */
    public int bands() {
        return image.bands();
    }

    /** {@return the number of pages the image was loaded with} */
    public int pages() {
        return image.pages();
    }

    /** {@return the height of one page} */
    public int pageHeight() {
        return image.pageHeight();
    }

    /** {@return whether the last band is alpha} */
    public boolean hasAlpha() {
        return image.hasAlpha();
    }

    /**
     * Runs any libvips operation on this image, for anything the typed methods do not cover (such as {@code sharpen}
     * or {@code flatten}). This image goes into the operation's image input ({@code in}, or {@code input} for
     * operations such as {@code extract_area}) unless {@code arguments} names one of those; other {@code Image}
     * values in {@code arguments} are passed as images. Every argument is checked against the operation's
     * {@code GParamSpec} before anything runs.
     *
     * @param operation the operation's nickname, such as {@code resize}
     * @param arguments input arguments by name: {@code Integer}, {@code Number}, {@code Boolean}, {@code String},
     *        enums as {@link VipsEnum} or nick, flags as {@code Integer} or nick, {@code Image}, {@code byte[]},
     *        {@code double[]}
     * @return the operation's {@code out} image
     * @throws IllegalArgumentException for a loader or saver (use {@link Libvips#load} instead), an operation with no
     *         image input or no image output {@code out}, or an argument that is unknown, not an input, deprecated,
     *         modified in place, of the wrong type or outside its range
     * @throws LibvipsException when libvips has no such operation or the operation fails
     * @throws IllegalStateException when this image or an argument image is closed
     */
    public Image call(String operation, Map<String, Object> arguments) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(arguments, "arguments");
        VipsRuntime runtime = libvips.runtime();
        runtime.enter();
        try {
            if (runtime.isForeign(operation)) {
                throw new IllegalArgumentException(
                        operation + " is a VipsForeign loader or saver; use Libvips.load or Image.save");
            }
            try (Operation op = Operation.create(operation)) {
                if (!op.hasImageOutput("out")) { // also refuses the draw_* operations, which modify an input
                    throw new IllegalArgumentException(operation + " has no image output out");
                }
                if (!arguments.containsKey("in") && !arguments.containsKey("input")) {
                    String input = op.firstInput("in", "input").orElseThrow(() -> new IllegalArgumentException(
                            operation + " has no image input; pass it explicitly"));
                    op.set(input, image);
                }
                arguments.forEach((name, value) -> op.set(name, value instanceof Image i ? i.image : value));
                try (Operation.Built built = op.build()) {
                    return new Image(built.image("out", runtime), libvips);
                }
            }
        } finally {
            runtime.exit();
        }
    }

    /** Releases the image; idempotent and thread-safe. */
    @Override
    public void close() {
        image.close();
    }
}
