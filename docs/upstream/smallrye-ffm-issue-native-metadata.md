Filed as smallrye/smallrye-ffm#81. The text below is as filed.

# Generate the GraalVM native-image registration for the call shapes the plugin links

## Versions

- SmallRye FFM 0.5 and `main` @ 1062f48; GraalVM / Mandrel native-image 25 (JDK 25)

## Problem

native-image compiles one stub per downcall shape at build time; a shape that is not registered fails at run time.
Registration is the `foreign` section of `META-INF/native-image/<group>/<artifact>/reachability-metadata.json`
(GraalVM 25), or in Quarkus the `FfmDowncallBuildItem` build item, with the `Linker.Option`s that change the stub:
`firstVariadicArg`, `captureCallState`, `critical`.

The Maven plugin knows every shape it links (it encodes them in the `invokedynamic` names, e.g. `(**sI~*sI*sI*sI*)sI`)
but writes nothing for native-image, so every consumer re-derives the list by hand from `javap -v` output and must
keep it in sync with the bindings. Variadic C APIs make this worse: each combination of optional arguments is its
own shape. The libvips subset we bind has 54 C call sites over 30 distinct shapes, 9 of them variadic
(`vips_thumbnail_image(in, &out, width, "height", int, "crop", int, "size", int, NULL)` is `firstVariadicArg(3)`
over ten arguments, `(**sI~*sI*sI*sI*)sI` in the plugin's notation).

The call-site bootstrap also needs reflection metadata that is not documented: `Bootstraps.downcall` calls
`Linker.downcallHandle(FunctionDescriptor, Linker.Option[])` through `lookup.findVirtual`, and `Bootstraps`
(its `<clinit>` queries `Linker.nativeLinker().canonicalLayouts()` and creates an errno downcall) should be
initialized at run time.

(Separate from #70 / #71, which is about `Bootstraps` itself not getting through native-image analysis.)

## Ask

- The `transform` goal writes `META-INF/native-image/<groupId>/<artifactId>/reachability-metadata.json` with one
  `foreign.downcalls` entry per distinct shape (return and parameter layouts, `firstVariadicArg`, `captureCallState`,
  `critical`) plus the reflection entries the bootstrap needs. Optional via a parameter if some users do not want it.
- Or, at least, a machine-readable list of the shapes (one JSON/properties file per transformed class) that a
  framework extension can turn into its own registration (`FfmDowncallBuildItem` in Quarkus).

## Context

Found while binding libvips for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips), and before that
quarkus-modsecurity (https://github.com/SiteNetSoft/quarkus-modsecurity), which both register their shapes by hand
today.
