package org.sitenetsoft.quarkus.libvips;

/**
 * An input is larger than {@code quarkus.libvips.limits.*} allows, or an output is larger than a Java byte array can
 * hold. An input limit is applied before libvips decodes any pixel: the size limit before the input reaches libvips,
 * the pixel limit right after the header is read. The output size ({@link #OUTPUT_SIZE}) is known only once libvips
 * has encoded the image, so it is refused then, before any of it is copied to Java.
 */
public class LimitExceededException extends LibvipsException {

    private static final long serialVersionUID = 1L;

    /** {@link #operation()} of a refusal by {@code quarkus.libvips.limits.max-input-size}. */
    public static final String MAX_INPUT_SIZE = "limits.max-input-size";

    /** {@link #operation()} of a refusal by {@code quarkus.libvips.limits.max-input-pixels}. */
    public static final String MAX_INPUT_PIXELS = "limits.max-input-pixels";

    /** {@link #operation()} of an output larger than a Java byte array can hold. */
    public static final String OUTPUT_SIZE = "output-size";

    private final long actual;
    private final long limit;

    /**
     * @param limitName {@link #MAX_INPUT_SIZE}, {@link #MAX_INPUT_PIXELS} or {@link #OUTPUT_SIZE}
     * @param actual the input's size in bytes or its pixels, or the output's size in bytes
     * @param limit the configured limit, or the largest Java byte array for {@link #OUTPUT_SIZE}
     */
    public LimitExceededException(String limitName, long actual, long limit) {
        super(limitName, "", message(limitName, actual, limit));
        this.actual = actual;
        this.limit = limit;
    }

    private static String message(String limitName, long actual, long limit) {
        if (OUTPUT_SIZE.equals(limitName)) {
            return "the output has " + Long.toUnsignedString(actual) + " bytes, more than the " + limit
                    + " a Java byte array can hold";
        }
        String unit = MAX_INPUT_SIZE.equals(limitName) ? "bytes" : "pixels";
        return "the input has " + actual + " " + unit + ", more than quarkus.libvips." + limitName + " = " + limit;
    }

    /**
     * {@return the input's size in bytes, or its pixels (width &times; height, all loaded pages); the output's size in
     * bytes for {@link #OUTPUT_SIZE} (negative for a {@code size_t} above {@code Long.MAX_VALUE})}
     */
    public long actual() {
        return actual;
    }

    /** {@return the configured limit; the largest Java byte array for {@link #OUTPUT_SIZE}} */
    public long limit() {
        return limit;
    }
}
