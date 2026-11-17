package org.sitenetsoft.quarkus.libvips.test;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;

/**
 * Shared test precondition: tests that need libvips run only when the library can be opened. Without it they are
 * skipped, not failed.
 * <p>
 * The library comes from the system property {@code quarkus.libvips.library}, else the env var
 * {@code QUARKUS_LIBVIPS_LIBRARY}, else the soname {@code libvips.so.42}.
 */
public final class LibvipsAvailable {

    private static volatile Boolean available;

    private LibvipsAvailable() {
    }

    /** {@return true when the library opens and exports {@code vips_init}} */
    public static boolean isAvailable() {
        Boolean a = available;
        if (a == null) {
            a = probe();
            available = a;
        }
        return a;
    }

    /** {@return the library path or name the tests open} */
    public static String library() {
        String v = System.getProperty("quarkus.libvips.library");
        if (v == null || v.isBlank()) {
            v = System.getenv("QUARKUS_LIBVIPS_LIBRARY");
        }
        return v == null || v.isBlank() ? "libvips.so.42" : v;
    }

    private static boolean probe() {
        try {
            return SymbolLookup.libraryLookup(library(), Arena.global()).find("vips_init").isPresent();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
