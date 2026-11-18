package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.libvips.LimitExceededException;

/**
 * Native output that a Java byte array cannot hold (a save of more than about 2 GiB, such as an uncompressed TIFF of a
 * maximum-size image) is refused as a limit, never as {@link IllegalStateException} from the copy. Needs no libvips:
 * the length is checked before the native memory is read.
 */
class OutputSizeTest {

    @Test
    void outputLargerThanAByteArrayIsALimit() {
        long tooLarge = LibvipsNative.MAX_OUTPUT_BYTES + 1;
        assertThatThrownBy(() -> LibvipsNative.copy(MemorySegment.NULL, tooLarge))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.operation()).isEqualTo(LimitExceededException.OUTPUT_SIZE);
                    assertThat(e.actual()).isEqualTo(tooLarge);
                    assertThat(e.limit()).isEqualTo(LibvipsNative.MAX_OUTPUT_BYTES);
                    assertThat(e.detail()).isEmpty();
                    assertThat(e).hasMessage("the output has 2147483640 bytes, more than the 2147483639 a Java byte"
                            + " array can hold");
                });
        assertThatThrownBy(() -> LibvipsNative.copy(MemorySegment.NULL, 5L << 30))
                .isInstanceOf(LimitExceededException.class).hasMessageStartingWith("the output has 5368709120 bytes");
        assertThatThrownBy(() -> LibvipsNative.copy(MemorySegment.NULL, -1))
                .isInstanceOf(LimitExceededException.class); // a size_t above Long.MAX_VALUE reads as negative
    }

    @Test
    void theLimitIsTheLargestArrayTheCopyCanMake() {
        // MemorySegment.toArray refuses more than Integer.MAX_VALUE - 8 elements
        assertThat(LibvipsNative.MAX_OUTPUT_BYTES).isEqualTo(Integer.MAX_VALUE - 8);
    }

    @Test
    void smallOutputsAreCopied() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment data = a.allocateFrom(JAVA_BYTE, new byte[] { 1, 2, 3 });
            assertThat(LibvipsNative.copy(MemorySegment.ofAddress(data.address()), 3)).containsExactly(1, 2, 3);
            assertThat(LibvipsNative.copy(MemorySegment.NULL, 0)).isEmpty();
        }
    }
}
