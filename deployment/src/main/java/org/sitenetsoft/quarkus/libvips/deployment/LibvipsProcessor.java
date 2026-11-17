package org.sitenetsoft.quarkus.libvips.deployment;

import java.util.function.BooleanSupplier;

import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsBuildTimeConfig;
import org.sitenetsoft.quarkus.libvips.LibvipsRecorder;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;

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

    /**
     * Register the {@link Libvips} bean.
     *
     * @return the bean, unremovable (applications may only look it up programmatically)
     */
    @BuildStep(onlyIf = IsEnabled.class)
    AdditionalBeanBuildItem bean() {
        return AdditionalBeanBuildItem.builder().setUnremovable().addBeanClasses(Libvips.class).build();
    }

    /**
     * Start libvips at run-time init and attach it to the bean.
     *
     * @param recorder the recorder
     * @param beans the bean container
     * @param shutdown where the stop task is registered
     * @param launchMode the launch mode ({@code vips_shutdown} only in production)
     * @return the service start
     */
    @BuildStep(onlyIf = IsEnabled.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem init(LibvipsRecorder recorder, BeanContainerBuildItem beans, ShutdownContextBuildItem shutdown,
            LaunchModeBuildItem launchMode) {
        recorder.init(beans.getValue(), shutdown, launchMode.getLaunchMode());
        return new ServiceStartBuildItem(FEATURE);
    }
}
