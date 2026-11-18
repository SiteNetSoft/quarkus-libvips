package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.libvips.LibvipsBuildTimeConfig;

import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

/**
 * The processor in isolation (no application: the class is package-private, so it is tested from the same class
 * loader).
 */
class LibvipsProcessorTest {

    @Test
    void featureName() {
        assertThat(new LibvipsProcessor().feature().getName()).isEqualTo("libvips");
    }

    @Test
    void libvipsBeanIsUnremovable() {
        var bean = new LibvipsProcessor().bean();
        assertThat(bean.getBeanClasses()).containsExactly("org.sitenetsoft.quarkus.libvips.Libvips");
        assertThat(bean.isRemovable()).isFalse();
    }

    @Test
    void nativeAccessIsEnabledForTheExtensionWhenEnabled() throws NoSuchMethodException {
        assertThat(new LibvipsProcessor().nativeAccess().moduleName()).isEqualTo("org.sitenetsoft.quarkus.libvips");
        assertThat(LibvipsProcessor.class.getDeclaredMethod("nativeAccess")
                .getAnnotation(io.quarkus.deployment.annotations.BuildStep.class).onlyIf())
                .containsExactly(LibvipsProcessor.IsEnabled.class);
    }

    @Test
    void noHealthCheckWithoutTheHealthExtension() {
        assertThat(health(Set.of(), true)).isEmpty();
    }

    @Test
    void healthCheckRegisteredWithTheHealthExtension() {
        assertThat(health(Set.of(Capability.SMALLRYE_HEALTH), true)).singleElement().satisfies(h -> {
            assertThat(h.getHealthCheckClass()).isEqualTo("org.sitenetsoft.quarkus.libvips.health.LibvipsHealthCheck");
            assertThat(h.isEnabled()).isTrue();
        });
        assertThat(health(Set.of(Capability.SMALLRYE_HEALTH), false)).singleElement()
                .satisfies(h -> assertThat(h.isEnabled()).isFalse());
    }

    private static List<HealthBuildItem> health(Set<String> capabilities, boolean enabled) {
        List<HealthBuildItem> produced = new ArrayList<>();
        new LibvipsProcessor().health(config(enabled), new Capabilities(capabilities), produced::add);
        return produced;
    }

    private static LibvipsBuildTimeConfig config(boolean healthEnabled) {
        return new LibvipsBuildTimeConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Health health() {
                return () -> healthEnabled;
            }
        };
    }
}
