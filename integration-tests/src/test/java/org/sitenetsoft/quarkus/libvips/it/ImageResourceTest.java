package org.sitenetsoft.quarkus.libvips.it;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The extension inside a REST application: real image work, the security guards and the readiness check. Runs in
 * JVM mode here and against the native executable through {@link ImageResourceIT}.
 */
@QuarkusTest
@EnabledIf("org.sitenetsoft.quarkus.libvips.it.LibvipsAvailable#isAvailable")
class ImageResourceTest {

    @Test
    void thumbnailAsWebp() {
        byte[] out = thumbnail("webp", "image/webp");
        assertThat(new String(out, 8, 4, US_ASCII)).isEqualTo("WEBP");
        // the 400 × 300 portrait is turned upright (300 × 400), then cropped to the 200 × 200 box
        given().contentType("application/octet-stream").body(out).post("/images/info").then().statusCode(200)
                .body("width", is(200)).body("height", is(200)).body("loader", is("webpload_buffer"))
                .body("orientation", is(1));
    }

    @Test
    void thumbnailAsJpeg() {
        byte[] out = thumbnail("jpeg", "image/jpeg");
        assertThat(out).startsWith(0xFF, 0xD8, 0xFF);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LIBVIPS_EXPECT_AVIF", matches = "true")
    void thumbnailAsAvif() {
        byte[] out = thumbnail("avif", "image/avif");
        assertThat(new String(out, 4, 8, US_ASCII)).isEqualTo("ftypavif");
    }

    @Test
    void info() {
        given().contentType("application/octet-stream").body(portrait()).post("/images/info").then().statusCode(200)
                .body("width", is(400)).body("height", is(300)).body("orientation", is(6))
                .body("loader", is("jpegload_buffer")).body("interpretation", is("srgb")).body("icc", is(true));
    }

    @Test
    void garbageIs415() {
        given().contentType("application/octet-stream").body(new byte[] { 1, 2, 3, 4 }).post("/images/info").then()
                .statusCode(415).body(is(LibvipsExceptionMapper.UNSUPPORTED));
    }

    @Test
    void corruptJpegIs422WithFixedText() {
        // a JPEG signature, then garbage: the loader fails; libvips's text must not reach the client
        byte[] corrupt = new byte[256];
        corrupt[0] = (byte) 0xFF;
        corrupt[1] = (byte) 0xD8;
        corrupt[2] = (byte) 0xFF;
        corrupt[3] = (byte) 0xE0;
        for (int i = 4; i < corrupt.length; i++) {
            corrupt[i] = (byte) (i * 7);
        }
        given().contentType("application/octet-stream").body(corrupt).post("/images/info").then().statusCode(422)
                .body(is(LibvipsExceptionMapper.FAILED));
    }

    @Test
    void ppmIs415() { // NO_LOADER either way: no ppmload_buffer before 8.17, blocked (so not sniffed) from 8.17
        given().contentType("application/octet-stream").body(ppm()).post("/images/info").then().statusCode(415)
                .body(is(LibvipsExceptionMapper.UNSUPPORTED));
    }

    @Test
    void oversizeIs413() {
        given().contentType("application/octet-stream").body(new byte[100 * 1024]).post("/images/info").then()
                .statusCode(413).body(containsString("quarkus.libvips.limits.max-input-size"));
    }

    @Test
    void decompressionBombIs413() {
        // a few dozen bytes declaring 100000 × 100000 pixels: refused at the header with the default pixel limit
        byte[] bomb = pngHeader(100_000, 100_000);
        for (String path : new String[] { "/images/info", "/images/thumbnail" }) {
            given().contentType("application/octet-stream").body(bomb).queryParam("width", 50)
                    .queryParam("height", 50).queryParam("format", "png").post(path).then().statusCode(413)
                    .body(containsString("quarkus.libvips.limits.max-input-pixels"));
        }
    }

    @Test
    void systemIsBlocked() {
        given().queryParam("operation", "system").get("/images/blocked").then().statusCode(200)
                .body("blocked", is(true));
    }

    @Test
    void blockedNeedsAnOperation() {
        given().get("/images/blocked").then().statusCode(400).body(is(LibvipsExceptionMapper.REFUSED));
    }

    @Test
    void systemCannotRun() {
        given().contentType("application/octet-stream").body(portrait()).post("/images/system").then()
                .statusCode(400).body(is(LibvipsExceptionMapper.REFUSED));
    }

    @Test
    void readiness() {
        given().get("/q/health/ready").then().statusCode(200)
                .body("checks.find { it.name == 'libvips' }.status", is("UP"));
    }

    private static byte[] thumbnail(String format, String contentType) {
        return given().contentType("application/octet-stream").body(portrait())
                .queryParam("width", 200).queryParam("height", 200).queryParam("format", format)
                .post("/images/thumbnail").then().statusCode(200).contentType(contentType).extract().asByteArray();
    }

    static byte[] portrait() {
        try (InputStream in = ImageResourceTest.class.getResourceAsStream("/portrait.jpg")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A 2 × 2 binary PPM. */
    static byte[] ppm() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("P6\n2 2\n255\n".getBytes(US_ASCII));
        out.writeBytes(new byte[12]);
        return out.toByteArray();
    }

    /** A PNG signature, an IHDR for an 8-bit RGB image of the given size, an empty IDAT and IEND. */
    static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' });
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[0]);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
