package org.sitenetsoft.quarkus.libvips;

import java.util.Objects;
import java.util.Optional;

/**
 * Options of {@link Libvips#load(byte[], LoadOptions)} and {@link Libvips#load(java.nio.file.Path, LoadOptions)}.
 *
 * @param access how the image will be read
 * @param failOn the error level at which the load fails; empty for {@code quarkus.libvips.fail-on}
 * @param page the first page to load, from 0
 * @param pages how many pages to load, {@code -1} for every page from {@code page}
 */
public record LoadOptions(Access access, Optional<FailOn> failOn, int page, int pages) {

    /** Random access, the configured fail-on, the first page only. */
    public static final LoadOptions DEFAULT = new LoadOptions(Access.RANDOM, Optional.empty(), 0, 1);

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException when {@code page} is negative or {@code pages} is neither {@code -1} nor at
     *         least 1
     */
    public LoadOptions {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(failOn, "failOn");
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0: " + page);
        }
        if (pages != -1 && pages < 1) {
            throw new IllegalArgumentException("pages must be -1 (every page) or at least 1: " + pages);
        }
    }

    /** {@return whether page or pages differ from a single first page} */
    boolean selectsPages() {
        return page != 0 || pages != 1;
    }
}
