package org.sitenetsoft.quarkus.libvips.health;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.sitenetsoft.quarkus.libvips.Libvips;

/**
 * Readiness check {@code libvips}: UP with the libvips version, the file name of the library it was opened by and the
 * effective settings (data {@code version}, {@code library}, {@code concurrency}, {@code cache.max-operations},
 * {@code cache.max-memory}, {@code cache.max-files}, {@code block-untrusted}, {@code heif-load-blocked},
 * {@code open-images}, {@code tracked-memory}). With {@code quarkus.libvips.required=false} and no usable library the
 * application is meant to serve without libvips, so the check stays UP with {@code available=false} and a fixed
 * {@code error}; the reason in detail (directories tried, loader errors) is in the startup log only, because the health
 * endpoint is often reachable without authentication. For the same reason {@code library} is only the file name
 * ({@code libvips.so.42}), never the directory, when {@code quarkus.libvips.library} or the search found a path. No
 * image operation runs in the check: it reads settings and counters only. Registered only when
 * {@code quarkus-smallrye-health} is present and {@code quarkus.libvips.health.enabled} is true.
 */
@Readiness
@ApplicationScoped
public class LibvipsHealthCheck implements HealthCheck {

    /** The check's name. */
    public static final String NAME = "libvips";

    /** The {@code error} when libvips is unavailable. */
    static final String UNAVAILABLE = "libvips is unavailable; see the startup log";

    private final Libvips libvips;

    LibvipsHealthCheck(Libvips libvips) {
        this.libvips = libvips;
    }

    @Override
    public HealthCheckResponse call() {
        Libvips.Status s = libvips.status();
        HealthCheckResponseBuilder b = HealthCheckResponse.named(NAME).up().withData("available", s.available());
        if (!s.available()) {
            return b.withData("error", UNAVAILABLE).build();
        }
        s.version().ifPresent(v -> b.withData("version", v.toString()));
        s.library().ifPresent(l -> b.withData("library", fileName(l)));
        return b.withData("concurrency", s.concurrency())
                .withData("cache.max-operations", s.cacheMaxOperations())
                .withData("cache.max-memory", s.cacheMaxMemory())
                .withData("cache.max-files", s.cacheMaxFiles())
                .withData("block-untrusted", s.blockUntrusted())
                .withData("heif-load-blocked", s.heifLoadBlocked())
                .withData("open-images", s.openImages())
                .withData("tracked-memory", s.trackedMemory())
                .build();
    }

    /** {@return the last component of a library path, or the name unchanged when it has no directory} */
    static String fileName(String library) {
        return library.substring(library.lastIndexOf('/') + 1);
    }
}
