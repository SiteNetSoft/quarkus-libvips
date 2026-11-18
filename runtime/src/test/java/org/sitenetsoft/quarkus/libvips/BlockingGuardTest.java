package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

import io.quarkus.runtime.BlockingOperationControl;
import io.quarkus.runtime.BlockingOperationNotAllowedException;
import io.quarkus.runtime.IOThreadDetector;

/**
 * Every method that runs a libvips operation refuses an I/O thread; header reads and introspection do not. The I/O
 * thread is simulated with Quarkus's own detector hook, for this test's thread only.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class BlockingGuardTest {

    Libvips libvips;
    Image image;
    volatile Thread ioThread;

    @BeforeEach
    void start() {
        libvips = TestLibvips.start(Settings.defaults());
        image = libvips.load(TestLibvips.portrait());
    }

    @AfterEach
    void stop() {
        ioThread = null;
        BlockingOperationControl.setIoThreadDetector(null); // as outside Quarkus: no detector list at all
        image.close();
    }

    /** From here on, this thread counts as an I/O thread (a Vert.x event loop). */
    private void onIoThread() {
        ioThread = Thread.currentThread();
        IOThreadDetector detector = () -> Thread.currentThread() == ioThread;
        BlockingOperationControl.setIoThreadDetector(new IOThreadDetector[] { detector });
        assertThat(BlockingOperationControl.isBlockingAllowed()).isFalse();
    }

    @Test
    void outsideQuarkusEveryThreadMayBlock() {
        // without Quarkus there is no detector list (Quarkus sets one at static init, empty without Vert.x)
        BlockingOperationControl.setIoThreadDetector(null);
        try (Image t = libvips.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT)) {
            assertThat(t.width()).isEqualTo(50);
        }
        BlockingOperationControl.setIoThreadDetector(new IOThreadDetector[0]); // a Quarkus application without Vert.x
        try (Image t = libvips.thumbnail(TestLibvips.portrait(), 50, ThumbnailOptions.DEFAULT)) {
            assertThat(t.width()).isEqualTo(50);
        }
    }

    @Test
    void operationsRefuseAnIoThread() {
        byte[] jpeg = TestLibvips.portrait();
        Path file = TestLibvips.portraitPath();
        Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
        calls.put("Libvips.load", () -> libvips.load(jpeg));
        calls.put("Libvips.load ", () -> libvips.load(jpeg, LoadOptions.DEFAULT));
        calls.put("Libvips.load  ", () -> libvips.load(file));
        calls.put("Libvips.load   ", () -> libvips.load(file, LoadOptions.DEFAULT));
        calls.put("Libvips.loaderFor", () -> libvips.loaderFor(jpeg));
        calls.put("Libvips.loaderFor ", () -> libvips.loaderFor(file));
        calls.put("Libvips.thumbnail", () -> libvips.thumbnail(jpeg, 50, ThumbnailOptions.DEFAULT));
        calls.put("Libvips.thumbnail ", () -> libvips.thumbnail(file, 50, ThumbnailOptions.DEFAULT));
        calls.put("Image.call", () -> image.call("resize", Map.of("scale", 0.5)));
        calls.put("Image.autorotate", () -> image.autorotate());
        calls.put("Image.thumbnail", () -> image.thumbnail(50, ThumbnailOptions.DEFAULT));
        calls.put("Image.resize", () -> image.resize(0.5));
        calls.put("Image.crop", () -> image.crop(0, 0, 10, 10));
        calls.put("Image.smartCrop", () -> image.smartCrop(10, 10, Interesting.CENTRE));
        calls.put("Image.iccImport", () -> image.iccImport());
        calls.put("Image.iccTransform", () -> image.iccTransform("srgb", Intent.RELATIVE));
        calls.put("Image.save", () -> image.save(SaveOptions.jpeg(80)));
        long open = libvips.openImages();
        onIoThread();
        calls.forEach((method, call) -> assertThatThrownBy(call).as(method)
                .isInstanceOf(BlockingOperationNotAllowedException.class)
                .hasMessageStartingWith(method.strip() + " was called on an I/O thread")
                .hasMessageContaining("@Blocking")
                .hasMessageContaining("executeBlocking")
                .hasMessageContaining("emitOn(Infrastructure.getDefaultWorkerPool())"));
        assertThat(libvips.openImages()).as("nothing was loaded").isEqualTo(open);
    }

    @Test
    void headerReadsAndIntrospectionStayAvailable() {
        onIoThread();
        assertThat(image.width()).isEqualTo(400);
        assertThat(image.height()).isEqualTo(300);
        assertThat(image.bands()).isEqualTo(3);
        assertThat(image.pages()).isEqualTo(1);
        assertThat(image.pageHeight()).isEqualTo(300);
        assertThat(image.hasAlpha()).isFalse();
        assertThat(image.orientation()).isEqualTo(6);
        assertThat(image.interpretation()).isEqualTo("srgb");
        assertThat(image.loader()).isEqualTo("jpegload_buffer");
        assertThat(image.fields()).contains("orientation");
        assertThat(image.field("orientation")).isPresent();
        assertThat(image.intField("orientation")).hasValue(6);
        assertThat(image.doubleField("xres")).isPresent();
        assertThat(image.blobField("exif-data")).isPresent();
        assertThat(image.exif()).isNotEmpty();
        assertThat(image.iccProfile()).isPresent();
        assertThat(libvips.isAvailable()).isTrue();
        assertThat(libvips.version()).isNotNull();
        assertThat(libvips.library()).isNotBlank();
        assertThat(libvips.status().available()).isTrue();
        assertThat(libvips.openImages()).isPositive();
        assertThat(libvips.leakedImages()).isNotNegative();
        assertThat(libvips.trackedMemory()).isNotNegative();
        assertThat(libvips.hasOperation("resize")).isTrue();
        assertThat(libvips.isBlocked("resize")).isFalse();
        assertThat(libvips.saverFor(".webp")).isEqualTo(Optional.of("VipsForeignSaveWebpBuffer"));
        assertThat(libvips.saveSuffixes()).contains(".jpg");
        image.close(); // releasing never blocks on pixels
    }

    @Test
    void anotherThreadIsNotAffected() throws Exception {
        onIoThread();
        Image[] out = new Image[1];
        Thread worker = Thread.ofPlatform().start(() -> out[0] = image.resize(0.5));
        worker.join();
        try (Image r = out[0]) {
            assertThat(r.width()).isEqualTo(200);
        }
    }
}
