package org.sitenetsoft.quarkus.libvips.deployment;

import java.util.function.BooleanSupplier;

import org.sitenetsoft.quarkus.libvips.LibvipsBuildTimeConfig;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Build steps of the libvips extension.
 */
class LibvipsProcessor {

    /** Name of the extension feature. */
    static final String FEATURE = "libvips";

    /** Create the processor. */
    LibvipsProcessor() {
    }

    /** {@code quarkus.libvips.enabled}. */
    static final class IsEnabled implements BooleanSupplier {
        private final LibvipsBuildTimeConfig config;

        IsEnabled(LibvipsBuildTimeConfig config) {
            this.config = config;
        }

        @Override
        public boolean getAsBoolean() {
            return config.enabled();
        }
    }

    /**
     * Register the {@code libvips} feature.
     *
     * @return the feature build item
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
