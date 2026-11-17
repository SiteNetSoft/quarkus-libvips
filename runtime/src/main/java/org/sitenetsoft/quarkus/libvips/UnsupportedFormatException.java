package org.sitenetsoft.quarkus.libvips;

/**
 * No libvips loader accepts the input, or no saver produces the requested format.
 * <p>
 * A format whose loader is blocked ({@code quarkus.libvips.security.*}) is reported as {@link Reason#NO_LOADER}:
 * libvips leaves blocked loaders out of format detection.
 */
public class UnsupportedFormatException extends LibvipsException {

    private static final long serialVersionUID = 1L;

    /** Why the format is unsupported. */
    public enum Reason {
        /** No loader accepts the input (unknown, empty, or blocked format). */
        NO_LOADER,
        /** This libvips has no saver for the format. */
        NO_SAVER
    }

    /** Added to every {@link Reason#NO_LOADER} message: a blocked format cannot be told from an unknown one. */
    static final String BLOCKED_HINT = "(blocked loaders are not considered; see quarkus.libvips.security.*)";

    private final Reason reason;

    /**
     * Creates an exception with a fixed message: {@code <operation>: no loader accepts this input} and
     * {@link #BLOCKED_HINT}, or {@code <operation>: no saver for this format}.
     *
     * @param operation the operation or C function that found no loader or saver
     * @param detail libvips's error text, or why the input was refused; may be empty; never part of the message
     * @param reason why
     */
    public UnsupportedFormatException(String operation, String detail, Reason reason) {
        super(operation, detail, message(operation, reason));
        this.reason = reason;
    }

    private static String message(String operation, Reason reason) {
        return reason == Reason.NO_LOADER ? operation + ": no loader accepts this input " + BLOCKED_HINT
                : operation + ": no saver for this format";
    }

    /** {@return why the format is unsupported} */
    public Reason reason() {
        return reason;
    }
}
