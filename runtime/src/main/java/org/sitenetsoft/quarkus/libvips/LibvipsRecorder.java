package org.sitenetsoft.quarkus.libvips;

import java.util.List;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.libvips.internal.Settings;
import org.sitenetsoft.quarkus.libvips.internal.VipsRuntime;

import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.configuration.ConfigurationException;

/**
 * Starts libvips at RUNTIME_INIT and attaches it to the {@link Libvips} bean.
 * <p>
 * Any failure (a missing or too old library, an unknown block-list entry, anything unexpected) stops the start-up
 * with a {@link ConfigurationException}, unless {@code quarkus.libvips.required=false}:
 * then one WARN, and the application runs without libvips. Shutdown marks the runtime stopped and empties the
 * operation cache; {@code vips_shutdown} runs only in production ({@link LaunchMode#NORMAL}), because libvips cannot be
 * initialised again in the same process (dev-mode restarts, test applications).
 */
@Recorder
public class LibvipsRecorder {

    private static final Logger LOG = Logger.getLogger(LibvipsRecorder.class);

    private final RuntimeValue<LibvipsConfig> config;

    /**
     * @param config the run-time configuration
     */
    public LibvipsRecorder(RuntimeValue<LibvipsConfig> config) {
        this.config = config;
    }

    /**
     * Starts libvips.
     *
     * @param beans the bean container
     * @param shutdown where the stop task is registered
     * @param mode the launch mode
     */
    public void init(BeanContainer beans, ShutdownContext shutdown, LaunchMode mode) {
        LibvipsConfig cfg = config.getValue();
        Libvips libvips = beans.beanInstance(Libvips.class);
        VipsRuntime runtime;
        try {
            runtime = VipsRuntime.start(settings(cfg));
        } catch (RuntimeException | LinkageError e) {
            String reason = reason(e);
            if (cfg.required()) {
                throw new ConfigurationException("libvips could not be started: " + reason, e);
            }
            LOG.warnf("libvips is not available, the application runs without it (quarkus.libvips.required=false): %s",
                    reason);
            libvips.attach(VipsRuntime.unavailable(reason));
            return;
        }
        libvips.attach(runtime);
        boolean shutdownLibrary = mode == LaunchMode.NORMAL;
        shutdown.addShutdownTask(() -> runtime.stop(shutdownLibrary));
        VipsRuntime.Effective e = runtime.effective();
        LOG.infof("libvips %s (%s): concurrency %d, cache %d operations / %d bytes / %d files, block-untrusted %s, "
                + "heif-load-blocked %s", runtime.version(), runtime.library(), e.concurrency(),
                e.cacheMaxOperations(), e.cacheMaxMemory(), e.cacheMaxFiles(), runtime.settings().blockUntrusted(),
                runtime.heifLoadBlocked());
    }

    /**
     * {@return why the start failed, for the operator} A {@link LibvipsUnavailableException}'s message is fixed; the
     * reason (with the library paths and directories tried) is its {@link LibvipsException#detail()}.
     */
    private static String reason(Throwable e) {
        if (e instanceof LibvipsException l && !l.detail().isBlank()) {
            return l.detail().strip();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /**
     * {@return the configuration in plain values} Memory sizes become bytes; {@code 0} stays "no limit".
     *
     * @param cfg the run-time configuration
     */
    static Settings settings(LibvipsConfig cfg) {
        return new Settings(cfg.library(), cfg.concurrency(), cfg.cache().maxOperations(),
                cfg.cache().maxMemory().asLongValue(), cfg.cache().maxFiles(), cfg.limits().maxInputPixels(),
                cfg.limits().maxInputSize().asLongValue(), cfg.failOn(), cfg.security().blockUntrusted(),
                cfg.security().blockedOperations().orElse(List.of()),
                cfg.security().allowedOperations().orElse(List.of()));
    }
}
