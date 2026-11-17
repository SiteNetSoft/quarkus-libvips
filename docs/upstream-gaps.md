# Upstream gaps

Things the extension works around that would be better fixed upstream. Each entry: what we needed, what is
missing, what we did instead, and the API we would have liked. Numbers are stable (the code and the design refer to
them); they are written "gap N", never "#N", which always means an upstream SmallRye FFM issue or PR.

The new items are filed in smallrye/smallrye-ffm: #77 (gap 4, fix in PR #84), #78 (gap 5, PR #85), #79 (gap 6,
PR #86), #80 (gap 3, PR #87), #81 (gap 2), #82 (gap 7), #83 (gap 8), and gap 9 as a comment on #34. The texts as
filed are kept in `docs/upstream/`.

The extension calls libvips through **plain `java.lang.foreign`**, as quarkus-modsecurity does: the released
SmallRye FFM 0.5 cannot be linked into a native image (gap 1). The binding class is the seam; the extension moves
to SmallRye FFM once a release fixes **gaps 1, 2 and 3**. Those three block the switch; gaps 4–9 have workarounds
that work the same with SmallRye FFM (described under each), so fixing them would only remove code.

Tested with SmallRye FFM 0.5 (Maven Central) and `main` @ 1062f48 (built locally), OpenJDK 25.0.5, libvips 8.18.7
(Debian/Kali `libvips42t64`), Linux x86_64. On HotSpot, SmallRye FFM handles the whole libvips subset the extension
needs (load from file and buffer, header and EXIF fields, autorotate, ICC import/transform, thumbnail with crop,
save to WebP/JPEG/AVIF buffers, error buffer, reference counting, and the `GValue` operation API), with the
declarations below. The table also records the variadic shapes that were tested; the extension itself makes no
variadic calls (gap 10).

| C shape | SmallRye FFM declaration | Status |
|---|---|---|
| `VipsImage *` in/out, `GObject *` | `MemorySegment` | works |
| `const char *` argument | `String` | works (but not `null`, gap 4) |
| `const char *` return owned by the library | `String` | works only when non-NULL and < 1024 bytes (gap 4) |
| `char *` return the caller must `g_free` | `MemorySegment` + `g_free` | works |
| `VipsImage **out`, `void **buf`, `const void **data` | `@Out MemorySegment[]` | works |
| `size_t *len` | `@Out long[]` | works |
| `int *out` | `@Out int[]` | works |
| `const char **out` | `@Out MemorySegment[]` + `getString` | works (`@Out String[]`: gap 7) |
| NULL-terminated `gchar **` return | `MemorySegment` | works (manual walk, `g_strfreev`) |
| `GType` (`gsize`) | `@As(AsType.size_t) long` | works |
| `gboolean` argument/return | `@As(AsType.stdc_int) boolean` | works (plain `boolean` is `_Bool`: wrong width) |
| C enum argument | `int` | works (`NativeIntValued` enum: gap 5) |
| variadic option list, `int`/`const char *`/pointer, NULL end | `@Variadic` + one method per shape | works |
| variadic `boolean`/`short`/`byte`/`char` | — | fails to link (gap 6) |
| `GValue *` (struct by pointer) | `MemorySegment` + own `StructLayout` | works (gap 9) |
| buffer the callee retains (`vips_image_new_from_buffer`) | native `MemorySegment` only | `byte[]` is a use-after-free (gap 8) |
| library by soname (`libvips.so.42`) | `System.load` + no `@Lib` (`main` only) | `@Lib` cannot (gap 3) |
| native image | — | blocked (gaps 1, 2) |
| library by soname, without `System.load` | — | blocked (gap 3) |

## 1. SmallRye FFM 0.5: no native image (upstream issue #70, PR #71)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** a native executable of an application using the extension (Quarkus native, built in CI).
- **Gap:** `Bootstraps` links conversion helpers it never declares; native-image fails while parsing
  `Bootstraps.downcall`, whatever types the bindings use. Reported by quarkus-modsecurity: smallrye/smallrye-ffm#70,
  fix in PR #71 (in review). Not repeated here.
- **Workaround:** plain `java.lang.foreign` (`Linker.downcallHandle` per call shape; no variadic calls, gap 10).
- **Ask:** merge and release #71.

## 2. SmallRye FFM: no native-image registration for the linked shapes (upstream issue #81)

- **Library:** `smallrye-ffm-maven-plugin` 0.5 and `main`.
- **Need:** every downcall shape registered for native-image (`FfmDowncallBuildItem`), and the
  reflection/run-time-initialization the bootstrap needs.
