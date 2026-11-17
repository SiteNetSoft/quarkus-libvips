package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class NativeImageTest {

    @AfterEach
    void restore() {
        VipsRuntime.start(Settings.defaults());
    }

    private static NativeImage black(VipsRuntime runtime) {
        try (Operation op = Operation.create("black")) {
            op.set("width", 8).set("height", 8);
            try (Operation.Built built = op.build()) {
                return built.image("out", runtime);
            }
        }
    }

    @Test
    void closeDuringUseKeepsTheImageUntilTheUseEnds() {
        VipsRuntime runtime = VipsRuntime.start(Settings.defaults());
        NativeImage image = black(runtime);
        assertThat(runtime.openImages()).isEqualTo(1);
        int width = image.use(segment -> {
            image.close(); // another thread's close, in effect: the reference outlives this use
            return LibvipsNative.width(segment);
        });
        assertThat(width).isEqualTo(8);
        assertThat(image.closed()).isTrue();
        assertThatThrownBy(image::width).isInstanceOf(IllegalStateException.class).hasMessage("image is closed");
        assertThat(runtime.openImages()).isZero();
        assertThat(runtime.leakedImages()).isZero();
    }

    @Test
    void closeAfterTheRuntimeStoppedSkipsTheUnref() throws Throwable {
        VipsRuntime runtime = VipsRuntime.start(Settings.defaults());
        NativeImage image = black(runtime);
        MemorySegment raw = image.use(segment -> segment);
        MemorySegment unused = (MemorySegment) LibvipsNative.h("g_object_ref").invokeExact(raw); // keeps it alive
        runtime.stop(false); // also empties the operation cache, which held references to this image
        int afterStop = refCount(raw);
        image.close(); // no native call: libvips may be shut down by now
        assertThat(refCount(raw)).isEqualTo(afterStop); // close did not unref
        assertThat(runtime.openImages()).isZero();
        assertThatThrownBy(image::width).isInstanceOf(IllegalStateException.class);
        LibvipsNative.unref(raw);
        LibvipsNative.unref(raw);
    }

    /** {@code GObject.ref_count} (gobject.h: {@code GTypeInstance}, a pointer, then {@code guint ref_count}). */
    private static int refCount(NativeImage image) {
        return image.use(NativeImageTest::refCount);
    }

    private static int refCount(MemorySegment object) {
        return object.reinterpret(16).get(ValueLayout.JAVA_INT, 8);
    }

    private static void resize(NativeImage input, double scale, VipsRuntime runtime) {
        try (Operation op = Operation.create("resize")) {
            op.set("in", input).set("scale", scale);
            try (Operation.Built built = op.build()) {
                built.image("out", runtime).close();
            }
        }
    }

    @Test
    void buildDropsEveryOperationReferenceOnSuccessAndFailure() { // N12
        VipsRuntime runtime = VipsRuntime.start(Settings.defaults());
        NativeImage input = black(runtime);
        try {
            for (int n = 0; n < 7; n++) {
                resize(input, 0.25 + n * 0.01, runtime); // the cache now holds these seven, each with a reference
            }
            int warm = refCount(input);
            for (int n = 0; n < 500; n++) {
                resize(input, 0.25 + (n % 7) * 0.01, runtime);
                try (Operation op = Operation.create("extract_area")) {
                    op.set("input", input).set("left", 0).set("top", 0).set("width", 10000).set("height", 1);
                    assertThatThrownBy(op::build).isInstanceOf(org.sitenetsoft.quarkus.libvips.LibvipsException.class);
                }
            }
            assertThat(refCount(input)).isEqualTo(warm); // a leaked operation would hold the input
        } finally {
            input.close();
        }
        assertThat(runtime.openImages()).isZero();
    }

    @Test
    void cachedCallsLeaveTheOutputWithOneReferenceForUsAndOneForTheCache() {
        VipsRuntime runtime = VipsRuntime.start(Settings.defaults().withCacheMaxOperations(100)); // off by default
        NativeImage input = black(runtime);
        try {
            for (int n = 0; n < 10; n++) {
                try (Operation op = Operation.create("resize")) {
                    op.set("in", input).set("scale", 0.5);
                    try (Operation.Built built = op.build()) {
                        NativeImage out = built.image("out", runtime);
                        built.close(); // drops the built operation's outputs
                        try {
                            assertThat(refCount(out)).as("call %d", n).isEqualTo(2);
                        } finally {
                            out.close();
                        }
                    }
                }
            }
        } finally {
            input.close();
        }
    }

    @Test
    void setValidatesFlagsModifiedArgumentsTypesAndNull() {
        VipsRuntime runtime = VipsRuntime.start(Settings.defaults());
        NativeImage image = black(runtime);
        try {
            try (Operation op = Operation.create("jpegsave_buffer")) {
                op.set("keep", "icc:exif").set("keep", 8);
                assertThatThrownBy(() -> op.set("keep", 1 << 20)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("jpegsave_buffer: keep = 1048576 is not a valid value");
            }
            try (Operation op = Operation.create("draw_rect")) {
                assertThat(op.has("image")).isFalse();
                assertThatThrownBy(() -> op.set("image", image)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("draw_rect: image is modified in place; not supported");
                assertThat(op.hasImageOutput("out")).isFalse();
            }
            try (Operation op = Operation.create("affine")) {
                assertThat(op.hasImageOutput("out")).isTrue();
                assertThatThrownBy(() -> op.set("interpolate", "bicubic"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("affine: interpolate has unsupported type VipsInterpolate");
            }
            try (Operation op = Operation.create("black")) {
                assertThatThrownBy(() -> op.set("width", null)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("black: width expects gint, got null");
            }
        } finally {
            image.close();
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void doubleArraysAndBooleansReachTheOperation() {
        VipsRuntime runtime = VipsRuntime.start(Settings.defaults());
        NativeImage input = black(runtime);
        try (Operation op = Operation.create("linear")) {
            op.set("in", input).set("a", new double[] { 2 }).set("b", new double[] { 3 }).set("uchar", true);
            try (Operation.Built built = op.build(); NativeImage out = built.image("out", runtime);
                    Operation avg = Operation.create("avg")) {
                avg.set("in", out);
                try (Operation.Built mean = avg.build()) {
                    assertThat(mean.doubleValue("out")).isEqualTo(3.0); // 2 * 0 + 3
                }
            }
        } finally {
            input.close();
        }
    }

    @Test
    void setRefusesUnsupportedTypesAndUnknownNicks() {
        VipsRuntime.start(Settings.defaults());
        try (Operation op = Operation.create("jpegload_buffer")) {
            assertThat(op.has("fail_on")).isTrue();
            assertThat(op.has("flags")).isFalse(); // an output
            assertThatThrownBy(() -> op.set("fail_on", "sometimes")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("jpegload_buffer: fail_on has no value sometimes in VipsFailOn");
            assertThatThrownBy(() -> op.set("buffer", "text")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("jpegload_buffer: buffer expects VipsBlob, got String");
            op.set("fail_on", "truncated").set("access", "sequential").set("shrink", 2).set("autorotate", true);
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }
}
