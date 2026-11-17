package org.sitenetsoft.quarkus.libvips;

/**
 * A libvips version.
 *
 * @param major the major version
 * @param minor the minor version
 * @param micro the micro (patch) version
 */
public record Version(int major, int minor, int micro) {

    /**
     * {@return whether this version is {@code major.minor} or later}
     *
     * @param major the major version to compare with
     * @param minor the minor version to compare with
     */
    public boolean atLeast(int major, int minor) {
        return this.major > major || this.major == major && this.minor >= minor;
    }

    /** {@return {@code major.minor.micro}} */
    @Override
    public String toString() {
        return major + "." + minor + "." + micro;
    }
}
