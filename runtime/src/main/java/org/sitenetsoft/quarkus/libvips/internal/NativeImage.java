package org.sitenetsoft.quarkus.libvips.internal;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;

/**
 * The owner of one {@code VipsImage} reference.
 * <p>
 * A count starts at 1 for the owner. Every {@link #use} acquires (+1, refused once the count reached 0 or
 * {@link #close} ran) and releases (−1); {@link #close} releases the owner's 1 once. Whoever brings the count to 0
 * drops the reference with {@code g_object_unref}, so closing while another thread uses the image never frees memory
 * that call still reads. A {@link Cleaner} releases an image that was never closed and counts it as leaked.
 * <p>
 * Each instance belongs to exactly one public {@code Image}, so it becomes unreachable together with it; the cleaner
 * is registered on this object.
 */
public final class NativeImage implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private NativeImage(State state) {
        this.state = state;
        this.cleanable = CLEANER.register(this, state);
    }

    /**
     * Takes ownership of a reference.
     *
     * @param image a {@code VipsImage *} whose reference the caller hands over
     * @param runtime the runtime the image belongs to; counts it as open
     * @return the owner
     */
    static NativeImage adopt(MemorySegment image, VipsRuntime runtime) {
        runtime.imageOpened();
        return new NativeImage(new State(image, runtime));
    }

    /**
     * Runs {@code body} with the image, which stays alive until {@code body} returns.
     *
     * @param <T> the result type
     * @param body what to do with the {@code VipsImage *}
     * @return what {@code body} returns
     * @throws IllegalStateException {@code image is closed} after {@link #close}
     */
    public <T> T use(Function<MemorySegment, T> body) {
        state.acquire();
        try {
            state.runtime.enter();
            try {
                return body.apply(state.image);
            } finally {
                state.runtime.exit();
            }
        } finally {
            state.release();
        }
    }

    /** {@return {@code vips_image_get_width}} */
    public int width() {
        return use(LibvipsNative::width);
    }

    /** {@return {@code vips_image_get_height}} */
    public int height() {
        return use(LibvipsNative::height);
    }

    /** {@return {@code vips_image_get_bands}} */
    public int bands() {
        return use(LibvipsNative::bands);
    }

    /** {@return {@code vips_image_get_n_pages}} */
    public int pages() {
        return use(LibvipsNative::nPages);
    }

    /** {@return {@code vips_image_get_page_height}} */
    public int pageHeight() {
        return use(LibvipsNative::pageHeight);
    }

    /** {@return {@code vips_image_hasalpha}} */
    public boolean hasAlpha() {
        return use(LibvipsNative::hasAlpha);
    }

    /** {@return whether {@link #close} has run (or the cleaner released the image)} */
    public boolean closed() {
        return state.ownerReleased.get();
    }

    /** Releases the owner's reference; idempotent and thread-safe. */
    @Override
    public void close() {
        state.releaseOwner(false);
        cleanable.clean(); // deregisters; the action does nothing once the owner is released
    }

    /** The cleaner's state: must not refer to the {@link NativeImage}. */
    private static final class State implements Runnable {

        final MemorySegment image;
        final VipsRuntime runtime;
        final AtomicInteger count = new AtomicInteger(1);
        final AtomicBoolean ownerReleased = new AtomicBoolean();

        State(MemorySegment image, VipsRuntime runtime) {
            this.image = image;
            this.runtime = runtime;
        }

        void acquire() {
            int c;
            do {
                c = count.get();
                if (c == 0) {
                    throw closed();
                }
            } while (!count.compareAndSet(c, c + 1));
            if (ownerReleased.get()) {
                release();
                throw closed();
            }
        }

        void release() {
            if (count.decrementAndGet() == 0) {
                unref();
            }
        }

        void releaseOwner(boolean leaked) {
            if (ownerReleased.compareAndSet(false, true)) {
                runtime.imageClosed(leaked);
                release();
            }
        }

        /** The last reference: dropped, unless the runtime has stopped (libvips may be shut down then). */
        private void unref() {
            try {
                runtime.enter();
            } catch (LibvipsUnavailableException e) {
                return;
            }
            try {
                LibvipsNative.unref(image);
            } finally {
                runtime.exit();
            }
        }

        @Override
        public void run() {
            releaseOwner(true);
        }

        private static IllegalStateException closed() {
            return new IllegalStateException("image is closed");
        }
    }
}
