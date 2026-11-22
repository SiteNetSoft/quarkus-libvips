package org.sitenetsoft.quarkus.libvips;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

/**
 * {@code quarkus.libvips.leak-check} runs in dev and test mode, and in production only with
 * {@code quarkus.libvips.leak-check-in-production}. Needs no libvips.
 */
class LibvipsRecorderTest {

    @Test
    void leakCheckRunsInDevAndTestOnly() {
        assertThat(LibvipsRecorder.leakCheck(true, false, LaunchMode.DEVELOPMENT)).isTrue();
        assertThat(LibvipsRecorder.leakCheck(true, false, LaunchMode.TEST)).isTrue();
        assertThat(LibvipsRecorder.leakCheck(true, false, LaunchMode.NORMAL)).isFalse();
        for (LaunchMode mode : LaunchMode.values()) {
            assertThat(LibvipsRecorder.leakCheck(false, false, mode)).as(mode.name()).isFalse();
        }
    }

    @Test
    void leakCheckInProductionRunsOnlyWhenAllowed() {
        assertThat(LibvipsRecorder.leakCheck(true, true, LaunchMode.NORMAL)).isTrue();
        for (LaunchMode mode : LaunchMode.values()) {
            assertThat(LibvipsRecorder.leakCheck(true, true, mode)).as(mode.name()).isTrue();
            assertThat(LibvipsRecorder.leakCheck(false, true, mode)).as(mode.name()).isFalse();
        }
    }
}
