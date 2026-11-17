package org.sitenetsoft.quarkus.libvips.internal;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;
import org.sitenetsoft.quarkus.libvips.Version;

/**
 * A started libvips: the library in use, its version, the settings applied, and the accounting of native use.
 * <p>
 * Every setting is process-global in libvips, so {@link #start} sets all of them, starting from a clean block state:
 * the last start in a JVM wins and nothing survives from an earlier one (a dev-mode restart, the next test
 * application).
 * <p>
 * libvips cannot be initialised again after {@code vips_shutdown}. {@link #stop} therefore always marks this runtime
 * stopped, but calls {@code vips_shutdown} only when asked (production, where the JVM is about to exit) and only when
 * no native call is in flight and no image is open.
 */
public final class VipsRuntime {

    /** Minimum libvips version. */
    static final int MIN_MAJOR = 8;
    static final int MIN_MINOR = 15;

    /** How long {@link #stop} waits for in-flight calls before skipping {@code vips_shutdown}. */
    static final long SHUTDOWN_WAIT_MILLIS = 2000;

    /**
     * System property holding libvips's own default concurrency, read at the first start in the process before any
     * setting: {@code vips_concurrency_set(0)} does not restore it (libvips ignores values below 1).
     */
    static final String DEFAULT_CONCURRENCY_PROPERTY = "org.sitenetsoft.quarkus.libvips.default-concurrency";

    private static final String OPERATION = "VipsOperation";
    private static final Logger LOG = Logger.getLogger(VipsRuntime.class);

    /**
     * The process-global settings as libvips reports them.
     *
     * @param concurrency {@code vips_concurrency_get}
     * @param cacheMaxOperations {@code vips_cache_get_max}
     * @param cacheMaxMemory {@code vips_cache_get_max_mem}
     * @param cacheMaxFiles {@code vips_cache_get_max_files}
     */
    public record Effective(int concurrency, int cacheMaxOperations, long cacheMaxMemory, int cacheMaxFiles) {
    }

    private final Optional<String> failure;
    private final Version version;
    private final String library;
    private final Settings settings;
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong openImages = new AtomicLong();
    private final AtomicLong leakedImages = new AtomicLong();
    private volatile boolean stopped;

    private VipsRuntime(Optional<String> failure, Version version, String library, Settings settings) {
        this.failure = failure;
        this.version = version;
        this.library = library;
        this.settings = settings;
    }

    /**
     * Loads libvips, initialises it, checks the version and applies every process-global setting.
     *
     * @param settings what to apply
     * @return the started runtime
     * @throws LibvipsUnavailableException when libvips cannot be loaded or initialised, or is older than 8.15
     * @throws IllegalArgumentException when a block-list entry names no operation (nothing has been changed then)
     */
    public static synchronized VipsRuntime start(Settings settings) {
        LibraryLoader.Loaded loaded = LibraryLoader.load(settings.library());
        if (LibvipsNative.init("quarkus-libvips") != 0) {
            throw new LibvipsUnavailableException("vips_init failed: " + LibvipsNative.errorBufferCopy().strip());
        }
        Version version = new Version(LibvipsNative.version(0), LibvipsNative.version(1), LibvipsNative.version(2));
        if (!version.atLeast(MIN_MAJOR, MIN_MINOR)) {
            throw new LibvipsUnavailableException(
                    "libvips " + version + " is older than " + MIN_MAJOR + "." + MIN_MINOR);
        }
        List<String> blocked = resolve(settings.blockedOperations(), "blocked-operations");
        List<String> allowed = resolve(settings.allowedOperations(), "allowed-operations");

        // read on every start, configured or not: the first start in the process must record libvips's default
        // before anything changes it, so that a later start without quarkus.libvips.concurrency can restore it
        int libvipsDefault = defaultConcurrency();
        LibvipsNative.concurrencySet(settings.concurrency().orElse(libvipsDefault));
        LibvipsNative.cacheSetMax(settings.cacheMaxOperations());
        LibvipsNative.cacheSetMaxMem(settings.cacheMaxMemory());
        LibvipsNative.cacheSetMaxFiles(settings.cacheMaxFiles());
        LibvipsNative.operationBlockSet(OPERATION, false);
        LibvipsNative.blockUntrustedSet(settings.blockUntrusted());
        blocked.forEach(name -> LibvipsNative.operationBlockSet(name, true));
        allowed.forEach(name -> LibvipsNative.operationBlockSet(name, false));
        return new VipsRuntime(Optional.empty(), version, loaded.library(), settings);
    }

