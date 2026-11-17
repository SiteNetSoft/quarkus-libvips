package org.sitenetsoft.quarkus.libvips.deployment;

/**
 * Makes the next libvips start fail with an {@link IllegalStateException}: the process-wide record of libvips's
 * default concurrency is set to a value that is not a number. Used as the test extension's before-all and after-all
 * customizers, which run in the test's own class loader (the application class loader cannot see this class).
 */
final class MalformedDefaultConcurrency {

    static final String PROPERTY = "org.sitenetsoft.quarkus.libvips.default-concurrency";

    private static String previous;

    private MalformedDefaultConcurrency() {
    }

    static void set() {
        previous = System.getProperty(PROPERTY);
        System.setProperty(PROPERTY, "not-a-number");
    }

    static void restore() {
        if (previous == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
    }
}
