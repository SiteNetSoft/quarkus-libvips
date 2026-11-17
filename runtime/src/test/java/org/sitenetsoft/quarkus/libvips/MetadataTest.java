package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class MetadataTest {

    Libvips libvips;

    @BeforeEach
    void start() {
        libvips = TestLibvips.start(Settings.defaults());
    }

    @Test
    void orientationExifAndFields() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.orientation()).isEqualTo(6);
            assertThat(i.intField("orientation")).hasValue(6);
            assertThat(i.exif()).containsKey("exif-ifd0-Orientation");
            assertThat(i.exif().get("exif-ifd0-Orientation")).startsWith("6");
            assertThat(i.fields()).contains("icc-profile-data", "exif-data", "orientation");
            assertThat(i.loader()).isEqualTo("jpegload_buffer");
            assertThat(i.interpretation()).isEqualTo("srgb");
        }
    }

    @Test
    void exifIsSortedAndLeavesOutTheRawBlock() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.exif()).doesNotContainKey("exif-data").isNotEmpty()
                    .allSatisfy((k, v) -> assertThat(k).startsWith("exif-"));
            assertThat(List.copyOf(i.exif().keySet())).isSorted();
            assertThat(i.field("vips-loader")).contains("jpegload_buffer");
            assertThat(i.doubleField("orientation")).hasValue(6.0);
            assertThat(i.doubleField("xres")).isPresent();
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void iccProfile() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.iccProfile()).get().extracting(b -> b.length).isEqualTo(22944);
            assertThat(i.blobField("icc-profile-data")).get().extracting(b -> b.length).isEqualTo(22944);
        }
    }

    @Test
    void absentFieldsAreEmpty() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.field("nope")).isEmpty();
            assertThat(i.intField("nope")).isEmpty();
            assertThat(i.doubleField("nope")).isEmpty();
            assertThat(i.blobField("nope")).isEmpty();
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }

    @Test
    void fieldsOfAnotherTypeAreEmpty() {
        try (Image i = libvips.load(TestLibvips.portrait())) {
            assertThat(i.intField("vips-loader")).isEmpty(); // a string field read as int: empty, no error text
            assertThat(i.blobField("orientation")).isEmpty();
            assertThat(i.field("icc-profile-data")).isPresent();
        }
        assertThat(ErrorBuffer.take()).isEmpty();
    }
}
