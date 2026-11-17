package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
