Filed as smallrye/smallrye-ffm#80; fix in PR #87. The text below is as filed.

# `@Lib` cannot name a versioned shared library (`libz.so.1`, `libvips.so.42`)

## Versions

- SmallRye FFM 0.5 and `main` @ 1062f48, OpenJDK 25.0.5, Linux x86_64 (Debian/Kali)

## Reproduction

```java
@Lib("libz.so.1")
public class Z {
    @Link static native String zlibVersion();
}
```

- 0.5: the plugin fails the build: `Execution ffm of goal ...:transform failed: Invalid member name: libz.so.1`
  (the library name was the name of a dynamic constant; `main` passes it as a static argument since #64, so this part
  is fixed there).
- `main`: the build passes, the first call fails:

  ```
  java.lang.BootstrapMethodError: bootstrap method initialization exception
  Caused by: java.lang.IllegalArgumentException: Cannot open library: liblibz.so.1.so
  ```

  `Bootstraps.libraryLookup` applies `System.mapLibraryName` to every name that is not an absolute path.

## Why it matters

On Linux distributions the unversioned `libfoo.so` link belongs to the development package (`zlib1g-dev`,
`libvips-dev`); a runtime image has only the soname (`libz.so.1`, `libvips.so.42`). So:

- `@Lib("vips")` works on a developer machine and fails in production containers;
- `@Lib("/usr/lib/x86_64-linux-gnu/libvips.so.42")` is distribution- and architecture-specific;
- `@Lib("libvips.so.42")` is what the dynamic linker expects (`dlopen("libvips.so.42")`, as
  `SymbolLookup.libraryLookup(String, Arena)` does) but cannot be expressed.

The workaround on `main` is no `@Lib` at all plus `System.load(<path found at run time>)` before the first call
(the `<<LOADER>>` lookup, fixed by #64; 0.5 still resolves `<<LOADER>>` through `defaultLookup`, so a 0.5 user has no
working option for a runtime-only image).

## Expected / possible fix (PR candidate)

In `Bootstraps.libraryLookup(MethodHandles.Lookup, String, Class, Arena, SymbolLookup)`: use the name verbatim, not
`System.mapLibraryName(name)`, when it already looks like a file name, e.g. when it contains `.so`, ends with `.dylib`
or `.dll`, or starts with `lib` and contains a `.`; try each registered search path with the verbatim name first. An
explicit alternative that avoids guessing: `@Lib(value = "libvips.so.42", mapped = false)`.

Releasing #64 (the `<<LOADER>>` fix) matters as much: for libraries whose location is only known at run time (a
configured path), `System.load` + `<<LOADER>>` is the only way to point `@Link` methods at them.

## Context

Found while binding libvips for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips), whose runtime
images install only `libvips42t64` (Debian) without the `-dev` package.