- **Gap:** the plugin encodes each shape in the `invokedynamic` name (`(**sI~*sI*sI*sI*)sI`) but writes no metadata.
  Binding libvips through its variadic convenience functions took 54 call sites over 30 distinct shapes, 9 of
  them variadic, and every new option combination of a variadic function is a new shape (the figures in #81). The
  extension avoids variadic calls (gap 10), which keeps its shapes few and fixed, but they still need registering.
  (quarkus-modsecurity's gap 3 describes the same need; it is now filed as #81.)
- **Workaround:** the binding class holds one `FunctionDescriptor` per call shape in a single map (none variadic:
  every descriptor's first-variadic index is -1); the deployment processor turns that map into
  `FfmDowncallBuildItem`s, so the registration cannot drift from the handles.
- **API we would have liked:** `META-INF/native-image/<groupId>/<artifactId>/reachability-metadata.json` written by
  the `transform` goal (`foreign.downcalls` + the bootstrap's reflection entries), or a per-class shape list a
  Quarkus extension can read.
- **Ask:** smallrye/smallrye-ffm#81 (text: `docs/upstream/smallrye-ffm-issue-native-metadata.md`).

## 3. SmallRye FFM: `@Lib` cannot name a versioned library; 0.5 cannot see `System.load` (upstream issue #80, PR #87)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** link against `libvips.so.42`. Runtime images have only the soname; `libvips.so` comes with
  `libvips-dev`. The library location may also be configured at run time.
- **Gap:** `@Lib("libvips.so.42")`: 0.5 fails the build (`Invalid member name: libvips.so.42`); `main` maps it to
  `liblibvips.so.42.so` and fails at the first call. `@Lib("vips")` works only where the `-dev` package is installed;
  an absolute path is distribution-specific (and rejected by 0.5 too). On 0.5, `<<LOADER>>` resolves through
  `defaultLookup`, so `System.load` does not help either (quarkus-modsecurity's gap 2; fixed by merged PR #64,
  not released).
- **Workaround:** plain FFM: `SymbolLookup.libraryLookup("libvips.so.42" or a configured path, Arena.global())`;
  the GLib/GObject symbols resolve through the same handle (dependencies of libvips).
  With SmallRye FFM `main` the only route is no `@Lib` and `System.load(path)` before the first call. The extension
  does not use `System.load`: a library loaded that way belongs to one class loader, and a dev-mode restart (a new
  class loader) cannot load it again. **This gap blocks the switch** until #80 (PR #87) is released; releasing #64
  alone still needs `System.load`.
- **API we would have liked:** names that already look like file names used verbatim
  (`dlopen("libvips.so.42")`), or `@Lib(value = "libvips.so.42", mapped = false)`.
- **Ask:** smallrye/smallrye-ffm#80, fix in PR #87 (text: `docs/upstream/smallrye-ffm-issue-lib-soname.md`);
  release #64.

## 4. SmallRye FFM: `String` and NULL; 1024-byte cap on returned strings (upstream issue #77, PR #84)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** `const char *` returns that may be NULL (`vips_foreign_find_load`, `g_type_name`) or long
  (`vips_error_buffer` accumulates every error: 2370 bytes after 20 failed loads); NULL `const char *` arguments.
- **Gap:** a NULL return mapped to `String` **crashes the JVM** (SIGSEGV reading address 0); a return over 1024
  bytes throws `IndexOutOfBoundsException: No null terminator found`; a `null` `String` argument throws
  `NullPointerException`. Also no way to declare a returned `char *` the caller must `g_free`.
- **Workaround:** plain FFM returns `MemorySegment` everywhere; a helper reads it with a NULL check and
  `reinterpret(Long.MAX_VALUE).getString(0)`, and frees it when the function's contract says so.
- **API we would have liked:** NULL ↔ `null` in both directions, no length cap, and later `@Free("g_free") String`.
- **Ask:** smallrye/smallrye-ffm#77, fix in PR #84 (text:
  `docs/upstream/smallrye-ffm-issue-string-null-and-length.md`).

## 5. SmallRye FFM: `NativeIntValued` parameters fail to link (upstream issue #78, PR #85)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** C enums (`VipsInteresting`, `VipsSize`, `VipsIntent`, `VipsAccess`) as Java enums.
- **Gap:** an enum implementing `NativeIntValued`, documented as a valid argument, fails at the first call:
  `IllegalArgumentException: Return type mismatch ... Expected: (MemorySegment,Level)int Was:
  (MemorySegment,NativeIntValued)int` (fixed and variadic positions alike).
- **Workaround:** the extension passes no C enum value as a Java enum. Each Java enum carries libvips's nick
  (`centre`, `down`, ...); the binding resolves it with `vips_enum_from_nick` against the `GType` that the argument's
  `GParamSpec` reports, and sets the resulting `int` in a `GValue` (`g_value_set_enum`). No C value is hard-coded,
  and a nick this libvips lacks is refused before the call. With SmallRye FFM that is an `int` parameter, which works.
- **Ask:** smallrye/smallrye-ffm#78, fix in PR #85 (`asType` on the converter; text:
  `docs/upstream/smallrye-ffm-issue-nativeintvalued-param.md`).

## 6. SmallRye FFM: variadic arguments narrower than `int` are not promoted (upstream issue #79, PR #86)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** `gboolean` options in libvips option lists (`"embedded", TRUE`) when calling the variadic convenience
  functions. The extension does not call them (gap 10); the gap matters for bindings of variadic C APIs.
- **Gap:** `@Variadic boolean` fails with `Invalid variadic argument layout: z1` (`short`: `s2`, `byte`: `b1`, `char`:
  a type mismatch). `float` is promoted to `double`; the narrow integer types are not.
- **Workaround:** `gboolean` is a C `int`, so `@As(AsType.stdc_int) boolean` (or `int`) works; plain FFM uses
  `JAVA_INT`. The extension sets options as `GValue`s instead (gap 10).
- **Ask:** smallrye/smallrye-ffm#79, fix in PR #86 (in `layoutOf`/`inputConvOf`; text:
  `docs/upstream/smallrye-ffm-issue-variadic-promotion.md`).

## 7. SmallRye FFM: no `String[]`; unsupported types reach run time (upstream issue #82)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** `const char **out` (`vips_image_get_string`), NULL-terminated string arrays.
- **Gap:** `String[]` (in or `@Out`) is transformed without complaint, then fails at the first call:
  `Invalid type class [Ljava.lang.String; for native descriptor type sI` (unknown types default to `s32`).
- **Workaround:** `@Out MemorySegment[]` and `getString` (works on 0.5 and `main`); plain FFM: an `ADDRESS` slot.
- **Ask:** smallrye/smallrye-ffm#82 (text: `docs/upstream/smallrye-ffm-issue-string-array.md`): build-time
  rejection of unsupported types, then `@Out String[]` and NULL-terminated `String[]`.

## 8. SmallRye FFM: heap data is copied into a per-call arena (upstream issue #83)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** `vips_image_new_from_buffer` and `vips_thumbnail_buffer` keep the buffer pointer (they wrap it in a
  `VipsBlob` without copying) and decode from it later.
