package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * GLib resolves more spellings of an argument name than its canonical one: {@code -} for {@code _}, a
 * {@code Type::} prefix, and anything after a NUL is never seen by C. Two independent guards keep those spellings
 * from skipping the file-name check: the name's shape is checked before any native call, and the file-name check
 * reads the name from the argument's own {@code GParamSpec}.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class ArgumentNameTest {

    private static final List<String> MALFORMED = List.of("VipsIccImport::input_profile",
            "VipsIccImport::input-profile", "input_profile\0", "input_profile\0x", "_input",
            "-input", " input_profile", "input_profile ", "input.profile", "", "9in");

    @BeforeEach
    void start() {
        VipsRuntime.start(Settings.defaults());
    }

    @Test
    void malformedNamesRefusedBeforeAnyNativeCall() {
        try (Operation op = Operation.create("icc_import")) {
            ErrorBuffer.take();
            for (String name : MALFORMED) {
                assertThatThrownBy(() -> op.set(name, "srgb")).as(name)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not a valid argument name");
                assertThatThrownBy(() -> op.has(name)).as(name).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> op.firstInput("in", name)).as(name)
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> op.hasImageOutput(name)).as(name)
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> op.intMaximum(name)).as(name).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(ErrorBuffer.take()).isEmpty(); // nothing reached libvips
            assertThat(op.has("input_profile")).isTrue();
            assertThat(op.has("input-profile")).isTrue();
        }
    }

    @Test
    void lookupsLeaveTheErrorBufferAlone() {
        // the buffer is process-global: a lookup must not take text another call left (or is about to take)
        try (Operation op = Operation.create("extract_area")) {
            ErrorBuffer.take();
            assertThat(LibvipsNative.enumFromNick(LibvipsNative.interpretationType(), "nosuchvalue")).isNegative();
            assertThat(op.has("in")).isFalse();
            assertThat(op.has("nosuch")).isFalse();
            assertThat(op.firstInput("in", "input")).contains("input");
            assertThat(op.firstInput("nosuch")).isEmpty();
            assertThat(op.hasImageOutput("nosuch")).isFalse();
            assertThat(op.intMaximum("nosuch")).isEmpty();
            assertThatThrownBy(() -> op.set("nosuch", 1)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("extract_area has no argument nosuch");
            assertThat(ErrorBuffer.take()).contains("nosuchvalue").doesNotContain("no property");
        }
    }

    @Test
    void fileNameCheckUsesTheCanonicalName() {
        // past the shape check, straight to the lookup: every spelling GLib resolves is still checked
        try (Operation op = Operation.create("icc_import")) {
            for (String name : List.of("input_profile", "input-profile", "VipsIccImport::input_profile",
                    "VipsIccImport::input-profile", "input_profile\0", "input-profile\0x")) {
                assertThatThrownBy(() -> op.setWithoutNameCheck(name, "/")).as(name)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("input_profile = /");
            }
        }
        try (Operation op = Operation.create("jpegload")) {
            for (String name : List.of("filename", "VipsForeignLoadJpegFile::filename", "filename\0")) {
                assertThatThrownBy(() -> op.setWithoutNameCheck(name, "relative.jpg")).as(name)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("filename = relative.jpg");
            }
        }
        ErrorBuffer.take();
    }
}
