package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * An application with the extension starts and lists the {@code libvips} feature.
 */
class ExtensionStartsTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.libvips.required", "false")
            .setLogRecordPredicate(r -> r.getMessage() != null && r.getMessage().contains("Installed features"))
            .assertLogRecords(records -> assertThat(records)
                    .anySatisfy(r -> assertThat(r.getParameters()).anySatisfy(
                            p -> assertThat(String.valueOf(p)).containsPattern("(^|, )libvips(,|$)"))));

    @Test
    void applicationStarts() {
        // the start-up and the log assertion run around this test
    }
}
