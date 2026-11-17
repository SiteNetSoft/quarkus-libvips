package org.sitenetsoft.quarkus.libvips;

/**
 * libvips cannot be used: it could not be loaded or started (see {@code quarkus.libvips.required}), or the application
 * is shutting down.
 * <p>
 * The message is always {@value #MESSAGE}. Why is in {@link #detail()}, for logs only: it can list the library paths
 * and directories that were tried. The start-up WARN and {@link Libvips#status()} carry it too.
 */
public class LibvipsUnavailableException extends LibvipsException {

    private static final long serialVersionUID = 1L;

    /** The message of every {@link LibvipsUnavailableException}. */
    public static final String MESSAGE = "libvips is unavailable";

    /**
     * @param detail why libvips is unavailable; never part of the message
     */
    public LibvipsUnavailableException(String detail) {
        super("libvips", detail, MESSAGE);
    }
}
