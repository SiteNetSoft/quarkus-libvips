package org.sitenetsoft.quarkus.libvips.it;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.LimitExceededException;
import org.sitenetsoft.quarkus.libvips.UnsupportedFormatException;

/** Maps libvips failures to HTTP statuses, with the exception's message as a plain-text body. */
public class LibvipsExceptionMapper {

    /**
     * {@return 415 for an unsupported (unknown, empty or blocked) format, 413 for a limit, 422 otherwise}
     * @param e the failure
     */
    @ServerExceptionMapper
    public Response libvips(LibvipsException e) {
        int status = switch (e) {
            case UnsupportedFormatException u -> 415;
            case LimitExceededException l -> 413;
            default -> 422;
        };
        return text(status, e.getMessage());
    }

    /**
     * {@return 400 for an argument libvips or the extension refuses, such as an operation that is not available}
     * @param e the refusal
     */
    @ServerExceptionMapper
    public Response refused(IllegalArgumentException e) {
        return text(400, e.getMessage());
    }

    private static Response text(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN_TYPE).entity(message).build();
    }
}
