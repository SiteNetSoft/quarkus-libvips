Filed as smallrye/smallrye-ffm#77; fix in PR #84. The text below is as filed.

# `String` return values: a NULL return crashes the JVM, and returns longer than 1024 bytes throw; a `null` `String` argument throws instead of passing NULL

## Versions

- SmallRye FFM 0.5 (released jar and plugin) and `main` @ 1062f48
- OpenJDK 25.0.5 (HotSpot), Linux x86_64

## Reproduction

```java
public class Repro {
    @Link static native String getenv(String name);
    @Link static native String setlocale(int category, String locale);

    public static void main(String[] args) {
        getenv("SURELY_UNSET_VARIABLE");   // (1)
        getenv("LONG_VAR");                // (2) run with LONG_VAR set to 2000 characters
        setlocale(6 /* LC_ALL, glibc */, null); // (3)
    }
}
```

1. The function returns NULL. The generated code reads a C string at address 0, and the JVM dies:

   ```
   #  SIGSEGV (0xb) ...
   # Problematic frame:
   # J ... jdk.internal.misc.Unsafe.getLongUnaligned(Ljava/lang/Object;J)J
   j  jdk.internal.foreign.AbstractMemorySegmentImpl.getString(J)Ljava/lang/String;
   j  Repro.getenv(Ljava/lang/String;)Ljava/lang/String;
   ```

2. The string is longer than 1024 bytes:

   ```
   java.lang.IndexOutOfBoundsException: No null terminator found:
     MemorySegment{ kind: native, address: 0x7ffe3ad34346, byteSize: 1024 } using region [0, 1024)
   ```

3. `null` for a `String` parameter:

   ```
   java.lang.NullPointerException
   ```

   C APIs often take NULL for a `const char *` parameter (a query, a default, or the NULL that ends a variadic
   list), so today the only way to declare such a parameter is `MemorySegment` and to copy the string by hand.

## Where

`maven-plugin/src/main/java/io/smallrye/ffm/maven/Generator.java`:

- `StringResultStep.call` (around line 1505): `reinterpret(1024)` ("assume a max byte length of 1024") followed by
  `getString(0)`, with no NULL check.
- `StringArgumentStep.call` (around line 1320): `allocator.allocateFrom(str)` on the argument, which throws for `null`.

## Real-world cases

From libvips: `vips_foreign_find_load()` returns NULL for a file no loader accepts, `g_type_name(0)` returns NULL, and
`vips_error_buffer()` returns the whole accumulated error log, which passes 1024 bytes after a few failures (2370
bytes after 20 failed loads in our test). Mapping any of them to `String` crashes or throws, so they have to be
declared as `MemorySegment` and read with `reinterpret(Long.MAX_VALUE).getString(0)` after an explicit NULL check.

## Expected

- A NULL `const char *` return becomes `null`.
- No fixed length cap: read up to the terminator (`reinterpret(Long.MAX_VALUE)`, as the JDK documentation suggests
  for C strings of unknown length).
- A `null` `String` argument is passed as NULL (`MemorySegment.NULL`).

## Possible fix (PR candidate)

In `Generator`:

- `StringResultStep.call`: after the downcall, `dup`, compare with `MemorySegment.NULL` (or `address() == 0`), and
  branch to `aconst_null`; otherwise `reinterpret(Long.MAX_VALUE)` instead of `1024L`.
- `StringArgumentStep.call`: `aload`, `ifnull` -> load `MemorySegment.NULL`, else `allocateFrom` as today.

Tests: the three calls above against libc (`getenv`, `setlocale`).

A related, larger idea for later: returned strings that the caller must free (`char *` from `g_strdup`, `strdup`,
`vips_error_buffer_copy`) can only be declared as `MemorySegment` today. Something like
`@Free("g_free") String f(...)` (copy, then call the named function on the pointer) would cover them.

## Context

Found while binding libvips (https://www.libvips.org) for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips).
