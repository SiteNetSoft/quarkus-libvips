package org.sitenetsoft.quarkus.libvips;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Consumer;

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
            return derive(runtime, operation, arguments.containsKey("in") || arguments.containsKey("input"),
                    op -> arguments.forEach((name, value) -> op.set(name, value instanceof Image i ? i.image : value)));
        } finally {
            runtime.exit();
        }
    }

    /** {@return the EXIF orientation, 1 (upright) to 8; 1 when the image has none} */
    public int orientation() {
        return image.orientation();
    }

    /** {@return the interpretation (colour space) nick, such as {@code srgb}, {@code b-w} or {@code lab}} */
    public String interpretation() {
        return image.interpretation();
    }

    /** {@return the loader that read the image, such as {@code jpegload_buffer}; empty when it was not loaded} */
    public String loader() {
        return image.field("vips-loader").orElse("");
    }

    /** {@return the names of every header field, such as {@code orientation} or {@code exif-ifd0-Make}} */
    public List<String> fields() {
        return image.fields();
    }

    /**
     * {@return a header field of any type as text; empty when the image has no such field}
     *
     * @param name the field name
     */
    public Optional<String> field(String name) {
        Objects.requireNonNull(name, "name");
        return image.field(name);
    }

    /**
     * {@return an {@code int} header field; empty when the image has no such field or it holds another type}
     *
     * @param name the field name
     */
    public OptionalInt intField(String name) {
        Objects.requireNonNull(name, "name");
        return image.intField(name);
    }

    /**
     * {@return a {@code double} or {@code int} header field as a {@code double}; empty when the image has no such field
     * or it holds another type}
     *
     * @param name the field name
     */
    public OptionalDouble doubleField(String name) {
        Objects.requireNonNull(name, "name");
        return image.doubleField(name);
    }

    /**
     * {@return a copy of a binary header field, such as {@code exif-data}; empty when the image has no such field or it
     * holds another type}
     *
     * @param name the field name
     */
    public Optional<byte[]> blobField(String name) {
        Objects.requireNonNull(name, "name");
        return image.blobField(name);
    }

    /**
     * {@return the parsed EXIF tags as text by field name (such as {@code exif-ifd0-Orientation} &rarr;
     * {@code "6 (Right-top, Short, 1 components, 2 bytes)"}), sorted by name; the raw block {@code exif-data} is left
     * out, see {@link #blobField}}
     */
    public Map<String, String> exif() {
        Map<String, String> exif = new TreeMap<>();
        for (String name : image.fields()) {
            if (name.startsWith("exif-") && !name.equals("exif-data")) {
                image.field(name).ifPresent(value -> exif.put(name, value));
            }
        }
        return exif;
    }

    /** {@return a copy of the embedded ICC profile ({@code icc-profile-data}); empty when there is none} */
    public Optional<byte[]> iccProfile() {
        return image.blobField("icc-profile-data");
    }

    /**
     * Turns the image upright by its EXIF orientation ({@code autorot}); the result has orientation 1.
     *
     * @return the upright image, to close
     * @throws LibvipsException when the operation fails
     */
    public Image autorotate() {
        return derive("autorot", op -> {
        });
    }

    /**
     * Makes a thumbnail of this image ({@code thumbnail_image}). Prefer {@link Libvips#thumbnail(byte[], int,
     * ThumbnailOptions)} for encoded input: it can shrink while decoding, which is much faster for large JPEGs.
     *
     * @param width the target width; to size by height alone, give a height and a width as large as the height
     *        argument allows (10 000 000 on a default libvips build)
     * @param options height, size, crop, rotation, colour profile
     * @return the thumbnail, to close
     * @throws IllegalArgumentException when {@code width} is out of range
     * @throws LibvipsException when the operation fails
     */
    public Image thumbnail(int width, ThumbnailOptions options) {
        Objects.requireNonNull(options, "options");
        return derive("thumbnail_image", op -> {
            op.set("width", width);
            options.applyTo(op);
        });
    }

    /**
     * Scales the image by {@code scale} in both directions ({@code resize}).
     *
     * @param scale the factor, such as {@code 0.5} for half size
     * @return the scaled image, to close
     * @throws IllegalArgumentException when {@code scale} is out of range
     * @throws LibvipsException when the operation fails
     */
    public Image resize(double scale) {
        return derive("resize", op -> op.set("scale", scale));
    }

    /**
     * Cuts out a rectangle ({@code extract_area}).
     *
     * @param left the left edge
     * @param top the top edge
     * @param width the width
     * @param height the height
     * @return the rectangle, to close
     * @throws IllegalArgumentException when a value is out of range
     * @throws LibvipsException naming {@code extract_area} when the rectangle is not inside the image
     */
    public Image crop(int left, int top, int width, int height) {
        return derive("extract_area", op -> {
            op.set("left", left);
            op.set("top", top);
            op.set("width", width);
            op.set("height", height);
        });
    }

    /**
     * Crops to {@code width} &times; {@code height}, keeping the part {@code interesting} picks ({@code smartcrop}).
     * With {@link Interesting#ALL} nothing is cut: the whole image is returned at its own size.
     *
     * @param width the width
     * @param height the height
     * @param interesting what to keep
     * @return the crop, to close
     * @throws IllegalArgumentException when a value is out of range
     * @throws LibvipsException when the operation fails
     */
    public Image smartCrop(int width, int height, Interesting interesting) {
        Objects.requireNonNull(interesting, "interesting");
        return derive("smartcrop", op -> {
            op.set("width", width);
            op.set("height", height);
            op.set("interesting", interesting);
        });
    }

    /**
     * Imports the image into the profile connection space (Lab) through its embedded ICC profile ({@code icc_import}).
     *
     * @return the Lab image, to close
     * @throws LibvipsException when the operation fails
     */
    public Image iccImport() {
        return derive("icc_import", op -> op.set("embedded", true));
    }

    /**
     * Converts the image to {@code outputProfile}, from its embedded ICC profile ({@code icc_transform}).
     *
     * @param outputProfile a built-in profile name such as {@code srgb}, {@code p3} or {@code cmyk}, or an ICC file
     *        name; name files only from trusted configuration, libvips reads the file
     * @param intent the rendering intent
     * @return the converted image, to close
     * @throws LibvipsException when the operation fails
     */
    public Image iccTransform(String outputProfile, Intent intent) {
        Objects.requireNonNull(outputProfile, "outputProfile");
        Objects.requireNonNull(intent, "intent");
        return derive("icc_transform", op -> {
            op.set("output_profile", outputProfile);
            op.set("intent", intent);
            op.set("embedded", true);
        });
    }

    /**
     * Encodes the image with the saver {@code options} names. Pixels are computed now, so an input that loaded but
     * is damaged fails here.
     *
     * @param options the format and its settings, such as {@link SaveOptions#webp(int)}
     * @return the encoded image
     * @throws UnsupportedFormatException ({@link UnsupportedFormatException.Reason#NO_SAVER}) naming the saver when
     *         this libvips does not have it
     * @throws LibvipsException naming the saver when encoding fails, including a saver that exists but is blocked or
     *         lacks its encoder plugin (such as libheif without an AV1 encoder)
     * @throws IllegalStateException when this image is closed
     */
    public byte[] save(SaveOptions options) {
        Objects.requireNonNull(options, "options");
        String saver = options.operation();
        Map<String, Object> arguments = switch (options) {
            case SaveOptions.Jpeg o -> o.arguments();
            case SaveOptions.Png o -> o.arguments();
            case SaveOptions.Webp o -> o.arguments();
            case SaveOptions.Avif o -> o.arguments();
        };
        VipsRuntime runtime = libvips.runtime();
        runtime.enter();
        try {
            if (!runtime.hasOperation(saver)) {
                throw new UnsupportedFormatException(saver, "", UnsupportedFormatException.Reason.NO_SAVER);
            }
            try (Operation op = Operation.create(saver)) {
                op.set("in", image);
                arguments.forEach(op::set);
                try (Operation.Built built = op.build()) {
                    return built.blob("buffer");
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

    private Image derive(String operation, Consumer<Operation> arguments) {
        VipsRuntime runtime = libvips.runtime();
        runtime.enter();
        try {
            return derive(runtime, operation, false, arguments);
        } finally {
            runtime.exit();
        }
    }

    /**
     * Runs {@code operation} on this image and returns its {@code out}: the one path of {@link #call} and every
     * transform. This image goes into {@code in} (or {@code input}) unless {@code inputGiven}.
     */
    private Image derive(VipsRuntime runtime, String operation, boolean inputGiven, Consumer<Operation> arguments) {
        try (Operation op = Operation.create(operation)) {
            if (!op.hasImageOutput("out")) { // also refuses the draw_* operations, which modify an input
                throw new IllegalArgumentException(operation + " has no image output out");
            }
            if (!inputGiven) {
                String input = op.firstInput("in", "input").orElseThrow(
                        () -> new IllegalArgumentException(operation + " has no image input; pass it explicitly"));
                op.set(input, image);
            }
            arguments.accept(op);
            try (Operation.Built built = op.build()) {
                return new Image(built.image("out", runtime), libvips);
            }
        }
    }
}
