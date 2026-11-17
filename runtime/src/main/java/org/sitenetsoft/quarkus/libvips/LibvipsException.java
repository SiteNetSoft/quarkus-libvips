package org.sitenetsoft.quarkus.libvips;

/**
 * A libvips operation failed.
 */
public class LibvipsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final String detail;

    /**
     * @param operation the libvips operation (nickname) that failed
     * @param detail libvips's error text; may be empty
     */
    public LibvipsException(String operation, String detail) {
        this(operation, detail, operation + " failed" + (detail == null || detail.isBlank() ? "" : ": " + detail.strip()));
    }

    /**
     * @param operation the operation
     * @param detail libvips's error text; may be empty
     * @param message the exception message
     */
    protected LibvipsException(String operation, String detail, String message) {
        super(message);
        this.operation = operation;
        this.detail = detail == null ? "" : detail;
    }

    /** {@return the libvips operation (nickname) that failed} */
    public String operation() {
        return operation;
    }

    /**
     * {@return libvips's error text since the last failure; may be empty, and may include warnings or text from other
     * calls} libvips keeps one error buffer for the process, and some successful calls leave text in it, such as
     * libjpeg's "premature end" warning after a lenient decode of a truncated JPEG, or "bad seek" lines after a HEIF
     * read.
     */
    public String detail() {
        return detail;
    }
}
