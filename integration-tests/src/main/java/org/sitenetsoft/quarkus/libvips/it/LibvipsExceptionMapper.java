package org.sitenetsoft.quarkus.libvips.it;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.LimitExceededException;
import org.sitenetsoft.quarkus.libvips.UnsupportedFormatException;

/**
 * Maps libvips failures to HTTP statuses with fixed plain-text bodies. The decision is made on the exception's type
 * (and a limit's {@code operation()}); neither the message nor libvips's {@code detail()} is returned to the client:
 * {@code detail()} can name host paths and, under concurrency, belong to another request. It is logged instead.
 */
public class LibvipsExceptionMapper {

    private static final Logger LOG = Logger.getLogger(LibvipsExceptionMapper.class);

    /** The body of a 415. */
    static final String UNSUPPORTED = "unsupported image format";

    /** The body of a 422. */
    static final String FAILED = "the image could not be processed";

    /** The body of a 400. */
    static final String REFUSED = "the request was refused";

    /**
     * {@return 415 for an unsupported (unknown, empty or blocked) format, 413 for a limit, 422 otherwise}
     * @param e the failure
     */
    @ServerExceptionMapper
    public Response libvips(LibvipsException e) {
        LOG.debugf("%s (%s): %s", e.getMessage(), e.getClass().getSimpleName(), e.detail());
        return switch (e) {
            case UnsupportedFormatException u -> text(415, UNSUPPORTED);
            // operation() is one of the two limit keys
            case LimitExceededException l -> text(413, "the input exceeds quarkus.libvips." + l.operation());
            default -> text(422, FAILED);
        };
    }

    /**
     * {@return 400 for an argument libvips or the extension refuses, such as an operation that is not available}
     * @param e the refusal
     */
    @ServerExceptionMapper
    public Response refused(IllegalArgumentException e) {
        LOG.debugf("refused: %s", e.getMessage());
        return text(400, REFUSED);
    }

    private static Response text(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN_TYPE).entity(message).build();
    }
}
