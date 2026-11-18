package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

/** {@code quarkus.libvips.leak-check} runs in dev and test mode only; production ignores it. Needs no libvips. */
class LibvipsRecorderTest {

    @Test
    void leakCheckRunsInDevAndTestOnly() {
        assertThat(LibvipsRecorder.leakCheck(true, LaunchMode.DEVELOPMENT)).isTrue();
        assertThat(LibvipsRecorder.leakCheck(true, LaunchMode.TEST)).isTrue();
        assertThat(LibvipsRecorder.leakCheck(true, LaunchMode.NORMAL)).isFalse();
        for (LaunchMode mode : LaunchMode.values()) {
            assertThat(LibvipsRecorder.leakCheck(false, mode)).as(mode.name()).isFalse();
        }
    }
}
