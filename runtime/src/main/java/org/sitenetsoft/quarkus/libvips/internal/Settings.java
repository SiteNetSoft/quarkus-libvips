package org.sitenetsoft.quarkus.libvips.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.sitenetsoft.quarkus.libvips.FailOn;

/**
 * The settings a {@link VipsRuntime} starts with (the run-time configuration, in plain values).
 *
 * @param library the configured library path or name; empty to search
 * @param concurrency worker threads per pipeline; empty for libvips's default
 * @param cacheMaxOperations {@code vips_cache_set_max}
 * @param cacheMaxMemory {@code vips_cache_set_max_mem}, bytes
 * @param cacheMaxFiles {@code vips_cache_set_max_files}
 * @param maxInputPixels pixel limit of a load, {@code 0} for none
 * @param maxInputSize byte limit of a load, {@code 0} for none
 * @param failOn default {@code fail_on} of a load
 * @param blockUntrusted {@code vips_block_untrusted_set}
 * @param blockedOperations operations to block (nicknames or class names)
 * @param allowedOperations operations to unblock afterwards (nicknames or class names)
 */
public record Settings(Optional<String> library, OptionalInt concurrency, int cacheMaxOperations, long cacheMaxMemory,
        int cacheMaxFiles, long maxInputPixels, long maxInputSize, FailOn failOn, boolean blockUntrusted,
        List<String> blockedOperations, List<String> allowedOperations) {

    /** Copies the lists and checks for {@code null}. */
    public Settings {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(concurrency, "concurrency");
        Objects.requireNonNull(failOn, "failOn");
        blockedOperations = List.copyOf(blockedOperations);
        allowedOperations = List.copyOf(allowedOperations);
    }

    /** {@return the defaults of the run-time configuration} */
    public static Settings defaults() {
        return new Settings(Optional.empty(), OptionalInt.empty(), 0, 52428800, 20, 268402689, 134217728,
                FailOn.NONE, true, List.of(), List.of());
    }

    /**
     * {@return a copy with another library}
     *
     * @param library the library path or name
     */
    public Settings withLibrary(String library) {
        return new Settings(Optional.of(library), concurrency, cacheMaxOperations, cacheMaxMemory, cacheMaxFiles,
                maxInputPixels, maxInputSize, failOn, blockUntrusted, blockedOperations, allowedOperations);
    }

    /**
     * {@return a copy with another concurrency}
     *
     * @param concurrency worker threads per pipeline
     */
    public Settings withConcurrency(int concurrency) {
        return new Settings(library, OptionalInt.of(concurrency), cacheMaxOperations, cacheMaxMemory, cacheMaxFiles,
                maxInputPixels, maxInputSize, failOn, blockUntrusted, blockedOperations, allowedOperations);
    }

    /**
     * {@return a copy with another operation cache size}
     *
     * @param cacheMaxOperations {@code vips_cache_set_max}; {@code 0} turns the cache off
     */
    public Settings withCacheMaxOperations(int cacheMaxOperations) {
        return new Settings(library, concurrency, cacheMaxOperations, cacheMaxMemory, cacheMaxFiles, maxInputPixels,
                maxInputSize, failOn, blockUntrusted, blockedOperations, allowedOperations);
    }

    /**
     * {@return a copy with other load limits}
     *
     * @param maxInputPixels pixel limit, {@code 0} for none
     * @param maxInputSize byte limit, {@code 0} for none
     */
    public Settings withLimits(long maxInputPixels, long maxInputSize) {
        return new Settings(library, concurrency, cacheMaxOperations, cacheMaxMemory, cacheMaxFiles, maxInputPixels,
                maxInputSize, failOn, blockUntrusted, blockedOperations, allowedOperations);
    }

    /**
     * {@return a copy with other security settings}
     *
     * @param blockUntrusted block what libvips marks untrusted
     * @param blocked operations to block
     * @param allowed operations to unblock afterwards
     */
    public Settings withSecurity(boolean blockUntrusted, List<String> blocked, List<String> allowed) {
        return new Settings(library, concurrency, cacheMaxOperations, cacheMaxMemory, cacheMaxFiles, maxInputPixels,
                maxInputSize, failOn, blockUntrusted, blocked, allowed);
    }
}
