package org.sitenetsoft.quarkus.libvips;

/**
 * libvips cannot be used: it could not be loaded or started (see {@code quarkus.libvips.required}), or the application
 * is shutting down.
 */
public class LibvipsUnavailableException extends LibvipsException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message why libvips is unavailable
     */
    public LibvipsUnavailableException(String message) {
        super("libvips", "", message);
    }
}
