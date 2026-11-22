package org.sitenetsoft.quarkus.libvips;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Run-time configuration of the libvips extension. libvips's settings are process-wide: every application start sets
 * all of them, so the last start in a JVM wins.
 */
@ConfigMapping(prefix = "quarkus.libvips")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface LibvipsConfig {

    /**
     * Path or name of the libvips shared library. A value containing {@code /} is opened as that file; any other
     * value is passed unchanged to the dynamic linker (for example {@code libvips.so.42}). Unset: {@code libvips.so.42}
     * through the dynamic linker, then in {@code java.library.path}, {@code LD_LIBRARY_PATH} and the usual system
     * library directories.
     *
     * @return the configured library
     */
    Optional<String> library();

    /**
     * Whether the application must fail to start when libvips cannot be loaded or started. With {@code false} it
     * starts without libvips: {@code Libvips.isAvailable()} is false and every image call throws
     * {@code LibvipsUnavailableException}.
     *
     * @return true when libvips is required
     */
    @WithDefault("true")
    boolean required();

    /**
     * libvips worker threads per image pipeline. Unset: libvips's default ({@code VIPS_CONCURRENCY}, else the number
     * of processors).
     *
     * @return the concurrency
     */
    OptionalInt concurrency();

    /**
     * The libvips operation cache.
     *
     * @return the cache settings
     */
    Cache cache();

    /**
     * Limits applied to every load.
     *
     * @return the limits
     */
    Limits limits();

    /**
     * The default {@code fail_on} of every load: {@code none}, {@code truncated}, {@code error} or {@code warning}.
     *
     * @return the level
     */
    @WithDefault("none")
    FailOn failOn();

    /**
     * Which libvips operations may run.
     *
     * @return the security settings
     */
    Security security();

    /**
     * Leak checking, for development and tests: turns on libvips's own checks ({@code vips_leak_set}) and, when the
     * application stops, logs what is still alive: images never closed, and the pixel memory and files libvips still
     * tracks (WARN when anything is left, INFO otherwise); libvips lists any objects still alive on standard error.
     * Ignored, with a warning, in production (the packaged application), where the checks only cost time, unless
     * {@code quarkus.libvips.leak-check-in-production} is set. Once on,
     * libvips keeps checking until the JVM exits, even after a restart without it (only the report follows the
     * setting), so set it for a whole dev session or test run.
     *
     * @return true to check for leaks
     */
    @WithDefault("false")
    boolean leakCheck();

    /**
     * Honour {@code quarkus.libvips.leak-check} in production too, for tests that run the packaged application (a
     * {@code @QuarkusIntegrationTest} or a native image), which start in production mode. Not for deployments.
     *
     * @return true to let the leak check run in production
     */
    @WithDefault("false")
    boolean leakCheckInProduction();

    /** The libvips operation cache. */
    interface Cache {

        /**
         * Operation cache entries ({@code vips_cache_set_max}); {@code 0}, the default, turns the cache off.
         * <p>
         * Off by default because a cached operation keeps its input images alive, and an image loaded from bytes keeps
         * the whole native copy of those bytes. Every transform of an upload, and the operations libvips runs inside a
         * thumbnail, would hold the upload until evicted, and that memory is not counted by {@code max-memory}, so
         * only this entry count bounds it. The extension's own loads are never cached, but with the cache on, the
         * loader libvips runs inside a thumbnail from bytes is, and it keeps the upload. Turn the cache on for
         * applications that repeat the same work on the same images, and size it with uploads in mind.
         *
         * @return the maximum number of cached operations
         */
        @WithDefault("0")
        int maxOperations();

        /**
         * Memory the operation cache may hold ({@code vips_cache_set_max_mem}).
         *
         * @return the maximum cache memory
         */
        @WithDefault("50M")
        MemorySize maxMemory();

        /**
         * Open files the operation cache may hold ({@code vips_cache_set_max_files}).
         *
         * @return the maximum number of open files
         */
        @WithDefault("20")
        int maxFiles();
    }

    /** Limits applied to every load. */
    interface Limits {

        /**
         * Width × height (× loaded pages) above which a load is refused before any pixel is decoded; {@code 0} means
         * no limit.
         *
         * @return the pixel limit
         */
        @WithDefault("268402689")
        long maxInputPixels();

        /**
         * Size of an input byte array or file above which a load is refused; {@code 0} means no limit.
         *
         * @return the size limit
         */
        @WithDefault("128M")
        MemorySize maxInputSize();
    }

    /** Which libvips operations may run. */
    interface Security {

        /**
         * Block every operation libvips marks untrusted ({@code vips_block_untrusted_set}): loaders and savers that
         * are not hardened against hostile input.
         *
         * @return true to block them
         */
        @WithDefault("true")
        boolean blockUntrusted();

        /**
         * More operations to block, each a nickname (such as {@code pngload_buffer}, one concrete variant) or a class
         * name (such as {@code VipsForeignLoadPng}, which also covers its subclasses). An unknown name fails the
         * start.
         *
         * @return the operations to block
         */
        Optional<List<String>> blockedOperations();

        /**
         * Operations to unblock after the blocks above, in the same forms (for example
         * {@code VipsForeignLoadHeif}). An unknown name fails the start.
         *
         * @return the operations to unblock
         */
        Optional<List<String>> allowedOperations();
    }
}
