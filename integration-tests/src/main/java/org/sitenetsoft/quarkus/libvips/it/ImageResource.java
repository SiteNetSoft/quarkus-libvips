package org.sitenetsoft.quarkus.libvips.it;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Interesting;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.SaveOptions;
import org.sitenetsoft.quarkus.libvips.ThumbnailOptions;

/** A small image service over the extension: thumbnails, header information, and the security guards. */
@Path("/images")
public class ImageResource {

    @Inject
    Libvips libvips;

    /**
     * Thumbnails an uploaded image into a box, cropping the centre, and encodes it.
     *
     * @param body the encoded image
     * @param width the box width
     * @param height the box height
     * @param format {@code webp}, {@code jpeg}, {@code avif} or {@code png}
     * @return the encoded thumbnail, typed {@code image/<format>}
     */
    @POST
    @Path("/thumbnail")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response thumbnail(byte[] body, @QueryParam("width") int width, @QueryParam("height") int height,
            @QueryParam("format") String format) {
        SaveOptions options = switch (format) {
            case "webp" -> SaveOptions.webp(80);
            case "jpeg" -> SaveOptions.jpeg(85);
            case "avif" -> SaveOptions.avif(50);
            case "png" -> SaveOptions.png();
            case null, default -> throw new IllegalArgumentException("format must be webp, jpeg, avif or png");
        };
        try (Image thumbnail = libvips.thumbnail(body, width, ThumbnailOptions.box(height, Interesting.CENTRE))) {
            return Response.ok(thumbnail.save(options), "image/" + format).build();
        }
    }

    /**
     * Describes an uploaded image from its header.
     *
     * @param body the encoded image
     * @return the size, orientation, loader, interpretation and whether an ICC profile is embedded
     */
    @POST
    @Path("/info")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    public Info info(byte[] body) {
        try (Image image = libvips.load(body)) {
            return new Info(image.width(), image.height(), image.orientation(), image.loader(),
                    image.interpretation(), image.iccProfile().isPresent());
        }
    }

    /**
     * Whether libvips blocks an operation.
     *
     * @param operation a nickname or class name
     * @return the operation and its state
     * @throws IllegalArgumentException (400) when {@code operation} is missing
     */
    @GET
    @Path("/blocked")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> blocked(@QueryParam("operation") String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("the operation query parameter is required");
        }
        return Map.of("operation", operation, "blocked", libvips.isBlocked(operation));
    }

    /**
     * Tries to run libvips's {@code system} operation, which runs a shell command, on an uploaded image; the
     * extension always refuses it.
     *
     * @param body the encoded image
     * @return never returns normally
     */
    @POST
    @Path("/system")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response system(byte[] body) {
        try (Image image = libvips.load(body); Image out = image.call("system", Map.of("cmd_format", "true"))) {
            return Response.serverError().entity("the system operation ran").build();
        }
    }

    /**
     * The header information of an image.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param orientation the EXIF orientation, 1 to 8
     * @param loader the loader nickname
     * @param interpretation the interpretation nick
     * @param icc whether an ICC profile is embedded
     */
    public record Info(int width, int height, int orientation, String loader, String interpretation, boolean icc) {
    }
}
