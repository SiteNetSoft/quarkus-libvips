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

    /** The libvips operation cache. */
    interface Cache {

        /**
         * Operation cache entries ({@code vips_cache_set_max}).
         *
         * @return the maximum number of cached operations
         */
        @WithDefault("100")
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
