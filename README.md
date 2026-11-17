# quarkus-libvips

Quarkus extension by SiteNetSoft for image processing with [libvips](https://www.libvips.org/), calling the library through the Java Foreign Function & Memory API (`java.lang.foreign`). Today the repository holds the extension skeleton (runtime and deployment modules, the `libvips` feature, build-time configuration). Planned: loading images, header, EXIF and ICC metadata, autorotate, thumbnail, resize, crop, colour profiles and saving to JPEG, PNG, WebP and AVIF, in JVM mode, dev mode and native image, with limits for untrusted uploads. The system library libvips (8.15 or later) will be required and is never bundled.

Status: work in progress. Nothing is published yet.
