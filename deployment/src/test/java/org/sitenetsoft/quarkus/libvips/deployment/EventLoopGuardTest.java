package org.sitenetsoft.quarkus.libvips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.ThumbnailOptions;

import io.quarkus.runtime.BlockingOperationNotAllowedException;
import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

/**
 * In a Quarkus application, a libvips operation on a Vert.x event loop is refused with Quarkus's own
 * {@link BlockingOperationNotAllowedException}; on a worker thread it runs. State reads run on the event loop.
 */
@EnabledIf("org.sitenetsoft.quarkus.libvips.deployment.LibvipsAvailable#isAvailable")
class EventLoopGuardTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withEmptyApplication();

    @Inject
    Libvips libvips;

    @Inject
    Vertx vertx;

    private static byte[] portrait() throws Exception {
        return Files.readAllBytes(Path.of("../runtime/src/test/resources/portrait.jpg"));
    }

    @Test
    void thumbnailOnTheEventLoopIsRefused() throws Exception {
        byte[] jpeg = portrait();
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        vertx.runOnContext(v -> {
            try (Image t = libvips.thumbnail(jpeg, 50, ThumbnailOptions.DEFAULT)) {
                outcome.complete(new AssertionError("ran on the event loop, width " + t.width()));
            } catch (Throwable e) {
                outcome.complete(Context.isOnEventLoopThread() ? e
                        : new AssertionError("not on an event loop", e));
            }
        });
        Throwable e = outcome.get(30, TimeUnit.SECONDS);
        assertThat(e).isInstanceOf(BlockingOperationNotAllowedException.class)
                .hasMessageStartingWith("Libvips.thumbnail was called on an I/O thread")
                .hasMessageContaining("@Blocking");
        assertThat(libvips.openImages()).isZero();
    }

    @Test
    void thumbnailOnAWorkerRuns() throws Exception {
        byte[] jpeg = portrait();
        int width = vertx.executeBlocking(() -> {
            try (Image t = libvips.thumbnail(jpeg, 50, ThumbnailOptions.DEFAULT)) {
                return t.width();
            }
        }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(width).isEqualTo(50);
    }

    @Test
    void stateReadsRunOnTheEventLoop() throws Exception {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        vertx.runOnContext(v -> {
            try {
                outcome.complete(Context.isOnEventLoopThread() && libvips.status().available()
                        && libvips.hasOperation("resize"));
            } catch (Throwable e) {
                outcome.completeExceptionally(e);
            }
        });
        assertThat(outcome.get(30, TimeUnit.SECONDS)).isTrue();
    }
}
