package org.sitenetsoft.quarkus.libvips;

/**
 * libvips's {@code VipsAccess}: how a loader will be read.
 */
public enum Access implements VipsEnum {
    /** Any order (libvips's default): the image is decoded whole when pixels are first needed. */
    RANDOM("random"),
    /** Top to bottom, once: lower memory for a single streaming pass such as a thumbnail or a save. */
    SEQUENTIAL("sequential");

    private final String nick;

    Access(String nick) {
        this.nick = nick;
    }

    @Override
    public String nick() {
        return nick;
    }
}
