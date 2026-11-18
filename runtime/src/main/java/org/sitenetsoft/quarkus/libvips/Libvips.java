package org.sitenetsoft.quarkus.libvips;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Singleton;

import org.sitenetsoft.quarkus.libvips.internal.Blocking;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Operation;
import org.sitenetsoft.quarkus.libvips.internal.VipsRuntime;

/**
 * libvips in this application. With {@code quarkus.libvips.required=false} the application may run without it:
 * {@link #isAvailable()} tells, and every image call then throws {@link LibvipsUnavailableException}.
 * <p>
 * libvips works on the calling thread. The methods that load, sniff or thumbnail an image throw
 * {@link io.quarkus.runtime.BlockingOperationNotAllowedException} on an I/O thread (a Vert.x event loop); call them
 * from a worker thread ({@code @Blocking}, {@code executeBlocking}, or Mutiny's
 * {@code emitOn(Infrastructure.getDefaultWorkerPool())}). The state and introspection methods ({@link #status()},
 * {@link #version()}, {@link #hasOperation}, {@link #isBlocked}, {@link #saverFor}, {@link #saveSuffixes()} and the
 * counters) run anywhere.
 */
@Singleton
public class Libvips {

    private static final String NOT_STARTED = "libvips is not started";

    /** One pass, top to bottom, with the configured fail-on: how a thumbnail reads its source. */
    private static final LoadOptions HEADER_PROBE = new LoadOptions(Access.SEQUENTIAL, Optional.empty(), 0, 1);

    /**
     * The longest file name, in UTF-8 bytes, that is sniffed as given: libvips copies a name into a 4096-byte buffer
     * ({@code VIPS_PATH_MAX}) when it splits off {@code [options]}, so the name, the {@code []} appended for sniffing
     * and the terminating NUL must fit.
     */
    static final int MAX_FILE_NAME_BYTES = 4093;

    private volatile VipsRuntime runtime;

    /**
     * The state of libvips, for health checks and diagnostics.
     *
     * @param available whether libvips can be used
     * @param error why it cannot, when it cannot; it may name library paths, so log it but never return it to clients
     * @param version the libvips version, when available
     * @param library the library path or name, when available
     * @param concurrency worker threads per pipeline ({@code 0} when unavailable)
     * @param cacheMaxOperations operation cache entries
     * @param cacheMaxMemory operation cache memory, bytes
     * @param cacheMaxFiles open files the cache may hold
     * @param blockUntrusted whether operations libvips marks untrusted are blocked
     * @param heifLoadBlocked whether reading HEIF/AVIF is blocked
     * @param openImages images open now
     * @param trackedMemory bytes libvips has allocated through its tracked allocator
     */
    public record Status(boolean available, Optional<String> error, Optional<Version> version,
            Optional<String> library, int concurrency, int cacheMaxOperations, long cacheMaxMemory, int cacheMaxFiles,
            boolean blockUntrusted, boolean heifLoadBlocked, long openImages, long trackedMemory) {

        static Status unavailable(String error) {
            return new Status(false, Optional.of(error), Optional.empty(), Optional.empty(), 0, 0, 0, 0, false, false,
                    0, 0);
        }
    }

    /** Created by the container. */
    public Libvips() {
    }

    /** {@return whether libvips can be used} */
    public boolean isAvailable() {
        VipsRuntime r = runtime;
        return r != null && r.available() && !r.stopped();
    }

    /**
     * {@return the libvips version}
     *
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Version version() {
        return started().version();
    }

    /**
     * {@return the path or name libvips was opened by}
     *
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public String library() {
        return started().library();
    }

    /** {@return the state of libvips; never throws} */
    public Status status() {
        VipsRuntime r = runtime;
        if (r == null) {
            return Status.unavailable(NOT_STARTED);
        }
        if (!r.available()) {
            return Status.unavailable(r.failure().orElse(NOT_STARTED));
        }
        try {
            VipsRuntime.Effective e = r.effective();
            return new Status(true, Optional.empty(), Optional.of(r.version()), Optional.of(r.library()),
                    e.concurrency(), e.cacheMaxOperations(), e.cacheMaxMemory(), e.cacheMaxFiles(),
                    r.settings().blockUntrusted(), r.heifLoadBlocked(), r.openImages(), r.trackedMemory());
        } catch (LibvipsUnavailableException e) {
            return Status.unavailable(e.detail());
        }
    }

