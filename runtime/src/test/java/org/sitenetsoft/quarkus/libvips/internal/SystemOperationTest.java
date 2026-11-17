package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * libvips's {@code system} runs a shell command and libvips does not mark it untrusted. These tests reach it below
 * every Java-side refusal, so they hold whatever the argument marshalling can do.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class SystemOperationTest {

    @AfterEach
    void restore() {
        VipsRuntime.start(Settings.defaults());
    }

    @Test
    void operationCreateRefusesSystem() {
        VipsRuntime.start(Settings.defaults());
        for (String name : List.of("system", "VipsSystem")) {
            assertThatThrownBy(() -> Operation.create(name).close()).as(name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not available");
        }
    }

    @Test
    void systemCannotRunEvenWhenBuiltDirectly(@TempDir Path dir) {
        for (Settings settings : List.of(Settings.defaults(), Settings.defaults().withSecurity(false, List.of(),
                List.of()), Settings.defaults().withSecurity(true, List.of(), List.of("VipsOperation")))) {
            VipsRuntime.start(settings);
            Path marker = dir.resolve("ran");
            ErrorBuffer.take();
            MemorySegment op = LibvipsNative.operationNew("system");
            assertThat(op).isNotEqualTo(MemorySegment.NULL);
            try {
                LibvipsNative.setString(op, "cmd_format", "touch " + marker);
                MemorySegment built = LibvipsNative.cacheOperationBuild(op);
                if (!built.equals(MemorySegment.NULL)) {
                    LibvipsNative.unrefOutputs(built);
                    LibvipsNative.unref(built);
                }
                assertThat(built).as(settings.toString()).isEqualTo(MemorySegment.NULL);
                assertThat(ErrorBuffer.take()).contains("operation is blocked");
            } finally {
                LibvipsNative.unrefOutputs(op);
                LibvipsNative.unref(op);
            }
            assertThat(marker).doesNotExist();
        }
    }
}
