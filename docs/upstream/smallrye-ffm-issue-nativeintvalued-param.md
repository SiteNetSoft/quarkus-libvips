Filed as smallrye/smallrye-ffm#78; fix in PR #85. The text below is as filed.

# `NativeIntValued` parameters fail to link: "Return type mismatch ... Was: (MemorySegment,NativeIntValued)int"

## Versions

- SmallRye FFM 0.5 and `main` @ 1062f48, OpenJDK 25.0.5, Linux x86_64

## Reproduction

The `NativeIntValued` javadoc says objects of this type may be passed into a native method, converted as
`stdc_int` by default. With any concrete type (an `enum` here):

```java
public enum Level implements NativeIntValued {
    LOW, HIGH;
    public int nativeValue() { return ordinal() - 5; }
    public boolean isPresent() { return true; }
}

@Link static native int abs(Level v);

abs(Level.LOW);
```

```
java.lang.BootstrapMethodError: bootstrap method initialization exception
Caused by: java.lang.IllegalArgumentException: Return type mismatch
		Expected: (MemorySegment,Level)int
		Was: (MemorySegment,NativeIntValued)int
```

The same happens in a variadic position (`@Variadic Level v`).

## Cause

`Bootstraps.inputConvOfS32` (and the other `inputConvOf*` methods) return `n2i(NativeIntValued)I`, and `downcall`
applies it with `MethodHandles.filterArguments`; the resulting handle takes `NativeIntValued`, not the declared
parameter type, so the final `handle.type().equals(type)` check fails. (The message says "Return type mismatch"
although the mismatch is in a parameter.)

## Possible fix (PR candidate)

In `Bootstraps.downcall`, adapt each argument converter to the declared parameter type before collecting it:

```java
MethodHandle conv = inputConvOf(descStr, k, type.parameterType(param), isVar);
if (conv != null && conv.type().parameterType(0) != type.parameterType(param)) {
    conv = conv.asType(conv.type().changeParameterType(0, type.parameterType(param)));
}
```

and reword the final message to "Type mismatch". A test with an `enum implements NativeIntValued` passed to `abs`
covers it.

## Real-world case

libvips takes C enums everywhere (`VipsInteresting`, `VipsSize`, `VipsIntent`, `VipsAccess`); a Java enum per C enum
is the natural binding, and it is what `NativeIntValued` promises. Today they have to be passed as `int`.

## Context

Found while binding libvips for quarkus-libvips (https://github.com/SiteNetSoft/quarkus-libvips).
