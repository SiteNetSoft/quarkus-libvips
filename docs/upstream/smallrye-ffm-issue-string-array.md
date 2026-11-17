Filed as smallrye/smallrye-ffm#82. The text below is as filed.

# `String[]` parameters are accepted by the plugin and fail at the first call; no mapping for `const char **` out-parameters or NULL-terminated string arrays

## Versions

- SmallRye FFM 0.5 and `main` @ 1062f48, OpenJDK 25.0.5, Linux x86_64

## Reproduction

```java
@Link(name = "strlen") static native @As(AsType.size_t) long strlenArray(String[] v);
@Link static native int vips_image_get_string(MemorySegment image, String name, @Out String[] out);
```

Both compile and are transformed without a warning; the first call fails:

```
java.lang.BootstrapMethodError: bootstrap method initialization exception
Caused by: java.lang.IllegalArgumentException: Invalid type class [Ljava.lang.String; for native descriptor type sI
```

`Generator.defaultAsType` falls back to `"s32"` for every type it does not know, so an unsupported parameter type
becomes an `int` argument and is only reported at link time, with a message that does not name the parameter.

## Expected

1. The plugin rejects parameter and return types it cannot map, at build time, naming the method and the parameter
   (instead of the `s32` fallback). The same applies to other reference types (`Object`, `List`, ...).
2. Feature request: `String[]` support, which C APIs use in two shapes:
   - `@Out String[] out` for a `const char **` out-parameter (one element; the callee stores a pointer to a string it
     owns). Generated code: allocate one `ADDRESS`, call, read it back with a NULL check and `getString`.
     Example: libvips `int vips_image_get_string(const VipsImage *, const char *name, const char **out)`.
   - `String[]` in, as a NULL-terminated `char **` (`execv`, `g_strv_length`, GLib `gchar **` arguments): copy each
     element with the call's allocator and append NULL. The reverse, a returned NULL-terminated `gchar **`
     (`vips_image_get_fields`, freed with `g_strfreev`), would need an ownership policy, so `MemorySegment` is fine
     there.

## Workaround

`@Out MemorySegment[] out` (supported since "Add support for Java arrays of pointers") and
`out[0].reinterpret(Long.MAX_VALUE).getString(0)`, which works on 0.5 and `main`.

## Context

Found while binding libvips for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips).
