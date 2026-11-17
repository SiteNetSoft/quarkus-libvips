Filed as smallrye/smallrye-ffm#79; fix in PR #86. The text below is as filed.

# Variadic arguments narrower than `int` (`boolean`, `byte`, `short`, `char`) fail to link: default argument promotions are not applied

## Versions

- SmallRye FFM 0.5 and `main` @ 1062f48, OpenJDK 25.0.5, Linux x86_64

## Reproduction

```java
@Link static native int printf(String fmt, @Variadic short v);
@Link(name = "printf") static native int printfBool(String fmt, @Variadic boolean v);
@Link(name = "printf") static native int printfByte(String fmt, @Variadic byte v);
@Link(name = "printf") static native int printfChar(String fmt, @Variadic char v);

printf("%d%n", (short) 7);
```

```
short:   IllegalArgumentException: Invalid variadic argument layout: s2
boolean: IllegalArgumentException: Invalid variadic argument layout: z1
byte:    IllegalArgumentException: Invalid variadic argument layout: b1
char:    IllegalArgumentException: Return type mismatch
             Expected: (MemorySegment,MemorySegment,char)int
             Was: (MemorySegment,MemorySegment,int)int
```

(each wrapped in a `BootstrapMethodError` at the first call).

`float` works, because `Bootstraps.layoutOf` and `inputConvOf` already apply the promotion to `double` when
`variadic` is true.

## Expected

C passes variadic arguments with the default argument promotions: anything narrower than `int` (including `_Bool`)
is passed as `int`. The JDK linker therefore rejects narrower variadic layouts, and SmallRye FFM should promote them
the way it already promotes `float`: `boolean` -> `int` 0/1, `byte`/`short` sign-extended, `char` (u16) zero-extended.

## Possible fix (PR candidate)

`api/src/main/java/io/smallrye/ffm/Bootstraps.java`:

- `layoutOf(desc, offs, variadic)`: when `variadic`, return `JAVA_INT` for `sB`, `sc`, `sS`, `ss`, `c` and `Z` (the
  unsigned narrow cases already map to `JAVA_INT`).
- `inputConvOf(..., variadic)`: when `variadic`, use the 32-bit conversions (`inputConvOfS32` / `inputConvOfU32`)
  for those descriptors, so that `z2i`, `b2i`, `s2i`, `c2i` produce the `int`.

Test: `printf` (or `snprintf` into a buffer) with each type.

## Real-world case

GLib's `gboolean` is an `int`, so a libvips option list such as `vips_icc_transform(in, &out, "srgb", "embedded",
TRUE, NULL)` declared with `@Variadic String k, boolean embedded` fails; `@As(AsType.stdc_int) boolean` works.
That workaround is fine for `gboolean`, but a C `bool`/`short` passed through `...` has no workaround other than
declaring it `int`.

## Context

Found while binding libvips for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips).