    /**
     * {@return a runtime that refuses every use}
     *
     * @param reason why libvips is unavailable
     */
    public static VipsRuntime unavailable(String reason) {
        return new VipsRuntime(Optional.of(reason), null, null, null);
    }

    /** libvips's default concurrency, read once per process before the first setting. */
    private static int defaultConcurrency() {
        String recorded = System.getProperty(DEFAULT_CONCURRENCY_PROPERTY);
        if (recorded != null) {
            try {
                return Integer.parseInt(recorded.strip());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "system property " + DEFAULT_CONCURRENCY_PROPERTY + " is not a number: " + recorded);
            }
        }
        int value = LibvipsNative.concurrencyGet();
        System.setProperty(DEFAULT_CONCURRENCY_PROPERTY, Integer.toString(value));
        return value;
    }

    /**
     * Resolves block-list entries to class names, which {@code vips_operation_block_set} needs (it silently ignores
     * anything else). {@code vips_type_find} accepts nicknames and concrete class names but skips abstract classes
     * such as {@code VipsForeignLoadHeif}; those are found with {@code g_type_from_name}.
     */
    private static List<String> resolve(List<String> names, String key) {
        long operation = LibvipsNative.operationType();
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            String n = name.strip();
            long type = n.isEmpty() ? 0 : LibvipsNative.typeFind(OPERATION, n);
            if (type == 0 && !n.isEmpty()) {
                type = LibvipsNative.typeFromName(n);
                if (type != 0 && !LibvipsNative.typeIsA(type, operation)) {
                    type = 0;
                }
            }
            if (type == 0) {
                throw new IllegalArgumentException("quarkus.libvips.security." + key + ": unknown operation " + n);
            }
            out.add(LibvipsNative.typeName(type));
        }
        return out;
    }

    /** {@return whether libvips was started} */
    public boolean available() {
        return failure.isEmpty();
    }

    /** {@return why libvips is unavailable, empty when it was started} */
    public Optional<String> failure() {
        return failure;
    }

    /**
     * {@return the libvips version}
     *
     * @throws LibvipsUnavailableException when unavailable
     */
    public Version version() {
        checkAvailable();
        return version;
    }

    /**
     * {@return the library path or name libvips was opened by}
     *
     * @throws LibvipsUnavailableException when unavailable
     */
    public String library() {
        checkAvailable();
        return library;
    }

    /**
     * {@return the settings applied}
     *
     * @throws LibvipsUnavailableException when unavailable
     */
    public Settings settings() {
        checkAvailable();
        return settings;
    }

    /** {@return the process-global settings as libvips reports them} */
    public Effective effective() {
        enter();
        try {
            return new Effective(LibvipsNative.concurrencyGet(), LibvipsNative.cacheGetMax(),
                    LibvipsNative.cacheGetMaxMem(), LibvipsNative.cacheGetMaxFiles());
        } finally {
            exit();
        }
    }

    /**
     * {@return whether the operation is blocked; false for an unknown nickname}
     *
     * @param operation an operation nickname, such as {@code pngload_buffer}
     */
    public boolean isBlocked(String operation) {
        enter();
        try {
            if (LibvipsNative.typeFind(OPERATION, operation) == 0) {
                return false;
            }
            MemorySegment op = LibvipsNative.operationNew(operation);
            if (op.equals(MemorySegment.NULL)) {
                return false;
            }
            try {
                return (LibvipsNative.operationGetFlags(op) & LibvipsNative.VIPS_OPERATION_BLOCKED) != 0;
            } finally {
                LibvipsNative.unref(op);
            }
        } finally {
            exit();
        }
    }

    /**
     * {@return whether reading HEIF/AVIF is blocked} libvips marks its HEIF loader untrusted only in some builds; false
     * when libvips has no HEIF loader.
     */
    public boolean heifLoadBlocked() {
        return isBlocked("heifload_buffer");
    }

    /** {@return the bytes libvips has allocated through its tracked allocator} */
    public long trackedMemory() {
        enter();
        try {
            return LibvipsNative.trackedGetMem();
        } finally {
            exit();
        }
    }

    /**
     * Checks that libvips may be used.
     *
     * @throws LibvipsUnavailableException when libvips is unavailable or this runtime is stopped
     */
    public void checkUsable() {
        checkAvailable();
        if (stopped) {
            throw new LibvipsUnavailableException("libvips is stopped: the application is shutting down");
        }
    }

    /**
     * Marks the start of a native use; every {@code enter} is paired with {@link #exit()}.
     *
     * @throws LibvipsUnavailableException when libvips is unavailable or this runtime is stopped
     */
    public void enter() {
        checkAvailable();
        inFlight.incrementAndGet(); // counted before the check, so stop() sees it or the check fails
        if (stopped) {
            inFlight.decrementAndGet();
            checkUsable();
        }
    }

    /** Marks the end of a native use. */
    public void exit() {
        inFlight.decrementAndGet();
    }

    /** {@return the images open now} */
    public long openImages() {
        return openImages.get();
    }

    /** {@return the images released by the cleaner because they were never closed} */
    public long leakedImages() {
        return leakedImages.get();
    }

    /** Counts an image as open. */
    void imageOpened() {
        openImages.incrementAndGet();
    }

    /**
     * Counts an image as closed.
     *
     * @param leaked whether the cleaner released it because it was never closed
     */
    void imageClosed(boolean leaked) {
        openImages.decrementAndGet();
        if (leaked) {
            leakedImages.incrementAndGet();
        }
    }

    /**
     * {@return the loader class name for a buffer, empty when none} libvips leaves error text when there is none; the
     * caller takes it.
     *
     * @param data the input; only its first 64 KiB are copied for sniffing
     */
    public Optional<String> findLoadBuffer(byte[] data) {
        enter();
        try {
            return LibvipsNative.findLoadBuffer(data);
        } finally {
            exit();
        }
    }

    /**
     * {@return the loader class name for a file, empty when none} libvips leaves error text when there is none; the
     * caller takes it.
     *
     * @param filename the file
     */
    public Optional<String> findLoad(String filename) {
        enter();
        try {
            return LibvipsNative.findLoad(filename);
        } finally {
            exit();
        }
    }

    /**
     * {@return whether libvips has the operation ({@code vips_type_find}); false for an abstract class}
     *
     * @param operation a nickname or class name
     */
    public boolean hasOperation(String operation) {
        enter();
        try {
            return LibvipsNative.typeFind(OPERATION, operation) != 0;
        } finally {
            exit();
        }
    }

    /**
     * {@return the buffer saver class name for a suffix, empty when none} libvips leaves error text when there is
     * none; the caller takes it.
     *
     * @param suffix a file suffix such as {@code .webp}
     */
    public Optional<String> findSaveBuffer(String suffix) {
        enter();
        try {
            return LibvipsNative.findSaveBuffer(suffix);
        } finally {
            exit();
        }
    }

    /** {@return every suffix some saver accepts ({@code vips_foreign_get_suffixes})} */
    public List<String> saveSuffixes() {
        enter();
        try {
            return LibvipsNative.saveSuffixes();
        } finally {
            exit();
        }
    }

    /**
     * {@return whether {@code operation} is a {@code VipsForeign} loader or saver}
     *
     * @param operation a nickname or class name
     */
    public boolean isForeign(String operation) {
        enter();
        try {
            return LibvipsNative.typeFind("VipsForeign", operation) != 0;
        } finally {
            exit();
        }
    }

    /**
     * Stops this runtime: later uses throw {@link LibvipsUnavailableException}. With {@code shutdownLibrary}, waits up
     * to 2 s for in-flight calls to end and, only if they did and no image is open, drops the operation cache and
     * calls {@code vips_shutdown}; libvips cannot be initialised again afterwards. Otherwise the operation cache is
     * emptied ({@code vips_cache_set_max(0)}, then the previous value) and libvips stays initialised for the next
     * start in this JVM. {@code vips_cache_drop_all} destroys the cache, so it runs only right before
     * {@code vips_shutdown}.
     *
     * @param shutdownLibrary whether {@code vips_shutdown} may run (production only)
     * @return whether {@code vips_shutdown} ran
     */
    public boolean stop(boolean shutdownLibrary) {
        if (!available() || stopped) {
            stopped = true;
            return false;
        }
        stopped = true;
        if (shutdownLibrary) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MILLIS);
            while (inFlight.get() > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long busy = inFlight.get();
            long open = openImages.get();
            if (busy == 0 && open == 0) {
                LibvipsNative.cacheDropAll();
                LibvipsNative.shutdown();
                return true;
            }
            LOG.debugf("libvips: vips_shutdown skipped (%d call(s) in flight, %d image(s) open)", busy, open);
        }
        emptyCache();
        return false;
    }

    /** Empties the operation cache without destroying it. */
    private static void emptyCache() {
        int max = LibvipsNative.cacheGetMax();
        LibvipsNative.cacheSetMax(0);
        LibvipsNative.cacheSetMax(max);
    }

    /** {@return whether {@link #stop} has run} */
    public boolean stopped() {
        return stopped;
    }

    private void checkAvailable() {
        if (failure.isPresent()) {
            throw new LibvipsUnavailableException(failure.get());
        }
    }
}