- **Gap:** a `byte[]` or heap `MemorySegment` argument is copied into memory freed when the call returns; libvips then
  decodes freed memory (in our test: `VipsJpeg: Not a JPEG file: starts with 0x4d 0x65`). Documented behaviour for
  the copy, but nothing at the declaration warns or prevents it.
- **Workaround:** the extension never hands libvips memory it does not own. An input `byte[]` is copied into a
  native segment of a per-call confined arena, then `vips_blob_copy` copies it into memory libvips owns; the blob is
  set on the operation as a `GValue` and the arena closes when the call returns. libvips's blob lives as long as
  the operation and the images derived from it. That costs one extra copy and works the same with SmallRye FFM.
- **Ask:** smallrye/smallrye-ffm#83 (text: `docs/upstream/smallrye-ffm-issue-heap-copy-retained.md`): document it;
  an opt-out annotation that rejects heap data.

## 9. SmallRye FFM: structs (upstream issue #34)

- **Library:** SmallRye FFM 0.5 and `main`.
- **Need:** GLib `GValue` (24 bytes, a `GType` and a two-element union, zero-initialized) by pointer for the
  operation API (`g_value_init`, `g_object_set_property`, `g_object_get_property`).
- **Gap:** no struct types; smallrye/smallrye-ffm#34 (struct layouts) is open and in design.
- **Workaround:** a hand-written `StructLayout` and `MemorySegment` parameters; works on 0.5, `main` and plain FFM.
  The binding also reads GLib structs by offset, which a struct API would declare instead (LP64 offsets):
  - `GParamSpec.value_type` at 24, and the bounds of `GParamSpecInt` (`minimum` 72, `maximum` 76) and
    `GParamSpecDouble` (`minimum` 72, `maximum` 80), for the range shown when a value fails validation and for
    the height limit of a thumbnail;
  - the `GType` (first field) of the `GValue` that `g_param_spec_get_default_value` returns, as the argument's type;
  - `GTypeInstance.g_class` (first word of every object), the class handed to `g_object_class_find_property`.
- **Ask:** posted as a comment on smallrye/smallrye-ffm#34 with this use case (unions, zeroed allocation; text:
  `docs/upstream/smallrye-ffm-comment-34-gvalue.md`).

## 10. libvips variadic API: fixed shapes per declaration (no upstream ask)

- **Need:** any combination of the optional arguments of an operation (thumbnail alone has ten).
- **Gap:** a C variadic call has a fixed shape per downcall in both SmallRye FFM and plain FFM; each option
  combination is its own declaration and its own native-image registration (gap 2). libvips's binding guide
  recommends the `GValue` operation API (`vips_operation_new` + `g_object_set_property` +
  `vips_cache_operation_build`) for bindings, which needs a small fixed set of non-variadic functions.
- **Decision:** the extension makes no variadic calls: the `GValue` operation API replaces the variadic convenience
  functions, and every descriptor's first-variadic index is -1. Nothing to ask upstream.

## 11. SmallRye FFM: upcalls (upstream issue #72, draft PR #76)

Not needed: the subset uses no libvips callbacks (progress signals are GObject signals, out of scope). Listed so the
return to SmallRye FFM does not depend on it.

## 12. `smallrye-ffm-maven-plugin`: `transform-test` without test classes (upstream issue #73, PR #75)

Applies once the extension uses the plugin, in any module built with `-Dmaven.test.skip` or without tests. Not
repeated here.
