package org.sitenetsoft.quarkus.libvips.internal;

import io.quarkus.runtime.BlockingOperationControl;
import io.quarkus.runtime.BlockingOperationNotAllowedException;

/**
 * The event-loop guard: libvips decodes, transforms and encodes on the calling thread, so a call that runs an
 * operation must never run on an I/O thread, as Quarkus's own blocking APIs refuse (Hibernate ORM's session, for
 * example). {@link BlockingOperationControl} knows the I/O threads once Vert.x has started; without Quarkus (plain unit
 * tests) every thread may block.
 */
public final class Blocking {

    private Blocking() {
    }

    /**
     * Refuses to go on when the calling thread must not block.
     *
     * @param method the public method, such as {@code Libvips.thumbnail}
     * @throws BlockingOperationNotAllowedException on an I/O thread (a Vert.x event loop)
     */
    public static void check(String method) {
        if (!blockingAllowed()) {
            throw new BlockingOperationNotAllowedException(message(method));
        }
    }

    /**
     * {@return whether the calling thread may block} Quarkus registers its I/O-thread detectors at static init of
     * every application (an empty list without Vert.x); before that, outside Quarkus, there are none and
     * {@link BlockingOperationControl#isBlockingAllowed()} fails on the missing list: every thread may block then.
     */
    static boolean blockingAllowed() {
        try {
            return BlockingOperationControl.isBlockingAllowed();
        } catch (NullPointerException e) {
            return true;
        }
    }

    /** {@return the fixed message for {@code method}} */
    static String message(String method) {
        return method + " was called on an I/O thread, where blocking is not allowed: libvips decodes, transforms and"
                + " encodes images on the calling thread. Call it from a worker thread: annotate the endpoint or method"
                + " with @Blocking, use vertx.executeBlocking(...), or switch a Mutiny pipeline with"
                + " emitOn(Infrastructure.getDefaultWorkerPool()) before the call.";
    }
}
