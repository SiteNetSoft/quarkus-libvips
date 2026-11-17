package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class LibvipsNativeTest {

    @BeforeAll
    static void bind() {
        LibvipsNative.bind(SymbolLookup.libraryLookup(LibvipsAvailable.library(), Arena.global()));
        assertThat(LibvipsNative.isBound()).isTrue();
        assertThat(LibvipsNative.init("quarkus-libvips-test")).isZero();
    }

    @Test
    void initAndVersion() {
        assertThat(LibvipsNative.init("quarkus-libvips-test")).isZero(); // idempotent
        assertThat(LibvipsNative.version(0)).isEqualTo(8);
        assertThat(LibvipsNative.version(1)).isGreaterThanOrEqualTo(15);
    }

    @Test
    void errorBufferCopyEmptiesTheBuffer() {
        LibvipsNative.errorClear();
        assertThat(LibvipsNative.operationNew("no_such_operation")).isEqualTo(MemorySegment.NULL);
        assertThat(LibvipsNative.errorBufferCopy()).contains("no_such_operation");
        assertThat(LibvipsNative.errorBufferCopy()).isEmpty(); // the copy cleared it
    }

    @Test
    void argumentSpecFromGParamSpec() {
        MemorySegment op = LibvipsNative.operationNew("resize");
        try {
            ArgumentSpec scale = LibvipsNative.argument(op, "scale").orElseThrow();
            assertThat(LibvipsNative.fundamental(scale.valueType())).isEqualTo(LibvipsNative.G_TYPE_DOUBLE);
            assertThat(scale.flags() & ArgumentSpec.INPUT).isNotZero();
            ArgumentSpec out = LibvipsNative.argument(op, "out").orElseThrow();
            assertThat(LibvipsNative.typeIsA(out.valueType(), LibvipsNative.imageType())).isTrue();
            assertThat(out.flags() & ArgumentSpec.OUTPUT).isNotZero();
            assertThat(LibvipsNative.argument(op, "nosuch")).isEmpty();
            LibvipsNative.errorClear();
        } finally {
            LibvipsNative.unref(op);
        }
    }

    @Test
    void findLoadBufferAndSaveBuffer() throws Exception {
        byte[] jpeg = Files.readAllBytes(Path.of(getClass().getResource("/portrait.jpg").toURI()));
        assertThat(LibvipsNative.findLoadBuffer(jpeg)).contains("VipsForeignLoadJpegBuffer");
        assertThat(LibvipsNative.findSaveBuffer(".webp")).contains("VipsForeignSaveWebpBuffer");
        assertThat(LibvipsNative.saveSuffixes()).contains(".jpg", ".png", ".webp");
    }

    @Test
    void bindRefusesALibraryWithoutEverySymbol() {
        assertThatThrownBy(() -> LibvipsNative.bind(name -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("symbol vips_init not found");
        assertThat(LibvipsNative.isBound()).isTrue(); // the earlier binding stays
    }

    @Test
    void unknownSymbolIsNamed() {
        assertThatThrownBy(() -> LibvipsNative.h("vips_no_such_function"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vips_no_such_function");
    }

    @Test
    void fieldOfAnotherTypeIsEmptyWithoutErrorText() throws Exception {
        MemorySegment image = loadPortrait();
        try {
            LibvipsNative.errorClear();
            assertThat(LibvipsNative.intField(image, "vips-loader")).isEmpty(); // a string field
            assertThat(LibvipsNative.intField(image, "xres")).isEmpty(); // a double field
            assertThat(LibvipsNative.doubleField(image, "vips-loader")).isEmpty();
            assertThat(LibvipsNative.blobField(image, "orientation")).isEmpty(); // an int field
            assertThat(LibvipsNative.doubleField(image, "orientation")).hasValue(6.0); // int converts
            assertThat(LibvipsNative.errorBufferCopy()).isEmpty();
        } finally {
            LibvipsNative.unref(image);
        }
    }

    /**
     * Calls every wrapper with valid arguments, so a layout or a call-site type that does not match its descriptor
     * fails here rather than in a later caller.
     */
    @Test
    void everyWrapperRoundTrips() throws Exception {
        assertThat(LibvipsNative.symbol("vips_init").address()).isNotZero();
        assertThatThrownBy(() -> LibvipsNative.symbol("vips_no_such_function"))
                .isInstanceOf(IllegalArgumentException.class);
        int concurrency = LibvipsNative.concurrencyGet();
        int cacheMax = LibvipsNative.cacheGetMax();
        long cacheMaxMem = LibvipsNative.cacheGetMaxMem();
        int cacheMaxFiles = LibvipsNative.cacheGetMaxFiles();
        try {
            LibvipsNative.concurrencySet(2);
            assertThat(LibvipsNative.concurrencyGet()).isEqualTo(2);
            LibvipsNative.cacheSetMax(77);
            assertThat(LibvipsNative.cacheGetMax()).isEqualTo(77);
            LibvipsNative.cacheSetMaxMem(5L << 33); // above 4 GiB: size_t is 64-bit both ways
            assertThat(LibvipsNative.cacheGetMaxMem()).isEqualTo(5L << 33);
            LibvipsNative.cacheSetMaxFiles(9);
            assertThat(LibvipsNative.cacheGetMaxFiles()).isEqualTo(9);
        } finally {
            LibvipsNative.concurrencySet(concurrency);
            LibvipsNative.cacheSetMax(cacheMax);
            LibvipsNative.cacheSetMaxMem(cacheMaxMem);
            LibvipsNative.cacheSetMaxFiles(cacheMaxFiles);
        }
        assertThat(LibvipsNative.trackedGetMem()).isNotNegative();

        // blocking
        long ppm = LibvipsNative.typeFind("VipsOperation", "ppmload");
        assertThat(LibvipsNative.typeName(ppm)).isEqualTo("VipsForeignLoadPpmFile");
        assertThat(LibvipsNative.typeName(0)).isNull();
        long operation = LibvipsNative.operationType();
        assertThat(LibvipsNative.typeName(operation)).isEqualTo("VipsOperation");
        long png = LibvipsNative.typeFromName("VipsForeignLoadPng"); // abstract: vips_type_find returns 0
        assertThat(LibvipsNative.typeFind("VipsOperation", "VipsForeignLoadPng")).isZero();
        assertThat(png).isNotZero();
        assertThat(LibvipsNative.typeIsA(png, operation)).isTrue();
        assertThat(LibvipsNative.typeFromName("pngload_buffer")).isZero(); // a nickname is not a type name
        try {
            LibvipsNative.blockUntrustedSet(true);
            LibvipsNative.operationBlockSet("VipsForeignLoadPpm", true);
            MemorySegment op = LibvipsNative.operationNew("ppmload");
            try {
                assertThat(LibvipsNative.operationGetFlags(op) & LibvipsNative.VIPS_OPERATION_BLOCKED).isNotZero();
            } finally {
                LibvipsNative.unref(op);
            }
        } finally {
            LibvipsNative.blockUntrustedSet(false);
            LibvipsNative.operationBlockSet("VipsForeignLoadPpm", false);
        }

        // types and enums
        assertThat(LibvipsNative.fundamental(LibvipsNative.imageType())).isEqualTo(LibvipsNative.G_TYPE_OBJECT);
        assertThat(LibvipsNative.fundamental(LibvipsNative.blobType())).isEqualTo(LibvipsNative.G_TYPE_BOXED);
        assertThat(LibvipsNative.fundamental(LibvipsNative.arrayDoubleType())).isEqualTo(LibvipsNative.G_TYPE_BOXED);
        long interpretation = LibvipsNative.interpretationType();
        assertThat(LibvipsNative.fundamental(interpretation)).isEqualTo(LibvipsNative.G_TYPE_ENUM);
        assertThat(LibvipsNative.enumNick(interpretation, 22)).isEqualTo("srgb");
        assertThat(LibvipsNative.enumFromNick(interpretation, "srgb")).isEqualTo(22);
        LibvipsNative.errorClear();
        assertThat(LibvipsNative.enumFromNick(interpretation, "no-such-nick")).isEqualTo(-1);
        assertThat(LibvipsNative.errorBufferCopy()).contains("quarkus-libvips").contains("no-such-nick");

        // load through the operation API: blob, boolean, enum and int setters; image, int and boolean getters
        MemorySegment image = loadPortrait();
        try {
            assertThat(LibvipsNative.width(image)).isEqualTo(400);
            assertThat(LibvipsNative.height(image)).isEqualTo(300);
            assertThat(LibvipsNative.bands(image)).isEqualTo(3);
            assertThat(LibvipsNative.nPages(image)).isEqualTo(1);
            assertThat(LibvipsNative.pageHeight(image)).isEqualTo(300);
            assertThat(LibvipsNative.orientation(image)).isEqualTo(6);
            assertThat(LibvipsNative.interpretation(image)).isEqualTo(22);
            assertThat(LibvipsNative.hasAlpha(image)).isFalse();
            assertThat(LibvipsNative.fields(image)).contains("icc-profile-data", "orientation", "vips-loader");
            assertThat(LibvipsNative.typeOf(image, "nosuch")).isZero();
            assertThat(LibvipsNative.typeOf(image, "orientation")).isEqualTo(LibvipsNative.G_TYPE_INT);
            assertThat(LibvipsNative.asString(image, "width")).contains("400");
            assertThat(LibvipsNative.asString(image, "vips-loader")).contains("jpegload_buffer");
            assertThat(LibvipsNative.intField(image, "orientation")).hasValue(6);
            assertThat(LibvipsNative.doubleField(image, "xres")).isPresent();
            assertThat(LibvipsNative.blobField(image, "icc-profile-data")).get()
                    .extracting(b -> b.length).isEqualTo(22944);
            LibvipsNative.errorClear();
            assertThat(LibvipsNative.asString(image, "nosuch")).isEmpty();
            assertThat(LibvipsNative.intField(image, "nosuch")).isEmpty();
            assertThat(LibvipsNative.doubleField(image, "nosuch")).isEmpty();
            assertThat(LibvipsNative.blobField(image, "nosuch")).isEmpty();
            assertThat(LibvipsNative.errorBufferCopy()).isEmpty(); // absent fields leave no error text

            // linear: image and double-array setters
            MemorySegment linear = LibvipsNative.operationNew("linear");
            LibvipsNative.setImage(linear, "in", image);
            LibvipsNative.setArrayDouble(linear, "a", new double[] { 1, 1, 1 });
            LibvipsNative.setArrayDouble(linear, "b", new double[] { 0, 0, 0 });
            LibvipsNative.setBoolean(linear, "uchar", true);
            MemorySegment scaled = build(linear, "out");
            try {
                assertThat(LibvipsNative.width(scaled)).isEqualTo(400);

                // webpsave_buffer: int, string and flags setters; blob getter
                MemorySegment save = LibvipsNative.operationNew("webpsave_buffer");
                try {
                    LibvipsNative.setImage(save, "in", scaled);
                    LibvipsNative.setInt(save, "Q", 80);
                    LibvipsNative.setString(save, "profile", "srgb");
                    long keep = LibvipsNative.argument(save, "keep").orElseThrow().valueType();
                    assertThat(LibvipsNative.fundamental(keep)).isEqualTo(LibvipsNative.G_TYPE_FLAGS);
                    int flags = LibvipsNative.flagsFromNick(keep, "icc:exif");
                    assertThat(flags).isPositive();
                    LibvipsNative.setFlags(save, "keep", keep, flags);
                    MemorySegment built = LibvipsNative.cacheOperationBuild(save);
                    assertThat(built).as(LibvipsNative.errorBufferCopy()).isNotEqualTo(MemorySegment.NULL);
                    try {
                        byte[] webp = LibvipsNative.getBlob(built, "buffer");
                        assertThat(new String(webp, 8, 4, java.nio.charset.StandardCharsets.US_ASCII))
                                .isEqualTo("WEBP");
                    } finally {
                        LibvipsNative.unrefOutputs(built);
                        LibvipsNative.unref(built);
                    }
                } finally {
                    LibvipsNative.unref(save);
                }
            } finally {
                LibvipsNative.unref(scaled);
            }
        } finally {
            LibvipsNative.unref(image);
        }

        // double setter and getter on an unbuilt operation
        MemorySegment resize = LibvipsNative.operationNew("resize");
        try {
            LibvipsNative.setDouble(resize, "scale", 0.25);
            assertThat(LibvipsNative.getDouble(resize, "scale")).isEqualTo(0.25);
        } finally {
            LibvipsNative.unref(resize);
        }

        assertThat(LibvipsNative.findLoad(portraitPath().toString())).contains("VipsForeignLoadJpegFile");
        assertThat(LibvipsNative.findLoadBuffer(new byte[0])).isEmpty();
        LibvipsNative.errorClear();
    }

    /** Loads the fixture with jpegload_buffer and checks the int and boolean getters on the built operation. */
    private MemorySegment loadPortrait() throws Exception {
        byte[] jpeg = Files.readAllBytes(portraitPath());
        MemorySegment op = LibvipsNative.operationNew("jpegload_buffer");
        try {
            LibvipsNative.setBlobCopy(op, "buffer", jpeg);
            LibvipsNative.setBoolean(op, "autorotate", false);
            LibvipsNative.setInt(op, "shrink", 1);
            long failOn = LibvipsNative.argument(op, "fail_on").orElseThrow().valueType();
            LibvipsNative.setEnum(op, "fail_on", failOn, LibvipsNative.enumFromNick(failOn, "none"));
            MemorySegment built = LibvipsNative.cacheOperationBuild(op);
            assertThat(built).as(LibvipsNative.errorBufferCopy()).isNotEqualTo(MemorySegment.NULL);
            try {
                assertThat(LibvipsNative.getInt(built, "shrink")).isEqualTo(1);
                assertThat(LibvipsNative.getBoolean(built, "autorotate")).isFalse();
                return LibvipsNative.getImage(built, "out");
            } finally {
                LibvipsNative.unrefOutputs(built);
                LibvipsNative.unref(built);
            }
        } finally {
            LibvipsNative.unref(op);
        }
    }

    /** Builds {@code op}, releases it, and returns a new reference to its image output {@code name}. */
    private static MemorySegment build(MemorySegment op, String name) {
        try {
            MemorySegment built = LibvipsNative.cacheOperationBuild(op);
            assertThat(built).as(LibvipsNative.errorBufferCopy()).isNotEqualTo(MemorySegment.NULL);
            try {
                return LibvipsNative.getImage(built, name);
            } finally {
                LibvipsNative.unrefOutputs(built);
                LibvipsNative.unref(built);
            }
        } finally {
            LibvipsNative.unref(op);
        }
    }

    private Path portraitPath() throws Exception {
        return Path.of(getClass().getResource("/portrait.jpg").toURI());
    }
}
