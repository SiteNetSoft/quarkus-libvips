Filed as smallrye/smallrye-ffm#83. The text below is as filed.

# Heap arrays are copied into a per-call arena, so a C function that keeps the pointer reads freed memory; document it, and offer a way to forbid the copy

## Versions

- SmallRye FFM 0.5 and `main` @ 1062f48, OpenJDK 25.0.5, Linux x86_64

## Reproduction (libvips)

`vips_image_new_from_buffer` does not copy its input: the image decodes pixels from that buffer later, when it is
evaluated.

```java
@Link static native MemorySegment vips_image_new_from_buffer(byte[] buf, @As(AsType.size_t) long len, String options,
        @Variadic MemorySegment end);

byte[] jpeg = Files.readAllBytes(path);
MemorySegment image = vips_image_new_from_buffer(jpeg, jpeg.length, "", MemorySegment.NULL);
vips_image_get_width(image);              // 400: the header was parsed during the call
// ... any later allocation reuses the freed temporary buffer ...
vips_image_write_to_buffer(image, ".png", out, len, MemorySegment.NULL);
// -1: "VipsJpeg: Not a JPEG file: starts with 0x4d 0x65"
```

The generated code copies the `byte[]` (or a heap `MemorySegment`) into a confined arena that closes when the call
returns, so the library keeps a dangling pointer. Here libvips noticed the corrupted data; in general the result is
silent garbage or a crash. Nothing marks the problem at the declaration.

This is the documented intent of the copy for heap data, and the fix on the caller's side is to pass a native
`MemorySegment` from an arena that lives as long as the C object. The issue is that the declaration gives no hint,
and a binding author cannot make the method refuse heap data.

## Asks

- Document in `@Link` (valid argument types) that heap arrays and heap segments are copied into memory that is freed
  when the call returns, so they must not be used for parameters the callee retains.
- An opt-out per parameter, e.g. `@Native` (or `@Retained`), with which the generated code throws
  `IllegalArgumentException` for a heap segment (and rejects primitive arrays at build time) instead of copying.

## Context

Found while binding libvips for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips);
`vips_image_new_from_buffer` and `vips_thumbnail_buffer` both retain their buffer.
