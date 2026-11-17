package org.sitenetsoft.quarkus.libvips;

/**
 * An input is larger than {@code quarkus.libvips.limits.*} allows. Thrown before libvips decodes any pixel: the size
 * limit before the input reaches libvips, the pixel limit right after the header is read.
 */
public class LimitExceededException extends LibvipsException {

    private static final long serialVersionUID = 1L;

    /** {@link #operation()} of a refusal by {@code quarkus.libvips.limits.max-input-size}. */
    public static final String MAX_INPUT_SIZE = "limits.max-input-size";

    /** {@link #operation()} of a refusal by {@code quarkus.libvips.limits.max-input-pixels}. */
    public static final String MAX_INPUT_PIXELS = "limits.max-input-pixels";

    private final long actual;
    private final long limit;

    /**
     * @param limitName {@link #MAX_INPUT_SIZE} or {@link #MAX_INPUT_PIXELS}
     * @param actual the input's size in bytes, or its pixels
     * @param limit the configured limit
     */
    public LimitExceededException(String limitName, long actual, long limit) {
        super(limitName, "", message(limitName, actual, limit));
        this.actual = actual;
        this.limit = limit;
    }

    private static String message(String limitName, long actual, long limit) {
        String unit = MAX_INPUT_SIZE.equals(limitName) ? "bytes" : "pixels";
        return "the input has " + actual + " " + unit + ", more than quarkus.libvips." + limitName + " = " + limit;
    }

    /** {@return the input's size in bytes, or its pixels (width &times; height, all loaded pages)} */
    public long actual() {
        return actual;
    }

    /** {@return the configured limit} */
    public long limit() {
        return limit;
    }
}
