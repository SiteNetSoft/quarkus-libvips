package org.sitenetsoft.quarkus.libvips.internal;

/**
 * libvips's process-global error buffer.
 * <p>
 * Every failure is detected by the failing call's own return value; the buffer only supplies the text. {@link #take()}
 * is one {@code vips_error_buffer_copy}, which copies and clears the buffer under libvips's own lock, so no Java lock
 * and no separate {@code vips_error_clear} (that would erase text another thread appended in between). The text is
 * best-effort: it may hold warnings left by calls that succeeded, or text from another thread's failure.
 */
public final class ErrorBuffer {

    private ErrorBuffer() {
    }

    /**
     * Takes the buffer: returns its text and leaves it empty. Called right after a failed call, and by argument
     * validation to discard the text an unknown name leaves.
     *
     * @return the text, {@code ""} when empty
     */
    public static String take() {
        return LibvipsNative.errorBufferCopy();
    }
}
