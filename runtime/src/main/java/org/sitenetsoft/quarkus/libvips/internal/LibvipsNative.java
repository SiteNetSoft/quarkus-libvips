package org.sitenetsoft.quarkus.libvips.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;

/**
 * The libvips C API through {@code java.lang.foreign}: every C descriptor and method handle of the extension.
 * <p>
 * Internal. The class is public only so that the deployment module can derive the native-image registration from
 * {@link #DOWNCALLS}; applications must not use it. It is the seam for a later SmallRye FFM binding, once a release
 * fixes gaps 1–4 of {@code docs/upstream-gaps.md}.
 * <p>
 * {@link #DOWNCALLS} is the single source: {@link #bind(SymbolLookup)} links one handle per entry, and the deployment
 * registers one native-image downcall per distinct shape. There is no variadic downcall; options are set as
 * {@code GValue}s on an operation object. Layouts: pointers {@code ADDRESS}; {@code int}, {@code gboolean} and C enums
 * {@code JAVA_INT}; {@code size_t} and {@code GType} {@code JAVA_LONG}; {@code double} {@code JAVA_DOUBLE}.
 * <p>
 * Every wrapper that allocates uses its own confined arena, closed when the call returns; native memory that libvips
 * returns is copied to Java and freed with the matching GLib or libvips function before the wrapper returns.
 */
public final class LibvipsNative {

    /**
     * One C call shape.
     *
     * @param symbol the C function
     * @param descriptor its layouts
     * @param firstVariadic index of the first variadic argument, {@code -1} for a non-variadic function
     */
    public record Downcall(String symbol, FunctionDescriptor descriptor, int firstVariadic) {

        /** {@return the linker options for this shape: {@code firstVariadicArg} when variadic, else none} */
        public Linker.Option[] options() {
            return firstVariadic < 0 ? new Linker.Option[0]
                    : new Linker.Option[] { Linker.Option.firstVariadicArg(firstVariadic) };
        }
    }

    /** {@code struct _GValue { GType g_type; union { ... } data[2]; }} (gobject/gvalue.h), 24 bytes on LP64. */
    public static final StructLayout GVALUE = MemoryLayout.structLayout(
            JAVA_LONG.withName("g_type"),
            MemoryLayout.sequenceLayout(2, JAVA_LONG).withName("data"));

    /** Fundamental {@code GType}s (gobject/gtype.h, {@code G_TYPE_MAKE_FUNDAMENTAL(n) = n << 2}). */
    public static final long G_TYPE_BOOLEAN = 20;
    public static final long G_TYPE_INT = 24;
    public static final long G_TYPE_ENUM = 48;
    public static final long G_TYPE_FLAGS = 52;
    public static final long G_TYPE_DOUBLE = 60;
    public static final long G_TYPE_STRING = 64;
    public static final long G_TYPE_BOXED = 72;
    public static final long G_TYPE_OBJECT = 80;

    /** {@code VIPS_OPERATION_BLOCKED} of {@code VipsOperationFlags}. */
    public static final int VIPS_OPERATION_BLOCKED = 32;

    /** The C functions the extension calls, keyed by symbol, in declaration order. */
    public static final SequencedMap<String, Downcall> DOWNCALLS = downcalls();

    /** {@code vips_foreign_find_load_buffer}: every 8.18 sniffer reads at most a prefix of this size. */
    private static final int SNIFF_BYTES = 65536;

    /** Error-buffer domain for enum and flag lookups. */
    private static final String DOMAIN = "quarkus-libvips";

    private static final MemorySegment NULL = MemorySegment.NULL;

    private static void fixed(Map<String, Downcall> m, String symbol, FunctionDescriptor fd) {
        m.put(symbol, new Downcall(symbol, fd, -1));
    }

