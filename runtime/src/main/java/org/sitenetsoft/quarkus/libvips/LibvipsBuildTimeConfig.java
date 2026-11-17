package org.sitenetsoft.quarkus.libvips;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration of the libvips extension.
 */
@ConfigMapping(prefix = "quarkus.libvips")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface LibvipsBuildTimeConfig {

    /**
     * Whether the extension is enabled at all.
     *
     * @return true when enabled
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Health check settings.
     *
     * @return the health settings
     */
    Health health();

    /**
     * Health check settings.
     */
    interface Health {
        /**
         * Whether the readiness check is registered (when the application has {@code quarkus-smallrye-health}).
         *
         * @return true when enabled
         */
        @WithDefault("true")
        boolean enabled();
    }
}
