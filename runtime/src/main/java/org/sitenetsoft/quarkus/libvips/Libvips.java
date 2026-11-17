package org.sitenetsoft.quarkus.libvips;

import java.util.Optional;

import jakarta.inject.Singleton;

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