    private static SequencedMap<String, Downcall> downcalls() {
        SequencedMap<String, Downcall> m = new LinkedHashMap<>();
        // vips.h, cache.h, threadpool.h, operation.h, memory.h: lifecycle and process-global settings
        fixed(m, "vips_init", FunctionDescriptor.of(JAVA_INT, ADDRESS)); // int (const char *argv0)
        fixed(m, "vips_version", FunctionDescriptor.of(JAVA_INT, JAVA_INT)); // int (int flag)
        fixed(m, "vips_shutdown", FunctionDescriptor.ofVoid());
        fixed(m, "vips_cache_drop_all", FunctionDescriptor.ofVoid());
        fixed(m, "vips_concurrency_set", FunctionDescriptor.ofVoid(JAVA_INT));
        fixed(m, "vips_concurrency_get", FunctionDescriptor.of(JAVA_INT));
        fixed(m, "vips_cache_set_max", FunctionDescriptor.ofVoid(JAVA_INT));
        fixed(m, "vips_cache_set_max_mem", FunctionDescriptor.ofVoid(JAVA_LONG)); // size_t
        fixed(m, "vips_cache_set_max_files", FunctionDescriptor.ofVoid(JAVA_INT));
        fixed(m, "vips_cache_get_max", FunctionDescriptor.of(JAVA_INT));
        fixed(m, "vips_cache_get_size", FunctionDescriptor.of(JAVA_INT));
        fixed(m, "vips_cache_get_max_mem", FunctionDescriptor.of(JAVA_LONG)); // size_t
        fixed(m, "vips_cache_get_max_files", FunctionDescriptor.of(JAVA_INT));
        fixed(m, "vips_block_untrusted_set", FunctionDescriptor.ofVoid(JAVA_INT)); // gboolean
        fixed(m, "vips_operation_block_set", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)); // (const char *, gboolean)
        fixed(m, "vips_tracked_get_mem", FunctionDescriptor.of(JAVA_LONG)); // size_t
        // error.h
        fixed(m, "vips_error_buffer_copy", FunctionDescriptor.of(ADDRESS)); // char * (g_free), clears the buffer
        fixed(m, "vips_error_clear", FunctionDescriptor.ofVoid());
        // glib / gobject
        fixed(m, "g_free", FunctionDescriptor.ofVoid(ADDRESS));
        fixed(m, "g_strfreev", FunctionDescriptor.ofVoid(ADDRESS));
        fixed(m, "g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS));
        fixed(m, "g_object_unref", FunctionDescriptor.ofVoid(ADDRESS));
        fixed(m, "g_type_name", FunctionDescriptor.of(ADDRESS, JAVA_LONG)); // const gchar * (GType)
        fixed(m, "g_type_fundamental", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)); // GType (GType)
        fixed(m, "g_type_is_a", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG)); // gboolean (GType, GType)
        fixed(m, "g_type_from_name", FunctionDescriptor.of(JAVA_LONG, ADDRESS)); // GType (const gchar *)
        fixed(m, "g_value_init", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG)); // GValue *(GValue *, GType)
        fixed(m, "g_value_unset", FunctionDescriptor.ofVoid(ADDRESS));
        fixed(m, "g_value_set_int", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        fixed(m, "g_value_set_double", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE));
        fixed(m, "g_value_set_boolean", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        fixed(m, "g_value_set_enum", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        fixed(m, "g_value_set_flags", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)); // guint
        fixed(m, "g_value_set_string", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)); // copies the string
        fixed(m, "g_value_set_object", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        fixed(m, "g_value_set_boxed", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        fixed(m, "g_value_get_int", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "g_value_get_double", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
        fixed(m, "g_value_get_boolean", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "g_value_get_object", FunctionDescriptor.of(ADDRESS, ADDRESS)); // transfer none
        fixed(m, "g_value_get_boxed", FunctionDescriptor.of(ADDRESS, ADDRESS)); // transfer none
        fixed(m, "g_object_set_property", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        fixed(m, "g_object_get_property", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        fixed(m, "g_param_spec_get_default_value", FunctionDescriptor.of(ADDRESS, ADDRESS)); // const GValue *
        // gboolean (GParamSpec *, GValue *): TRUE when the value had to be changed to fit the GParamSpec
        fixed(m, "g_param_value_validate", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        // type.h, enumtypes.h, util.h
        fixed(m, "vips_image_get_type", FunctionDescriptor.of(JAVA_LONG));
        fixed(m, "vips_blob_get_type", FunctionDescriptor.of(JAVA_LONG));
        fixed(m, "vips_array_double_get_type", FunctionDescriptor.of(JAVA_LONG));
        fixed(m, "vips_interpretation_get_type", FunctionDescriptor.of(JAVA_LONG));
        fixed(m, "vips_blob_copy", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG)); // (const void *, size_t)
        fixed(m, "vips_blob_get", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)); // (VipsBlob *, size_t *)
        fixed(m, "vips_area_unref", FunctionDescriptor.ofVoid(ADDRESS));
        // void (GValue *, const double *, int n)
        fixed(m, "vips_value_set_array_double", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        // int (const char *domain, GType, const char *nick)
        fixed(m, "vips_enum_from_nick", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        fixed(m, "vips_flags_from_nick", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        fixed(m, "vips_enum_nick", FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_INT)); // const char * (GType, int)
        // object.h, operation.h, cache.h
        fixed(m, "vips_type_find", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS)); // (basename, nickname)
        fixed(m, "vips_operation_get_type", FunctionDescriptor.of(JAVA_LONG));
        fixed(m, "vips_operation_new", FunctionDescriptor.of(ADDRESS, ADDRESS));
        fixed(m, "vips_operation_get_flags", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_cache_operation_build", FunctionDescriptor.of(ADDRESS, ADDRESS));
        fixed(m, "vips_object_unref_outputs", FunctionDescriptor.ofVoid(ADDRESS));
        // int (VipsObject *, const char *, GParamSpec **, VipsArgumentClass **, VipsArgumentInstance **)
        fixed(m, "vips_object_get_argument",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        fixed(m, "vips_object_get_argument_flags", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        // header.h
        fixed(m, "vips_image_get_width", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_height", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_bands", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_n_pages", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_page_height", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_orientation", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_interpretation", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_hasalpha", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        fixed(m, "vips_image_get_fields", FunctionDescriptor.of(ADDRESS, ADDRESS)); // gchar ** (g_strfreev)
        fixed(m, "vips_image_get_typeof", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS)); // 0 when absent
        // int (const VipsImage *, const char *, char **out) (g_free)
        fixed(m, "vips_image_get_as_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        fixed(m, "vips_image_get_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)); // int *out
        fixed(m, "vips_image_get_double", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)); // double *out
        // int (const VipsImage *, const char *, const void **data, size_t *length)
        fixed(m, "vips_image_get_blob", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        // foreign.h: class names (const, not freed), suffixes (g_strfreev)
        fixed(m, "vips_foreign_find_load", FunctionDescriptor.of(ADDRESS, ADDRESS));
        fixed(m, "vips_foreign_find_load_buffer", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        fixed(m, "vips_foreign_find_save_buffer", FunctionDescriptor.of(ADDRESS, ADDRESS));
        fixed(m, "vips_foreign_get_suffixes", FunctionDescriptor.of(ADDRESS));
        return Collections.unmodifiableSequencedMap(m);
    }

    /** The lookup the handles were linked from, and the handles by symbol. */
    private record Bound(SymbolLookup lookup, Map<String, MethodHandle> handles) {
    }

    private static volatile Bound bound;

    private LibvipsNative() {
    }

    // ---- lifecycle ----

    /**
     * Links every {@link #DOWNCALLS} entry against {@code lib}. Every symbol is resolved first, so a library that
     * lacks one is refused before anything is linked. Linking happens once per class loader: a later call only checks
     * that {@code lib} has every symbol and keeps the first handles (a different copy of libvips is refused by the
     * library loader before it gets here).
     *
     * @throws IllegalArgumentException {@code symbol <name> not found}
     */
    static synchronized void bind(SymbolLookup lib) {
        Map<String, MemorySegment> addresses = new LinkedHashMap<>();
        for (Downcall d : DOWNCALLS.values()) {
            addresses.put(d.symbol(), lib.find(d.symbol())
                    .orElseThrow(() -> new IllegalArgumentException("symbol " + d.symbol() + " not found")));
        }
        if (bound != null) {
            return;
        }
        Linker linker = Linker.nativeLinker();
        Map<String, MethodHandle> handles = new LinkedHashMap<>();
        DOWNCALLS.forEach((symbol, d) -> handles.put(symbol,
                linker.downcallHandle(addresses.get(symbol), d.descriptor(), d.options())));
        bound = new Bound(lib, Collections.unmodifiableMap(handles));
    }

    /** {@return whether {@link #bind(SymbolLookup)} has linked the handles in this class loader} */
    static boolean isBound() {
        return bound != null;
    }

    /**
     * {@return the address of {@code name} in the bound library}
     *
     * @throws IllegalStateException when not bound
     * @throws IllegalArgumentException when the library has no such symbol
     */
    static MemorySegment symbol(String name) {
        return bound().lookup().find(name)
                .orElseThrow(() -> new IllegalArgumentException("symbol " + name + " not found"));
    }

    /** {@code vips_init}: 0 on success; idempotent. */
    static int init(String argv0) {
        try (Arena a = Arena.ofConfined()) {
            return (int) h("vips_init").invokeExact(a.allocateFrom(argv0));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_version}: 0 major, 1 minor, 2 micro. */
    static int version(int flag) {
        try {
            return (int) h("vips_version").invokeExact(flag);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void shutdown() {
        voidCall("vips_shutdown");
    }

    /**
     * {@code vips_cache_drop_all}: for shutdown only, right before {@code vips_shutdown}. It destroys the operation
     * cache rather than emptying it, and a later {@code vips_cache_operation_build} crashes (libvips 8.15) or logs
     * GLib criticals (8.18). To empty the cache at run time, call {@link #cacheSetMax(int) cacheSetMax(0)} and then
     * restore the previous value.
     */
    static void cacheDropAll() {
        voidCall("vips_cache_drop_all");
    }

    /** {@code vips_concurrency_set}: 0 means the libvips default (which honours {@code VIPS_CONCURRENCY}). */
    static void concurrencySet(int concurrency) {
        intArg("vips_concurrency_set", concurrency);
    }

    static int concurrencyGet() {
        return intResult("vips_concurrency_get");
    }

    static void cacheSetMax(int max) {
        intArg("vips_cache_set_max", max);
    }

    static void cacheSetMaxMem(long maxMem) {
        try {
            h("vips_cache_set_max_mem").invokeExact(maxMem);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void cacheSetMaxFiles(int maxFiles) {
        intArg("vips_cache_set_max_files", maxFiles);
    }

    static int cacheGetMax() {
        return intResult("vips_cache_get_max");
    }

    /** {@code vips_cache_get_size}: operations in the cache now. */
    static int cacheGetSize() {
        return intResult("vips_cache_get_size");
    }

    static long cacheGetMaxMem() {
        return longResult("vips_cache_get_max_mem");
    }

    static int cacheGetMaxFiles() {
        return intResult("vips_cache_get_max_files");
    }

    static void blockUntrustedSet(boolean state) {
        intArg("vips_block_untrusted_set", state ? 1 : 0);
    }

    /** {@code vips_operation_block_set}: blocks or unblocks {@code className} and every subclass. */
    static void operationBlockSet(String className, boolean state) {
        try (Arena a = Arena.ofConfined()) {
            h("vips_operation_block_set").invokeExact(a.allocateFrom(className), state ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long trackedGetMem() {
        return longResult("vips_tracked_get_mem");
    }

    // ---- errors ----

    /**
     * {@code vips_error_buffer_copy}: libvips copies <em>and clears</em> its process-global error buffer in one call
     * under its own lock. The text is copied to Java and the C copy freed.
     *
     * @return the text, {@code ""} when empty
     */
    static String errorBufferCopy() {
        try {
            MemorySegment p = (MemorySegment) h("vips_error_buffer_copy").invokeExact();
            if (p.equals(NULL)) {
                return "";
            }
            try {
                return cstr(p);
            } finally {
                gFree(p);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_error_clear}; for tests only — production code takes the buffer with {@link #errorBufferCopy()}. */
    static void errorClear() {
        voidCall("vips_error_clear");
    }

    // ---- objects and types ----

    /** {@code vips_operation_new}: a new operation object, {@code NULL} (with error text) for an unknown nickname. */
    static MemorySegment operationNew(String nickname) {
        try (Arena a = Arena.ofConfined()) {
            return (MemorySegment) h("vips_operation_new").invokeExact(a.allocateFrom(nickname));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int operationGetFlags(MemorySegment op) {
        try {
            return (int) h("vips_operation_get_flags").invokeExact(op);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vips_cache_operation_build}: the built operation (a new reference, possibly a cached one), {@code NULL}
     * on failure. The caller keeps its reference to {@code op}.
     */
    static MemorySegment cacheOperationBuild(MemorySegment op) {
        return pointerToPointer("vips_cache_operation_build", op);
    }

    static void unrefOutputs(MemorySegment op) {
        pointerArg("vips_object_unref_outputs", op);
    }

    /** {@code g_object_unref}. */
    static void unref(MemorySegment obj) {
        pointerArg("g_object_unref", obj);
    }

    /**
     * {@code vips_type_find}: the {@code GType} of a nickname or concrete class name under {@code base}, 0 when unknown
     * or abstract (no error text either way).
     */
    static long typeFind(String base, String nickname) {
        try (Arena a = Arena.ofConfined()) {
            return (long) h("vips_type_find").invokeExact(a.allocateFrom(base), a.allocateFrom(nickname));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code g_type_from_name}: the {@code GType} of a registered type name, 0 when unknown. Finds abstract classes such
     * as {@code VipsForeignLoadHeif}, which {@link #typeFind} skips; a nickname is not a type name.
     */
    static long typeFromName(String name) {
        try (Arena a = Arena.ofConfined()) {
            return (long) h("g_type_from_name").invokeExact(a.allocateFrom(name));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_operation_get_type}: the {@code GType} of {@code VipsOperation}. */
    static long operationType() {
        return longResult("vips_operation_get_type");
    }

    /** {@code g_type_name}, {@code null} for an invalid type. */
    static String typeName(long gtype) {
        try {
            return cstr((MemorySegment) h("g_type_name").invokeExact(gtype));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long fundamental(long gtype) {
        try {
            return (long) h("g_type_fundamental").invokeExact(gtype);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean typeIsA(long type, long base) {
        try {
            return (int) h("g_type_is_a").invokeExact(type, base) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long imageType() {
        return longResult("vips_image_get_type");
    }

    static long blobType() {
        return longResult("vips_blob_get_type");
    }

    static long arrayDoubleType() {
        return longResult("vips_array_double_get_type");
    }

    static long interpretationType() {
        return longResult("vips_interpretation_get_type");
    }

    /**
     * The value type (from the {@code GParamSpec}'s default value) and flags of an argument of {@code object}.
     *
     * @return empty when {@code vips_object_get_argument} fails; libvips then leaves text in the error buffer, which
     *         the caller clears
     */
    static Optional<ArgumentSpec> argument(MemorySegment object, String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment n = a.allocateFrom(name);
            MemorySegment pspec = a.allocate(ADDRESS);
            MemorySegment argumentClass = a.allocate(ADDRESS);
            MemorySegment argumentInstance = a.allocate(ADDRESS);
            int rc = (int) h("vips_object_get_argument").invokeExact(object, n, pspec, argumentClass,
                    argumentInstance);
            if (rc != 0) {
                return Optional.empty();
            }
            MemorySegment spec = pspec.get(ADDRESS, 0);
            MemorySegment value = (MemorySegment) h("g_param_spec_get_default_value").invokeExact(spec);
            long type = value.reinterpret(GVALUE.byteSize()).get(JAVA_LONG, 0);
            int flags = (int) h("vips_object_get_argument_flags").invokeExact(object, n);
            return Optional.of(new ArgumentSpec(type, flags, spec));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- setters: g_value_init, g_value_set_*, g_object_set_property, g_value_unset ----

    static void setInt(MemorySegment op, String name, int value) {
        set(op, name, G_TYPE_INT, (gv, a) -> {
            h("g_value_set_int").invokeExact(gv, value);
        });
    }

    static void setDouble(MemorySegment op, String name, double value) {
        set(op, name, G_TYPE_DOUBLE, (gv, a) -> {
            h("g_value_set_double").invokeExact(gv, value);
        });
    }

    static void setBoolean(MemorySegment op, String name, boolean value) {
        set(op, name, G_TYPE_BOOLEAN, (gv, a) -> {
            h("g_value_set_boolean").invokeExact(gv, value ? 1 : 0);
        });
    }

    static void setEnum(MemorySegment op, String name, long type, int value) {
        set(op, name, type, (gv, a) -> {
            h("g_value_set_enum").invokeExact(gv, value);
        });
    }

    static void setFlags(MemorySegment op, String name, long type, int value) {
        set(op, name, type, (gv, a) -> {
            h("g_value_set_flags").invokeExact(gv, value);
        });
    }

    static void setString(MemorySegment op, String name, String value) {
        set(op, name, G_TYPE_STRING, (gv, a) -> {
            MemorySegment text = value == null ? NULL : a.allocateFrom(value);
            h("g_value_set_string").invokeExact(gv, text);
        });
    }

    /** Sets an image argument; the {@code GValue} takes its own reference, the caller keeps its own. */
    static void setImage(MemorySegment op, String name, MemorySegment image) {
        set(op, name, imageType(), (gv, a) -> {
            h("g_value_set_object").invokeExact(gv, image);
        });
    }

    /**
     * Sets a {@code VipsBlob} argument holding a copy of {@code data}. Two native copies are made: the Java array
     * into this call's arena, then {@code vips_blob_copy} into memory libvips owns, so nothing libvips keeps points
     * into memory Java frees.
     */
    static void setBlobCopy(MemorySegment op, String name, byte[] data) {
        set(op, name, blobType(), (gv, a) -> {
            MemorySegment copy = a.allocateFrom(JAVA_BYTE, data);
            MemorySegment blob = (MemorySegment) h("vips_blob_copy").invokeExact(copy, (long) data.length);
            try {
                h("g_value_set_boxed").invokeExact(gv, blob);
            } finally {
                h("vips_area_unref").invokeExact(blob);
            }
        });
    }

    static void setArrayDouble(MemorySegment op, String name, double[] values) {
        set(op, name, arrayDoubleType(), (gv, a) -> {
            h("vips_value_set_array_double").invokeExact(gv,
                    a.allocateFrom(JAVA_DOUBLE, values), values.length);
        });
    }

    // ---- getters: g_value_init, g_object_get_property, copy out, g_value_unset ----

    /** {@return a new reference to the image argument (the caller unrefs it), or {@code NULL} when unset} */
    static MemorySegment getImage(MemorySegment op, String name) {
        return get(op, name, imageType(), gv -> {
            MemorySegment image = (MemorySegment) h("g_value_get_object").invokeExact(gv);
            return image.equals(NULL) ? NULL : (MemorySegment) h("g_object_ref").invokeExact(image);
        });
    }

    /** {@return a Java copy of the {@code VipsBlob} argument, empty when it holds none} */
    static byte[] getBlob(MemorySegment op, String name) {
        return get(op, name, blobType(), gv -> {
            MemorySegment blob = (MemorySegment) h("g_value_get_boxed").invokeExact(gv);
            if (blob.equals(NULL)) {
                return new byte[0];
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment length = a.allocate(JAVA_LONG);
                MemorySegment data = (MemorySegment) h("vips_blob_get").invokeExact(blob, length);
                return copy(data, length.get(JAVA_LONG, 0));
            }
        });
    }

    static int getInt(MemorySegment op, String name) {
        return get(op, name, G_TYPE_INT, gv -> (int) h("g_value_get_int").invokeExact(gv));
    }

    static double getDouble(MemorySegment op, String name) {
        return get(op, name, G_TYPE_DOUBLE, gv -> (double) h("g_value_get_double").invokeExact(gv));
    }

    static boolean getBoolean(MemorySegment op, String name) {
        return get(op, name, G_TYPE_BOOLEAN, gv -> (int) h("g_value_get_boolean").invokeExact(gv) != 0);
    }

    // ---- enums ----

    /** {@code vips_enum_from_nick}: the value of a nick (or name) of enum {@code type}, {@code -1} on failure. */
    static int enumFromNick(long type, String nick) {
        return fromNick("vips_enum_from_nick", type, nick);
    }

    /** {@code vips_flags_from_nick}: flags from a nick list such as {@code "a:b"}, {@code -1} on failure. */
    static int flagsFromNick(long type, String nick) {
        return fromNick("vips_flags_from_nick", type, nick);
    }

    /** {@code vips_enum_nick}: the nick of a value of enum {@code type}. */
    static String enumNick(long type, int value) {
        try {
            return cstr((MemorySegment) h("vips_enum_nick").invokeExact(type, value));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- header ----

    static int width(MemorySegment image) {
        return pointerToInt("vips_image_get_width", image);
    }

    static int height(MemorySegment image) {
        return pointerToInt("vips_image_get_height", image);
    }

    static int bands(MemorySegment image) {
        return pointerToInt("vips_image_get_bands", image);
    }

    static int nPages(MemorySegment image) {
        return pointerToInt("vips_image_get_n_pages", image);
    }

    static int pageHeight(MemorySegment image) {
        return pointerToInt("vips_image_get_page_height", image);
    }

    static int orientation(MemorySegment image) {
        return pointerToInt("vips_image_get_orientation", image);
    }

    static int interpretation(MemorySegment image) {
        return pointerToInt("vips_image_get_interpretation", image);
    }

    static boolean hasAlpha(MemorySegment image) {
        return pointerToInt("vips_image_hasalpha", image) != 0;
    }

    /** {@return the names of every header field} */
    static List<String> fields(MemorySegment image) {
        try {
            return strv((MemorySegment) h("vips_image_get_fields").invokeExact(image));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_image_get_typeof}: the field's {@code GType}, 0 when the image has no such field. */
    static long typeOf(MemorySegment image, String name) {
        try (Arena a = Arena.ofConfined()) {
            return (long) h("vips_image_get_typeof").invokeExact(image, a.allocateFrom(name));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_image_get_as_string}: any field as text; empty when the field is absent. */
    static Optional<String> asString(MemorySegment image, String name) {
        if (typeOf(image, name) == 0) {
            return Optional.empty();
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            int rc = (int) h("vips_image_get_as_string").invokeExact(image, a.allocateFrom(name), out);
            if (rc != 0) {
                return Optional.empty();
            }
            MemorySegment p = out.get(ADDRESS, 0);
            try {
                return Optional.ofNullable(cstr(p));
            } finally {
                gFree(p);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vips_image_get_int} for an {@code int} field. Empty when the field is absent or holds another type: the
     * type is checked with {@code vips_image_get_typeof} first, because {@code vips_image_get_int} on a field of
     * another type (a string, say) fails with "field ... is of type ..." in the process-global error buffer.
     */
    static OptionalInt intField(MemorySegment image, String name) {
        if (fieldFundamental(image, name) != G_TYPE_INT) {
            return OptionalInt.empty();
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT);
            int rc = (int) h("vips_image_get_int").invokeExact(image, a.allocateFrom(name), out);
            return rc == 0 ? OptionalInt.of(out.get(JAVA_INT, 0)) : OptionalInt.empty();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vips_image_get_double} for a {@code double} or {@code int} field (an {@code int} is converted). Empty when
     * the field is absent or holds another type, checked first for the reason given on {@link #intField}.
     */
    static OptionalDouble doubleField(MemorySegment image, String name) {
        long fundamental = fieldFundamental(image, name);
        if (fundamental != G_TYPE_DOUBLE && fundamental != G_TYPE_INT) {
            return OptionalDouble.empty();
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_DOUBLE);
            int rc = (int) h("vips_image_get_double").invokeExact(image, a.allocateFrom(name), out);
            return rc == 0 ? OptionalDouble.of(out.get(JAVA_DOUBLE, 0)) : OptionalDouble.empty();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vips_image_get_blob}, copied to Java. Empty when the field is absent or is not a {@code VipsBlob},
     * checked first for the reason given on {@link #intField}.
     */
    static Optional<byte[]> blobField(MemorySegment image, String name) {
        long type = typeOf(image, name);
        if (type == 0 || !typeIsA(type, blobType())) {
            return Optional.empty();
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment data = a.allocate(ADDRESS);
            MemorySegment length = a.allocate(JAVA_LONG);
            int rc = (int) h("vips_image_get_blob").invokeExact(image, a.allocateFrom(name), data, length);
            return rc == 0 ? Optional.of(copy(data.get(ADDRESS, 0), length.get(JAVA_LONG, 0))) : Optional.empty();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@return the fundamental type of the field's type, 0 when the image has no such field} */
    private static long fieldFundamental(MemorySegment image, String name) {
        long type = typeOf(image, name);
        return type == 0 ? 0 : fundamental(type);
    }

    // ---- foreign ----

    /** {@code vips_foreign_find_load}: the loader class name for a file; empty (with error text) when none. */
    static Optional<String> findLoad(String filename) {
        try (Arena a = Arena.ofConfined()) {
            return Optional.ofNullable(cstr((MemorySegment) h("vips_foreign_find_load")
                    .invokeExact(a.allocateFrom(filename))));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code vips_foreign_find_load_buffer}: the loader class name for a buffer; empty (with error text) when none.
     * Only the first 64 KiB are copied to native memory: every libvips 8.18 sniffer reads a prefix, and the function
     * keeps nothing.
     */
    static Optional<String> findLoadBuffer(byte[] data) {
        int n = Math.min(data.length, SNIFF_BYTES);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment prefix = a.allocate(Math.max(n, 1));
            MemorySegment.copy(data, 0, prefix, JAVA_BYTE, 0, n);
            return Optional.ofNullable(cstr((MemorySegment) h("vips_foreign_find_load_buffer")
                    .invokeExact(prefix, (long) n)));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_foreign_find_save_buffer}: the buffer saver class name for a suffix such as {@code ".webp"}. */
    static Optional<String> findSaveBuffer(String suffix) {
        try (Arena a = Arena.ofConfined()) {
            return Optional.ofNullable(cstr((MemorySegment) h("vips_foreign_find_save_buffer")
                    .invokeExact(a.allocateFrom(suffix))));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code vips_foreign_get_suffixes}: every suffix some saver accepts. */
    static List<String> saveSuffixes() {
        try {
            return strv((MemorySegment) h("vips_foreign_get_suffixes").invokeExact());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- helpers ----

    /** A value that does not fit its argument's {@code GParamSpec}; the message says why, without the name. */
    static final class InvalidValueException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        InvalidValueException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface ValueSetter {
        void set(MemorySegment gvalue, Arena arena) throws Throwable;
    }

    @FunctionalInterface
    private interface ValueGetter<T> {
        T get(MemorySegment gvalue) throws Throwable;
    }

    /**
     * Fills a {@code GValue}, checks it against the argument's {@code GParamSpec} with {@code g_param_value_validate}
     * and only then sets it: GLib would otherwise log a critical and silently keep the default for a value out of
     * range (an int or double outside its bounds, NaN, flag bits outside the mask, an unknown enum value).
     *
     * @throws IllegalArgumentException {@code no argument <name>}
     * @throws InvalidValueException {@code out of range (min to max)} or {@code not a valid value}
     */
    private static void set(MemorySegment op, String name, long type, ValueSetter setter) {
        MemorySegment pspec = argument(op, name).map(ArgumentSpec::pspec).orElseThrow(() -> {
            errorBufferCopy(); // "no property named ..."
            return new IllegalArgumentException("no argument " + name);
        });
        try (Arena a = Arena.ofConfined()) {
            MemorySegment gv = gvalue(a, type);
            try {
                setter.set(gv, a);
                if ((int) h("g_param_value_validate").invokeExact(pspec, gv) != 0) {
                    throw new InvalidValueException(invalid(pspec));
                }
                h("g_object_set_property").invokeExact(op, a.allocateFrom(name), gv);
            } finally {
                h("g_value_unset").invokeExact(gv);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code GParamSpec}: 72 bytes on LP64, then the bounds of {@code GParamSpecInt} / {@code GParamSpecDouble}. */
    private static final long PSPEC_VALUE_TYPE = 24, PSPEC_MIN = 72, PSPEC_INT_MAX = 76, PSPEC_DOUBLE_MAX = 80;

    /** Why a value failed validation, with the bounds of an int or double argument. */
    private static String invalid(MemorySegment pspec) {
        MemorySegment p = pspec.reinterpret(88);
        long fundamental = fundamental(p.get(JAVA_LONG, PSPEC_VALUE_TYPE));
        if (fundamental == G_TYPE_INT) {
            return "out of range (" + p.get(JAVA_INT, PSPEC_MIN) + " to " + p.get(JAVA_INT, PSPEC_INT_MAX) + ")";
        }
        if (fundamental == G_TYPE_DOUBLE) {
            return "out of range (" + p.get(JAVA_DOUBLE, PSPEC_MIN) + " to " + p.get(JAVA_DOUBLE, PSPEC_DOUBLE_MAX)
                    + ")";
        }
        return "not a valid value";
    }

    private static <T> T get(MemorySegment op, String name, long type, ValueGetter<T> getter) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment gv = gvalue(a, type);
            try {
                h("g_object_get_property").invokeExact(op, a.allocateFrom(name), gv);
                return getter.get(gv);
            } finally {
                h("g_value_unset").invokeExact(gv);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A zeroed {@code GValue} (G_VALUE_INIT) in {@code a}, initialised to {@code type}. */
    private static MemorySegment gvalue(Arena a, long type) throws Throwable {
        MemorySegment gv = a.allocate(GVALUE);
        MemorySegment unused = (MemorySegment) h("g_value_init").invokeExact(gv, type);
        return gv;
    }

    private static int fromNick(String symbol, long type, String nick) {
        try (Arena a = Arena.ofConfined()) {
            return (int) h(symbol).invokeExact(a.allocateFrom(DOMAIN), type, a.allocateFrom(nick));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Copies a NULL-terminated {@code gchar **} to Java and frees it with {@code g_strfreev}. */
    private static List<String> strv(MemorySegment array) throws Throwable {
        if (array.equals(NULL)) {
            return List.of();
        }
        try {
            MemorySegment all = array.reinterpret(Long.MAX_VALUE);
            List<String> out = new ArrayList<>();
            for (long i = 0;; i++) {
                MemorySegment s = all.getAtIndex(ADDRESS, i);
                if (s.equals(NULL)) {
                    return List.copyOf(out);
                }
                out.add(cstr(s));
            }
        } finally {
            h("g_strfreev").invokeExact(array);
        }
    }

    private static byte[] copy(MemorySegment data, long length) {
        if (length == 0) {
            return new byte[0];
        }
        return data.reinterpret(length).toArray(JAVA_BYTE);
    }

    /** {@return the UTF-8 C string at {@code p}, {@code null} for {@code NULL}} */
    private static String cstr(MemorySegment p) {
        return p.equals(NULL) ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static void gFree(MemorySegment p) throws Throwable {
        h("g_free").invokeExact(p);
    }

    private static void voidCall(String symbol) {
        try {
            h(symbol).invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void intArg(String symbol, int value) {
        try {
            h(symbol).invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static int intResult(String symbol) {
        try {
            return (int) h(symbol).invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static long longResult(String symbol) {
        try {
            return (long) h(symbol).invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void pointerArg(String symbol, MemorySegment p) {
        try {
            h(symbol).invokeExact(p);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static int pointerToInt(String symbol, MemorySegment p) {
        try {
            return (int) h(symbol).invokeExact(p);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MemorySegment pointerToPointer(String symbol, MemorySegment p) {
        try {
            return (MemorySegment) h(symbol).invokeExact(p);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static Bound bound() {
        Bound b = bound;
        if (b == null) {
            throw new IllegalStateException("libvips is not bound");
        }
        return b;
    }

    /**
     * {@return the linked handle of {@code symbol}}
     *
     * @throws IllegalStateException when not bound, or when {@code symbol} is not a {@link #DOWNCALLS} entry
     */
    static MethodHandle h(String symbol) {
        MethodHandle handle = bound().handles().get(symbol);
        if (handle == null) {
            throw new IllegalStateException("no downcall for symbol " + symbol);
        }
        return handle;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException("unexpected exception from a libvips call: " + t, t);
    }
}
