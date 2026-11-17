package org.sitenetsoft.quarkus.libvips;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.sitenetsoft.quarkus.libvips.internal.ErrorBuffer;
import org.sitenetsoft.quarkus.libvips.internal.Settings;

@EnabledIf("org.sitenetsoft.quarkus.libvips.test.LibvipsAvailable#isAvailable")
class ConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 40;

    @Test
    void failuresAndSuccessesInterleave() throws Exception {
        Libvips libvips = TestLibvips.start(Settings.defaults());
        byte[] good = TestLibvips.portrait();
        var strict = new LoadOptions(Access.RANDOM, Optional.of(FailOn.TRUNCATED), 0, 1);
        byte[] bad = TestLibvips.truncatedPortrait();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        List<String> failedIn = new CopyOnWriteArrayList<>();
        AtomicInteger expectedFailures = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                boolean failing = t % 2 == 1;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(30, TimeUnit.SECONDS)) { // every thread starts at once
                        throw new AssertionError("start gate not opened");
                    }
                    for (int n = 0; n < ROUNDS; n++) {
                        try (Image i = libvips.load(failing ? bad : good, failing ? strict : LoadOptions.DEFAULT);
                                Image th = i.thumbnail(64, ThumbnailOptions.DEFAULT)) {
                            byte[] out = th.save(SaveOptions.webp(70));
                            if (failing) {
                                unexpected.add(new AssertionError("truncated input saved"));
                            } else if (!new String(out, 8, 4, US_ASCII).equals("WEBP")) {
                                unexpected.add(new AssertionError("not webp"));
                            }
                        } catch (LibvipsException e) {
                            if (!failing) {
                                unexpected.add(e);
                            } else if (e.operation() == null || e.operation().isBlank()
                                    || e.operation().contains("load")) {
                                unexpected.add(e); // must be thumbnail_image or webpsave_buffer
                            } else {
                                failedIn.add(e.operation());
                                expectedFailures.incrementAndGet();
                            }
                        } catch (Throwable e) {
                            unexpected.add(e);
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(unexpected).isEmpty();
        assertThat(expectedFailures.get()).isEqualTo(THREADS / 2 * ROUNDS);
        assertThat(failedIn).allMatch(op -> op.equals("thumbnail_image") || op.equals("webpsave_buffer"));
        assertThat(libvips.openImages()).isZero();
        ErrorBuffer.take(); // drain; not asserted empty (warnings from lenient paths may remain)
    }
}