    /**
     * Loads an image from memory with {@link LoadOptions#DEFAULT}. Only the header is read now; pixels are decoded
     * when an operation needs them, so a damaged file may fail later, in that operation.
     *
     * @param data the encoded image; copied, the array is not kept
     * @return the image, to close
     * @throws LimitExceededException when the input is larger than {@code quarkus.libvips.limits.max-input-size}, or
     *         its header declares more pixels than {@code quarkus.libvips.limits.max-input-pixels}
     * @throws UnsupportedFormatException when no loader accepts the data (empty, unknown or blocked format)
     * @throws LibvipsException when the loader fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image load(byte[] data) {
        return load(data, LoadOptions.DEFAULT);
    }

    /**
     * Loads an image from memory.
     *
     * @param data the encoded image; copied, the array is not kept
     * @param options access, fail-on and pages
     * @return the image, to close
     * @throws LimitExceededException when the input is larger than {@code quarkus.libvips.limits.max-input-size}, or
     *         its header declares more pixels than {@code quarkus.libvips.limits.max-input-pixels}
     * @throws UnsupportedFormatException when no loader accepts the data (empty, unknown or blocked format)
     * @throws IllegalArgumentException when pages are asked of a loader without pages
     * @throws LibvipsException when the loader fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image load(byte[] data, LoadOptions options) {
        Blocking.check("Libvips.load");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(options, "options");
        if (data.length == 0) {
            throw new UnsupportedFormatException("load", "the input is empty",
                    UnsupportedFormatException.Reason.NO_LOADER);
        }
        VipsRuntime r = runtime();
        checkSize(r, data.length);
        r.enter();
        try {
            String loader = r.findLoadBuffer(data).orElseThrow(() -> new UnsupportedFormatException(
                    "vips_foreign_find_load_buffer", ErrorBuffer.take(), UnsupportedFormatException.Reason.NO_LOADER));
            return load(r, loader, "buffer", data, options);
        } finally {
            r.exit();
        }
    }

    /**
     * Loads an image from a file with {@link LoadOptions#DEFAULT}.
     *
     * @param file the file
     * @return the image, to close
     * @throws LimitExceededException when the file is larger than {@code quarkus.libvips.limits.max-input-size}, or
     *         its header declares more pixels than {@code quarkus.libvips.limits.max-input-pixels}
     * @throws UnsupportedFormatException when no loader accepts the file (missing, unknown or blocked format)
     * @throws IllegalArgumentException when the file name ends in white space or is longer than 4093 bytes, or the file
     *         exists but is not a regular file (a FIFO, device or directory)
     * @throws LibvipsException when the loader fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image load(Path file) {
        return load(file, LoadOptions.DEFAULT);
    }

    /**
     * Loads an image from a file.
     *
     * @param file the file
     * @param options access, fail-on and pages
     * @return the image, to close
     * @throws LimitExceededException when the file is larger than {@code quarkus.libvips.limits.max-input-size}, or
     *         its header declares more pixels than {@code quarkus.libvips.limits.max-input-pixels}
     * @throws UnsupportedFormatException when no loader accepts the file (missing, unknown or blocked format)
     * @throws IllegalArgumentException when pages are asked of a loader without pages, or the file name ends in white
     *         space or is longer than 4093 bytes, or the file exists but is not a regular file (a FIFO, device or
     *         directory)
     * @throws LibvipsException when the loader fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image load(Path file, LoadOptions options) {
        Blocking.check("Libvips.load");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        String filename = filename(file);
        VipsRuntime r = runtime();
        checkSize(r, file);
        r.enter();
        try {
            String loader = r.findLoad(sniffName(filename)).orElseThrow(() -> new UnsupportedFormatException(
                    "vips_foreign_find_load", ErrorBuffer.take().replace(sniffName(filename), filename),
                    UnsupportedFormatException.Reason.NO_LOADER));
            return load(r, loader, "filename", filename, options);
        } finally {
            r.exit();
        }
    }

    /**
     * {@return the class name of the loader libvips would pick for {@code data}, such as
     * {@code VipsForeignLoadJpegBuffer}; empty when none}
     *
     * @param data the encoded image; only its first 64 KiB are copied for sniffing
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Optional<String> loaderFor(byte[] data) {
        Blocking.check("Libvips.loaderFor");
        Objects.requireNonNull(data, "data");
        VipsRuntime r = runtime();
        if (data.length == 0) {
            return Optional.empty();
        }
        r.enter();
        try {
            Optional<String> loader = r.findLoadBuffer(data);
            if (loader.isEmpty()) {
                ErrorBuffer.take(); // "not in a known format": the empty result says it
            }
            return loader;
        } finally {
            r.exit();
        }
    }

    /**
     * {@return the class name of the loader libvips would pick for {@code file}, such as
     * {@code VipsForeignLoadJpegFile}; empty when none}
     *
     * @param file the file
     * @throws IllegalArgumentException when the file name ends in white space or is longer than 4093 bytes, or the file
     *         exists but is not a regular file (a FIFO, device or directory)
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Optional<String> loaderFor(Path file) {
        Blocking.check("Libvips.loaderFor");
        String filename = filename(file);
        VipsRuntime r = runtime();
        r.enter();
        try {
            Optional<String> loader = r.findLoad(sniffName(filename));
            if (loader.isEmpty()) {
                ErrorBuffer.take();
            }
            return loader;
        } finally {
            r.exit();
        }
    }

    /**
     * {@return the class name of the buffer saver libvips registers for a file suffix, such as
     * {@code VipsForeignSaveWebpBuffer} for {@code .webp}; empty when none} This is a suffix lookup only: {@code .avif}
     * finds nothing, because libvips's HEIF saver declares {@code .heic} and {@code .heif}; {@link Image#save} does not
     * use it, each {@link SaveOptions} names its own saver. libvips splits a trailing {@code [options]} off the suffix
     * here, but only a class name comes back, so no option reaches any saver.
     *
     * @param suffix a file suffix such as {@code .webp}, or a file name ending in one
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Optional<String> saverFor(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        VipsRuntime r = runtime();
        r.enter();
        try {
            Optional<String> saver = r.findSaveBuffer(suffix);
            if (saver.isEmpty()) {
                ErrorBuffer.take(); // "... is not a known buffer format": the empty result says it
            }
            return saver;
        } finally {
            r.exit();
        }
    }

    /**
     * {@return every file suffix some libvips saver accepts, such as {@code .jpg} or {@code .webp}}
     *
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Set<String> saveSuffixes() {
        VipsRuntime r = runtime();
        r.enter();
        try {
            return Set.copyOf(r.saveSuffixes());
        } finally {
            r.exit();
        }
    }

    /**
     * {@return whether this libvips has the operation, such as {@code heifsave_buffer}} An optional format is present
     * only when libvips was built with its library.
     *
     * @param nickname the operation's nickname (or concrete class name)
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public boolean hasOperation(String nickname) {
        Objects.requireNonNull(nickname, "nickname");
        VipsRuntime r = runtime();
        r.enter();
        try {
            return r.hasOperation(nickname);
        } finally {
            r.exit();
        }
    }

    /**
     * {@return whether libvips blocks the operation ({@code quarkus.libvips.security.*}); false when libvips has no
     * such operation}
     *
     * @param operation a nickname such as {@code ppmload}, or a concrete class name such as
     *        {@code VipsForeignLoadPpmFile}
     * @throws IllegalArgumentException when {@code operation} names an abstract class such as
     *         {@code VipsForeignLoadPpm} (block lists accept those; ask about a concrete variant here)
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public boolean isBlocked(String operation) {
        Objects.requireNonNull(operation, "operation");
        return runtime().isBlocked(operation);
    }

    /**
     * Makes a thumbnail from an encoded image in memory ({@code thumbnail_buffer}). libvips shrinks while decoding
     * where the format allows (JPEG, WebP, HEIF, PDF, SVG), so this is the fast path for uploads. The EXIF orientation
     * is applied unless {@link ThumbnailOptions#noRotate()}; {@code quarkus.libvips.fail-on} applies.
     *
     * @param data the encoded image; copied, the array is not kept
     * @param width the target width; to size by height alone, give a height and a width as large as the height
     *        argument allows (10 000 000 on a default libvips build)
     * @param options height, size, crop, rotation, colour profile
     * @return the thumbnail, to close
     * @throws LimitExceededException when the input is larger than {@code quarkus.libvips.limits.max-input-size}, or
     *         its header declares more pixels than {@code quarkus.libvips.limits.max-input-pixels}
     * @throws UnsupportedFormatException when no loader accepts the data (empty, unknown or blocked format)
     * @throws IllegalArgumentException when {@code width} or the height is out of range, or the output profile is not
     *         accepted (see {@link ThumbnailOptions#outputProfile()})
     * @throws LibvipsException when the operation fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image thumbnail(byte[] data, int width, ThumbnailOptions options) {
        Blocking.check("Libvips.thumbnail");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(options, "options");
        if (data.length == 0) {
            throw new UnsupportedFormatException("thumbnail", "the input is empty",
                    UnsupportedFormatException.Reason.NO_LOADER);
        }
        VipsRuntime r = runtime();
        checkSize(r, data.length);
        r.enter();
        try {
            String loader = r.findLoadBuffer(data).orElseThrow(() -> new UnsupportedFormatException(
                    "vips_foreign_find_load_buffer", ErrorBuffer.take(), UnsupportedFormatException.Reason.NO_LOADER));
            if (r.settings().maxInputPixels() > 0) {
                // thumbnail_buffer may shrink while decoding, so its output says nothing about the input: read the
                // header once on its own and check the full size before any pixel is decoded
                load(r, loader, "buffer", data, HEADER_PROBE).close();
            }
            try (Operation op = Operation.create("thumbnail_buffer")) {
                op.set("buffer", data);
                op.set("width", width);
                options.applyTo(op);
                op.set("fail_on", r.settings().failOn());
                try (Operation.Built built = op.build()) {
                    return new Image(built.image("out", r), this);
                }
            }
        } finally {
            r.exit();
        }
    }

    /**
     * Makes a thumbnail from a file. The file is opened by its exact name through the loader its content selects, read
     * once top to bottom, and reduced with {@code thumbnail_image}. libvips's own file thumbnail operation is not used:
     * it reads loader options from a {@code [...]} at the end of the name. That also means no shrink while decoding;
     * for large JPEGs read the file into memory and use {@link #thumbnail(byte[], int, ThumbnailOptions)}.
     *
     * @param file the file
     * @param width the target width; to size by height alone, give a height and a width as large as the height
     *        argument allows (10 000 000 on a default libvips build)
     * @param options height, size, crop, rotation, colour profile
     * @return the thumbnail, to close
     * @throws LimitExceededException when the file is larger than {@code quarkus.libvips.limits.max-input-size}, or
     *         its header declares more pixels than {@code quarkus.libvips.limits.max-input-pixels}
     * @throws UnsupportedFormatException when no loader accepts the file (missing, unknown or blocked format)
     * @throws IllegalArgumentException when {@code width} or the height is out of range, the output profile is not
     *         accepted (see {@link ThumbnailOptions#outputProfile()}), the file name ends in white space or is
     *         longer than 4093 bytes, or the file exists but is not a regular file (a FIFO, device or directory)
     * @throws LibvipsException when the loader or the operation fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image thumbnail(Path file, int width, ThumbnailOptions options) {
        Blocking.check("Libvips.thumbnail");
        Objects.requireNonNull(options, "options");
        try (Image source = load(file, HEADER_PROBE)) {
            return source.thumbnail(width, options);
        }
    }

    /** {@return the images open now} */
    public long openImages() {
        VipsRuntime r = runtime;
        return r == null ? 0 : r.openImages();
    }

