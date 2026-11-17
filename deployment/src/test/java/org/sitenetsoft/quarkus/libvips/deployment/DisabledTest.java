package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Libvips;

import io.quarkus.arc.Arc;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.FfmDowncallBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code quarkus.libvips.enabled=false}: no bean, nothing loaded, no downcall registered; the classes with static
 * native state are still initialised at run time.
 */
class DisabledTest {

    /** System properties carry what the build saw: the build and the test method run in different class loaders. */
    private static final String RUNTIME_INIT = "libvips.test.disabled.runtime-init";
    private static final String DOWNCALLS = "libvips.test.disabled.downcalls";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication()
            .overrideConfigKey("quarkus.libvips.enabled", "false")
            .addBuildChainCustomizer(chain -> chain.addBuildStep(context -> {
                System.setProperty(RUNTIME_INIT, context.consumeMulti(RuntimeInitializedClassBuildItem.class).stream()
                        .map(RuntimeInitializedClassBuildItem::getClassName).collect(Collectors.joining(",")));
                System.setProperty(DOWNCALLS,
                        Integer.toString(context.consumeMulti(FfmDowncallBuildItem.class).size()));
                context.produce(new FeatureBuildItem("libvips-disabled-test"));
            }).consumes(RuntimeInitializedClassBuildItem.class).consumes(FfmDowncallBuildItem.class)
                    .produces(FeatureBuildItem.class).build());

    @Test
    void noBean() {
        assertThat(Arc.container().instance(Libvips.class).isAvailable()).isFalse();
    }

    @Test
    void classesWithNativeStateStayRuntimeInitialized() {
        // named here: the package-private processor cannot be reached from the application's class loader
        assertThat(List.of(System.getProperty(RUNTIME_INIT, "").split(","))).contains(
                "org.sitenetsoft.quarkus.libvips.internal.LibvipsNative",
                "org.sitenetsoft.quarkus.libvips.internal.LibraryLoader",
                "org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer",
                "org.sitenetsoft.quarkus.libvips.internal.VipsRuntime",
                "org.sitenetsoft.quarkus.libvips.internal.NativeImage");
        assertThat(System.getProperty(DOWNCALLS)).isEqualTo("0");
    }
}
