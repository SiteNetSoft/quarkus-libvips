package org.sitenetsoft.quarkus.libvips;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How {@link Image#save} encodes an image. Each record names the libvips saver it runs ({@link #operation()}); savers
 * are never looked up by suffix, because AVIF has no buffer saver of its own suffix (it is {@code heifsave_buffer}
 * with AV1 compression).
 * <p>
 * The factories {@code jpeg}, {@code png}, {@code webp} and {@code avif} keep only the ICC profile and strip the rest
 * of the metadata. That drops the EXIF block and with it the orientation: an image that was not turned upright saves
 * sideways as JPEG, PNG or WebP. GIF has no orientation at all and always saves the pixels as they are. So call
 * {@link Image#autorotate()} (or make a thumbnail, which turns it upright) before saving. AVIF records the rotation in
 * the container ({@code irot}) and keeps it.
 */
public sealed interface SaveOptions permits SaveOptions.Jpeg, SaveOptions.Png, SaveOptions.Webp, SaveOptions.Avif,
        SaveOptions.Gif {

    /** {@return the saver operation's nickname, such as {@code webpsave_buffer}} */
    String operation();

    /** {@return the usual file suffix of the format, such as {@code .webp}; metadata only} */
    String suffix();

    /**
     * {@return JPEG options: not interlaced, ICC profile kept; autorotate first, the EXIF orientation is dropped}
     *
     * @param quality 1 to 100
     */
    static Jpeg jpeg(int quality) {
        return new Jpeg(quality, false, Set.of(Keep.ICC));
    }

    /**
     * {@return PNG options: compression 6, not interlaced, ICC profile kept; autorotate first, the EXIF orientation is
     * dropped}
     */
    static Png png() {
        return new Png(6, false, Set.of(Keep.ICC));
    }

    /**
     * {@return WebP options: lossy, effort 4, ICC profile kept; autorotate first, the EXIF orientation is dropped}
     *
     * @param quality 1 to 100
     */
    static Webp webp(int quality) {
        return new Webp(quality, false, 4, Set.of(Keep.ICC));
    }

    /**
     * {@return AVIF options: lossy, effort 4, ICC profile kept}
     *
     * @param quality 1 to 100
     */
    static Avif avif(int quality) {
        return new Avif(quality, false, 4, Set.of(Keep.ICC));
    }

    /**
     * {@return GIF options: effort 7, 8 bits per pixel (up to 256 colours), not interlaced, no metadata kept;
     * autorotate first, GIF has no orientation}
     */
    static Gif gif() {
        return new Gif(7, 8, false, Set.of());
    }

    /**
     * JPEG ({@code jpegsave_buffer}).
     *
     * @param quality the Q factor, 1 to 100
     * @param interlace whether to write a progressive JPEG
     * @param keep the metadata to keep
     */
    record Jpeg(int quality, boolean interlace, Set<Keep> keep) implements SaveOptions {

        /**
         * Checks the values.
         *
         * @throws IllegalArgumentException when {@code quality} is out of range
         */
        public Jpeg {
            checkRange("quality", quality, 1, 100);
            keep = Set.copyOf(Objects.requireNonNull(keep, "keep"));
        }

        @Override
        public String operation() {
            return "jpegsave_buffer";
        }

        @Override
        public String suffix() {
            return ".jpg";
        }

        /** {@return the saver's arguments by name} */
        Map<String, Object> arguments() {
            return Map.of("Q", quality, "interlace", interlace, "keep", Keep.bits(keep));
        }
    }

    /**
     * PNG ({@code pngsave_buffer}).
     *
     * @param compression the zlib compression level, 0 to 9
     * @param interlace whether to write an Adam7-interlaced PNG
     * @param keep the metadata to keep
     */
    record Png(int compression, boolean interlace, Set<Keep> keep) implements SaveOptions {

        /**
         * Checks the values.
         *
         * @throws IllegalArgumentException when {@code compression} is out of range
         */
        public Png {
            checkRange("compression", compression, 0, 9);
            keep = Set.copyOf(Objects.requireNonNull(keep, "keep"));
        }

        @Override
        public String operation() {
            return "pngsave_buffer";
        }

        @Override
        public String suffix() {
            return ".png";
        }

        /** {@return the saver's arguments by name} */
        Map<String, Object> arguments() {
            return Map.of("compression", compression, "interlace", interlace, "keep", Keep.bits(keep));
        }
    }

    /**
     * WebP ({@code webpsave_buffer}).
     *
     * @param quality the Q factor, 1 to 100
     * @param lossless whether to compress losslessly
     * @param effort CPU effort to reduce the size, 0 (fastest) to 6
     * @param keep the metadata to keep
     */
    record Webp(int quality, boolean lossless, int effort, Set<Keep> keep) implements SaveOptions {

        /**
         * Checks the values.
         *
         * @throws IllegalArgumentException when {@code quality} or {@code effort} is out of range
         */
        public Webp {
            checkRange("quality", quality, 1, 100);
            checkRange("effort", effort, 0, 6);
            keep = Set.copyOf(Objects.requireNonNull(keep, "keep"));
        }

        @Override
        public String operation() {
            return "webpsave_buffer";
        }

        @Override
        public String suffix() {
            return ".webp";
        }

        /** {@return the saver's arguments by name} */
        Map<String, Object> arguments() {
            return Map.of("Q", quality, "lossless", lossless, "effort", effort, "keep", Keep.bits(keep));
        }
    }

    /**
     * AVIF ({@code heifsave_buffer} with AV1 compression). Needs libvips built with libheif and an AV1 encoder
     * ({@link Libvips#hasOperation(String) hasOperation("heifsave_buffer")} tells the first).
     *
     * @param quality the Q factor, 1 to 100
     * @param lossless whether to compress losslessly
     * @param effort CPU effort, 0 (fastest) to 9
     * @param keep the metadata to keep
     */
    record Avif(int quality, boolean lossless, int effort, Set<Keep> keep) implements SaveOptions {

        /**
         * Checks the values.
         *
         * @throws IllegalArgumentException when {@code quality} or {@code effort} is out of range
         */
        public Avif {
            checkRange("quality", quality, 1, 100);
            checkRange("effort", effort, 0, 9);
            keep = Set.copyOf(Objects.requireNonNull(keep, "keep"));
        }

        @Override
        public String operation() {
            return "heifsave_buffer";
        }

        @Override
        public String suffix() {
            return ".avif";
        }

        /** {@return the saver's arguments by name} */
        Map<String, Object> arguments() {
            return Map.of("Q", quality, "lossless", lossless, "effort", effort, "keep", Keep.bits(keep),
                    "compression", "av1");
        }
    }

    /**
     * GIF ({@code gifsave_buffer}, libvips built with cgif). The image is quantised to a palette of at most
     * 2<sup>{@code bitdepth}</sup> colours. libvips does not mark it untrusted, so the default settings do not block
     * it.
     *
     * @param effort quantisation effort, 1 (fastest) to 10
     * @param bitdepth bits per pixel of the palette, 1 to 8
     * @param interlace whether to write an interlaced (progressive) GIF
     * @param keep the metadata to keep
     */
    record Gif(int effort, int bitdepth, boolean interlace, Set<Keep> keep) implements SaveOptions {

        /**
         * Checks the values.
         *
         * @throws IllegalArgumentException when {@code effort} or {@code bitdepth} is out of range
         */
        public Gif {
            checkRange("effort", effort, 1, 10);
            checkRange("bitdepth", bitdepth, 1, 8);
            keep = Set.copyOf(Objects.requireNonNull(keep, "keep"));
        }

        @Override
        public String operation() {
            return "gifsave_buffer";
        }

        @Override
        public String suffix() {
            return ".gif";
        }

        /** {@return the saver's arguments by name} */
        Map<String, Object> arguments() {
            return Map.of("effort", effort, "bitdepth", bitdepth, "interlace", interlace, "keep", Keep.bits(keep));
        }
    }

    private static void checkRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be " + min + " to " + max + ", got " + value);
        }
    }
}
