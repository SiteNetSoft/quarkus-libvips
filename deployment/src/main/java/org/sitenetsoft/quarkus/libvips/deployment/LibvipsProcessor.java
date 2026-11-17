package org.sitenetsoft.quarkus.libvips.deployment;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsBuildTimeConfig;
import org.sitenetsoft.quarkus.libvips.LibvipsRecorder;
import org.sitenetsoft.quarkus.libvips.internal.LibvipsNative;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.FfmDowncallBuildItem;
import io.quarkus.deployment.builditem.nativeimage.FfmType;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

/**
 * Build steps of the libvips extension.
 */
class LibvipsProcessor {

    /** Name of the extension feature. */
    static final String FEATURE = "libvips";
    /** The readiness check, by name: loading the class would need the health API, which is optional. */
    static final String HEALTH_CHECK = "org.sitenetsoft.quarkus.libvips.health.LibvipsHealthCheck";

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
    ServiceStartBuildItem init(LibvipsRecorder recorder, BeanContainerBuildItem beans,
            ShutdownContextBuildItem shutdown, LaunchModeBuildItem launchMode) {
        recorder.init(beans.getValue(), shutdown, launchMode.getLaunchMode());
        return new ServiceStartBuildItem(FEATURE);
    }

    /**
     * Register the readiness check when the application has {@code quarkus-smallrye-health}; it is left out when
     * {@code quarkus.libvips.health.enabled} is false.
     *
     * @param config the build-time configuration
     * @param capabilities the application's capabilities
     * @param health the health check producer
     */
    @BuildStep(onlyIf = IsEnabled.class)
    void health(LibvipsBuildTimeConfig config, Capabilities capabilities, BuildProducer<HealthBuildItem> health) {
        if (capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            health.produce(new HealthBuildItem(HEALTH_CHECK, config.health().enabled()));
        }
    }

    /**
     * The native-image downcall registrations, derived from {@link LibvipsNative#DOWNCALLS} so that they cannot drift
     * from the bindings: one item per distinct shape (with its variadic index, none today). No linker option is used.
     * {@code NativeDescriptorsTest} checks every C function has its shape here.
     */
    static final List<FfmDowncallBuildItem> DOWNCALLS = LibvipsNative.DOWNCALLS.values().stream()
            .map(LibvipsProcessor::item)
            .distinct()
            .toList();

    /**
     * Classes whose static state must not be built into the image heap: the linked method handles and symbols, the
     * library lookup, the error-buffer access, the process-global start state and the {@code Cleaner} (its thread)
     * of {@code NativeImage}. Quarkus initialises every other class at build time, so any class that gains static
     * native state (a {@code MemorySegment}, {@code Arena}, {@code SymbolLookup}, bound {@code MethodHandle} or
     * {@code Cleaner}, or a field holding one) must be listed here.
     */
    static final List<String> RUNTIME_INITIALIZED = List.of(
            "org.sitenetsoft.quarkus.libvips.internal.LibvipsNative",
            "org.sitenetsoft.quarkus.libvips.internal.LibraryLoader",
            "org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer",
            "org.sitenetsoft.quarkus.libvips.internal.VipsRuntime",
            "org.sitenetsoft.quarkus.libvips.internal.NativeImage");

    /**
     * Register the downcall shapes for native image.
     *
     * @param downcalls the downcall producer
     */
    @BuildStep(onlyIf = IsEnabled.class)
    void nativeForeignAccess(BuildProducer<FfmDowncallBuildItem> downcalls) {
        DOWNCALLS.forEach(downcalls::produce);
    }

    /**
     * Register the classes with static native state for run-time initialisation, also when the extension is disabled:
     * the classes stay on the class path, and an application may still reach them.
     *
     * @param runtimeInit the run-time initialisation producer
     */
    @BuildStep
    void runtimeInitialized(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        RUNTIME_INITIALIZED.forEach(c -> runtimeInit.produce(new RuntimeInitializedClassBuildItem(c)));
    }

    /** {@return the registration for one C function: its shape, and its first variadic argument when it has one} */
    static FfmDowncallBuildItem item(LibvipsNative.Downcall downcall) {
        FunctionDescriptor fd = downcall.descriptor();
        FfmType returnType = fd.returnLayout().map(LibvipsProcessor::ffmType).orElse(FfmType.VOID);
        FfmType[] parameterTypes = fd.argumentLayouts().stream().map(LibvipsProcessor::ffmType)
                .toArray(FfmType[]::new);
        FfmDowncallBuildItem.Builder builder = FfmDowncallBuildItem.builder(returnType, parameterTypes);
        if (downcall.firstVariadic() >= 0) {
            builder.firstVariadicArg(downcall.firstVariadic());
        }
        return builder.build();
    }

    /**
     * The registration models what the bindings use: pointers, {@code int}, {@code long}, {@code double}; anything
     * else fails the build.
     *
     * @param layout an argument or return layout
     * @return its native-image type
     */
    static FfmType ffmType(MemoryLayout layout) {
        if (layout instanceof AddressLayout) {
            return FfmType.ADDRESS;
        }
        if (layout instanceof ValueLayout v) {
            if (v.carrier() == int.class) {
                return FfmType.INT;
            }
            if (v.carrier() == long.class) {
                return FfmType.LONG;
            }
            if (v.carrier() == double.class) {
                return FfmType.DOUBLE;
            }
        }
        throw new IllegalStateException("no native-image registration modelled for layout " + layout);
    }
}
