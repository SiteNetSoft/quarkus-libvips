package org.sitenetsoft.quarkus.libvips;

/**
 * A libvips operation failed.
 * <p>
 * The message is fixed text: it names the {@link #operation()} and the kind of failure, never libvips's error text
 * or a file path, so it can be logged anywhere. libvips's text is in {@link #detail()}, for logs only.
 */
public class LibvipsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final String detail;

    /**
     * Creates an exception with the message {@code <operation> failed}.
     *
     * @param operation what failed (see {@link #operation()})
     * @param detail libvips's error text; may be empty; never part of the message
     */
    public LibvipsException(String operation, String detail) {
        this(operation, detail, operation + " failed");
    }

    /**
     * @param operation what failed (see {@link #operation()})
     * @param detail libvips's error text; may be empty
     * @param message the exception message: fixed text, without {@code detail} or any file path
     */
    protected LibvipsException(String operation, String detail, String message) {
        super(message);
        this.operation = operation;
        this.detail = detail == null ? "" : detail;
    }

    /**
     * {@return the operation, C function or limit that failed or refused} One of:
     * <ul>
     * <li>an operation nickname, such as {@code extract_area}, {@code jpegsave_buffer} or {@code thumbnail_buffer};
     * </li>
     * <li>a loader's class name, such as {@code VipsForeignLoadJpegBuffer}, when the loader libvips picked fails;</li>
     * <li>a C function, {@code vips_foreign_find_load} or {@code vips_foreign_find_load_buffer}, when no loader
     * accepts the input ({@link UnsupportedFormatException});</li>
     * <li>{@code load} or {@code thumbnail} for an empty input ({@link UnsupportedFormatException});</li>
     * <li>{@link LimitExceededException#MAX_INPUT_SIZE}, {@link LimitExceededException#MAX_INPUT_PIXELS} or
     * {@link LimitExceededException#OUTPUT_SIZE} ({@link LimitExceededException});</li>
     * <li>{@code libvips} ({@link LibvipsUnavailableException}).</li>
     * </ul>
     */
    public String operation() {
        return operation;
    }

    /**
     * {@return libvips's error text since the last failure; may be empty} For logs only: never return it to clients.
     * It can name host file paths, and libvips keeps one error buffer for the whole process, so under concurrency the
     * text may belong to another thread's failure, and it may include warnings that successful calls left, such as
     * libjpeg's "premature end" after a lenient decode of a truncated JPEG, or "bad seek" lines after a HEIF read.
     * Decide on the exception's type and {@link #operation()}, not on this text.
     */
    public String detail() {
        return detail;
    }
}
