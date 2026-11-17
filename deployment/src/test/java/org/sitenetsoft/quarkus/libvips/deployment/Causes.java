package org.sitenetsoft.quarkus.libvips.deployment;

final class Causes {

    private Causes() {
    }

    /** {@return the first throwable of the type in the cause chain, or null} */
    static <T extends Throwable> T find(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