    /** {@return the images released by the cleaner because they were never closed} */
    public long leakedImages() {
        VipsRuntime r = runtime;
        return r == null ? 0 : r.leakedImages();
    }

    /**
     * {@return the bytes libvips has allocated through its tracked allocator ({@code vips_tracked_get_mem})}
     *
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public long trackedMemory() {
        return runtime().trackedMemory();
    }

    /**
     * The name to sniff a file by. {@code vips_foreign_find_load} reads a trailing {@code [...]} as loader options and
     * looks at the file without it, so {@code upload.jpg[shrink=2]} would be sniffed as {@code upload.jpg}. An empty
     * {@code []} appended is what it strips instead, so it sniffs exactly the file the loader opens. The loader gets
     * the name unchanged through its {@code filename} property, which takes no options. libvips's error text names
     * the sniffing name; it is put back to the real name before it reaches {@link LibvipsException#detail()}.
     */
    private static String sniffName(String filename) {
        return filename + "[]";
    }

    /**
     * The file name, refused defensively when it ends in white space. libvips itself keeps such a name intact (the
     * sniff of {@code "upload.jpg []"} reads {@code "upload.jpg "}), but the name goes through libvips's file-name and
     * option splitting for sniffing, whose tokenizer skips white space; a name that ends in it is kept out so that
     * splitting is never ambiguous.
     */
    private static String filename(Path file) {
        Objects.requireNonNull(file, "file");
        // absolute, because the loader's file-name argument takes only absolute paths (see Operation)
        String filename = file.toAbsolutePath().toString();
        if (!filename.isEmpty() && isVipsSpace(filename.charAt(filename.length() - 1))) {
            throw new IllegalArgumentException("file names ending in white space are not supported: " + file);
        }
        // a FIFO would block the sniffing open until a writer appears; a device or directory is never an image
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a regular file: " + file);
        }
        int bytes = filename.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_NAME_BYTES) {
            throw new IllegalArgumentException("file names longer than " + MAX_FILE_NAME_BYTES
                    + " bytes are not supported (libvips cuts them at 4096 bytes); this one has " + bytes);
        }
        return filename;
    }

    /** The characters libvips's tokenizer skips as white space ({@code strspn(p, " \t\n\r")}). */
    private static boolean isVipsSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private Image load(VipsRuntime r, String loader, String source, Object input, LoadOptions options) {
        try (Operation op = Operation.create(loader)) {
            op.set(source, input);
            op.set("access", options.access());
            op.set("fail_on", options.failOn().orElse(r.settings().failOn()));
            if (options.selectsPages()) {
                if (!op.has("page") || !op.has("n")) {
                    throw new IllegalArgumentException(loader + " has no pages");
                }
                op.set("page", options.page());
                op.set("n", options.pages());
            }
            Image image;
            try (Operation.Built built = op.build()) {
                image = new Image(built.image("out", r), this);
            }
            long limit = r.settings().maxInputPixels();
            long pixels = (long) image.width() * image.height();
            if (limit > 0 && pixels > limit) {
                image.close();
                throw new LimitExceededException(LimitExceededException.MAX_INPUT_PIXELS, pixels, limit);
            }
            return image;
        }
    }

    /** Refuses an input array larger than {@code quarkus.libvips.limits.max-input-size}. */
    private static void checkSize(VipsRuntime r, long size) {
        long limit = r.settings().maxInputSize();
        if (limit > 0 && size > limit) {
            throw new LimitExceededException(LimitExceededException.MAX_INPUT_SIZE, size, limit);
        }
    }

    /**
     * Refuses a file larger than {@code quarkus.libvips.limits.max-input-size}. A file that cannot be measured
     * (missing, unreadable) is left to the loader lookup, which reports it. This is check-then-use: a file that grows
     * after it was measured is still read; the pixel limit, checked on the header libvips reads, still applies.
     */
    private static void checkSize(VipsRuntime r, Path file) {
        if (r.settings().maxInputSize() <= 0) {
            return;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return;
        }
        checkSize(r, size);
    }

    /** Attaches the started (or unavailable) runtime; called once per application start by the recorder. */
    void attach(VipsRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * {@return the runtime, for native use}
     *
     * @throws LibvipsUnavailableException when libvips is unavailable or stopped
     */
    VipsRuntime runtime() {
        VipsRuntime r = runtime;
        if (r == null) {
            throw new LibvipsUnavailableException(NOT_STARTED);
        }
        r.checkUsable();
        return r;
    }

    private VipsRuntime started() {
        VipsRuntime r = runtime;
        if (r == null) {
            throw new LibvipsUnavailableException(NOT_STARTED);
        }
        return r;
    }
}
