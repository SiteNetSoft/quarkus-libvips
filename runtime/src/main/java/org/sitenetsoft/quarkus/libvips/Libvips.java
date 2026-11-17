package org.sitenetsoft.quarkus.libvips;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Operation;
import org.sitenetsoft.quarkus.libvips.internal.VipsRuntime;

/**
 * libvips in this application. With {@code quarkus.libvips.required=false} the application may run without it:
 * {@link #isAvailable()} tells, and every image call then throws {@link LibvipsUnavailableException}.
 */
@Singleton
public class Libvips {

    private static final String NOT_STARTED = "libvips is not started";

    private volatile VipsRuntime runtime;

    /**
     * The state of libvips, for health checks and diagnostics.
     *
     * @param available whether libvips can be used
     * @param error why it cannot, when it cannot
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
            return Status.unavailable(e.getMessage());
        }
    }

    /**
     * Loads an image from memory with {@link LoadOptions#DEFAULT}. Only the header is read now; pixels are decoded
     * when an operation needs them, so a damaged file may fail later, in that operation.
     *
     * @param data the encoded image; copied, the array is not kept
     * @return the image, to close
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
     * @throws UnsupportedFormatException when no loader accepts the data (empty, unknown or blocked format)
     * @throws IllegalArgumentException when pages are asked of a loader without pages
     * @throws LibvipsException when the loader fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image load(byte[] data, LoadOptions options) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(options, "options");
        if (data.length == 0) {
            throw new UnsupportedFormatException("load", "the input is empty",
                    UnsupportedFormatException.Reason.NO_LOADER);
        }
        VipsRuntime r = runtime();
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
     * @throws UnsupportedFormatException when no loader accepts the file (missing, unknown or blocked format)
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
     * @throws UnsupportedFormatException when no loader accepts the file (missing, unknown or blocked format)
     * @throws IllegalArgumentException when pages are asked of a loader without pages
     * @throws LibvipsException when the loader fails
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Image load(Path file, LoadOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        String filename = file.toString();
        VipsRuntime r = runtime();
        r.enter();
        try {
            String loader = r.findLoad(sniffName(filename)).orElseThrow(() -> new UnsupportedFormatException(
                    "vips_foreign_find_load", ErrorBuffer.take(), UnsupportedFormatException.Reason.NO_LOADER));
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
     * @throws LibvipsUnavailableException when libvips is unavailable
     */
    public Optional<String> loaderFor(Path file) {
        Objects.requireNonNull(file, "file");
        VipsRuntime r = runtime();
        r.enter();
        try {
            Optional<String> loader = r.findLoad(sniffName(file.toString()));
            if (loader.isEmpty()) {
                ErrorBuffer.take();
            }
            return loader;
        } finally {
            r.exit();
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
     * the name unchanged through its {@code filename} property, which takes no options.
     */
    private static String sniffName(String filename) {
        return filename + "[]";
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
            try (Operation.Built built = op.build()) {
                return new Image(built.image("out", r), this);
            }
        }
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
