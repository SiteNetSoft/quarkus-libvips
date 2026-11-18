package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.TestLibvips;

/**
 * An input array is copied once into memory from {@code g_try_malloc} and handed to libvips with
 * {@code vips_blob_new}; libvips frees it through the function given, exactly once, when the last reference goes:
 * whether the operation is built, fails or is never built, and however long a loaded image keeps the input. The free
 * function is swapped here for a counting one (an upcall, in tests only) that then calls {@code g_free}; production
 * passes {@code g_free}'s address itself.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class BlobOwnershipTest {

    private static final AtomicInteger FREED = new AtomicInteger();
    private static final List<Long> FREED_ADDRESSES = new ArrayList<>();
    private static final MemorySegment COUNTING_FREE;

    static {
        try {
            COUNTING_FREE = Linker.nativeLinker().upcallStub(
                    MethodHandles.lookup().findStatic(BlobOwnershipTest.class, "countingFree",
                            MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** {@code int (*VipsCallbackFn)(void *data, void *area)}: counts, then frees as {@code g_free} would. */
    static int countingFree(MemorySegment data, MemorySegment area) {
        synchronized (FREED_ADDRESSES) {
            FREED_ADDRESSES.add(data.address());
        }
        FREED.incrementAndGet();
        try {
            LibvipsNative.h("g_free").invokeExact(data);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return 0;
    }

    private final List<Long> allocated = new ArrayList<>();
    private final LongFunction<MemorySegment> malloc = size -> {
        MemorySegment p = LibvipsNative.tryMalloc(size);
        allocated.add(p.address());
        return p;
    };

    @BeforeEach
    void start() {
        TestLibvips.start(Settings.defaults());
        FREED.set(0);
        synchronized (FREED_ADDRESSES) {
            FREED_ADDRESSES.clear();
        }
    }

    @AfterEach
    void restore() {
        VipsRuntime.start(Settings.defaults());
    }

    private void assertFreedOnce() {
        assertThat(FREED.get()).isEqualTo(1);
        synchronized (FREED_ADDRESSES) {
            assertThat(FREED_ADDRESSES).containsExactlyElementsOf(allocated);
        }
    }

    @Test
    void anOperationNeverBuiltFreesTheInputOnce() {
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        LibvipsNative.setBlob(op, "buffer", TestLibvips.portrait(), COUNTING_FREE, malloc);
        assertThat(allocated).hasSize(1);
        assertThat(FREED.get()).as("the operation holds it").isZero();
        LibvipsNative.unref(op);
        assertFreedOnce();
    }

    @Test
    void aLoadedImageKeepsTheInputUntilItIsReleased() {
        byte[] jpeg = TestLibvips.portrait();
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        MemorySegment image;
        try {
            LibvipsNative.setBlob(op, "buffer", jpeg, COUNTING_FREE, malloc);
            assertThat(LibvipsNative.objectBuild(op)).as(LibvipsNative.errorBufferCopy()).isZero();
            image = LibvipsNative.getImage(op, "out");
            LibvipsNative.unrefOutputs(op);
        } finally {
            LibvipsNative.unref(op);
        }
        assertThat(FREED.get()).as("the image decodes lazily from the input").isZero();
        assertThat(LibvipsNative.width(image)).isEqualTo(400);
        LibvipsNative.unref(image);
        assertFreedOnce();
    }

    @Test
    void theNativeCopyHoldsTheCallersBytes() {
        byte[] jpeg = TestLibvips.portrait();
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        try {
            LibvipsNative.setBlob(op, "buffer", jpeg, COUNTING_FREE, malloc);
            MemorySegment copy = MemorySegment.ofAddress(allocated.getFirst()).reinterpret(jpeg.length);
            assertThat(copy.toArray(JAVA_BYTE)).isEqualTo(jpeg);
            Arrays.fill(jpeg, (byte) 0); // the caller's array is not kept
            assertThat(LibvipsNative.objectBuild(op)).as(LibvipsNative.errorBufferCopy()).isZero();
            LibvipsNative.unrefOutputs(op);
        } finally {
            LibvipsNative.unref(op);
        }
        assertFreedOnce();
    }

    @Test
    void aFailedBuildFreesTheInputOnce() {
        byte[] garbage = new byte[4096];
        Arrays.fill(garbage, (byte) 0x5a);
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        try {
            LibvipsNative.setBlob(op, "buffer", garbage, COUNTING_FREE, malloc);
            assertThat(LibvipsNative.objectBuild(op)).isNotZero();
            LibvipsNative.errorClear();
            LibvipsNative.unrefOutputs(op);
        } finally {
            LibvipsNative.unref(op);
        }
        assertFreedOnce();
    }

    @Test
    void aThumbnailWithTheCacheOnFreesTheInputOnce() {
        LibvipsNative.cacheSetMax(100);
        try {
            MemorySegment op = LibvipsNative.operationNew("thumbnail_buffer");
            MemorySegment thumbnail;
            try {
                LibvipsNative.setBlob(op, "buffer", TestLibvips.portrait(), COUNTING_FREE, malloc);
                LibvipsNative.setInt(op, "width", 50);
                MemorySegment built = LibvipsNative.cacheOperationBuild(op);
                assertThat(built).as(LibvipsNative.errorBufferCopy()).isNotEqualTo(MemorySegment.NULL);
                thumbnail = LibvipsNative.getImage(built, "out");
                LibvipsNative.unrefOutputs(built);
                LibvipsNative.unref(built);
            } finally {
                LibvipsNative.unref(op);
            }
            LibvipsNative.unref(thumbnail);
            assertThat(FREED.get()).as("never more than once, cached or not").isLessThanOrEqualTo(1);
        } finally {
            LibvipsNative.cacheSetMax(0);
        }
        assertFreedOnce();
    }

    @Test
    void anAllocationFailureAllocatesAndFreesNothing() {
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        try {
            assertThatThrownBy(() -> LibvipsNative.setBlob(op, "buffer", TestLibvips.portrait(), COUNTING_FREE,
                    size -> MemorySegment.NULL))
                    .isInstanceOf(OutOfMemoryError.class)
                    .hasMessage("native memory for a 28029-byte input could not be allocated");
        } finally {
            LibvipsNative.unref(op);
        }
        assertThat(FREED.get()).isZero();
    }

    @Test
    void anUnknownArgumentAllocatesNothing() {
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        try {
            assertThatThrownBy(() -> LibvipsNative.setBlob(op, "no_such_argument", TestLibvips.portrait(),
                    COUNTING_FREE, malloc)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            LibvipsNative.unref(op);
        }
        assertThat(allocated).isEmpty();
        assertThat(FREED.get()).isZero();
    }

    @Test
    void anEmptyArrayHasNothingToFree() {
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        LibvipsNative.setBlob(op, "buffer", new byte[0], COUNTING_FREE, malloc);
        LibvipsNative.unref(op);
        assertThat(allocated).isEmpty();
        assertThat(FREED.get()).isZero();
    }

    @Test
    void productionHandsOverGFree() {
        assertThat(LibvipsNative.gFreeAddress()).isEqualTo(LibvipsNative.symbol("g_free"));
        assertThat(LibvipsNative.DOWNCALLS).doesNotContainKey("vips_blob_copy");
        assertThat(LibvipsNative.DOWNCALLS.get("vips_blob_new").descriptor())
                .isEqualTo(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    }
}
