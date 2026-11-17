package org.sitenetsoft.quarkus.libvips.internal;

import static org.assertj.core.api.Assertions.assertThat;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** The descriptor map, checked without libvips. */
class DowncallsTest {

    static final Set<String> C_FUNCTIONS = Set.of(
            "vips_init", "vips_version", "vips_shutdown", "vips_cache_drop_all", "vips_concurrency_set",
            "vips_concurrency_get", "vips_cache_set_max", "vips_cache_set_max_mem", "vips_cache_set_max_files",
            "vips_cache_get_max", "vips_cache_get_size", "vips_cache_get_max_mem", "vips_cache_get_max_files",
            "vips_block_untrusted_set",
            "vips_operation_block_set", "vips_tracked_get_mem", "vips_error_buffer_copy", "vips_error_clear",
            "g_free", "g_strfreev", "g_object_ref", "g_object_unref", "g_type_name", "g_type_fundamental",
            "g_type_is_a", "g_type_from_name", "g_value_init", "g_value_unset", "g_value_set_int", "g_value_set_double",
            "g_value_set_boolean", "g_value_set_enum", "g_value_set_flags", "g_value_set_string",
            "g_value_set_object", "g_value_set_boxed", "g_value_get_int", "g_value_get_double",
            "g_value_get_boolean", "g_value_get_object", "g_value_get_boxed", "g_object_set_property",
            "g_object_get_property", "g_param_spec_get_default_value", "g_param_value_validate",
            "g_param_spec_get_name", "g_object_class_find_property",
            "vips_image_get_type",
            "vips_blob_get_type", "vips_array_double_get_type", "vips_interpretation_get_type", "vips_blob_copy",
            "vips_blob_get", "vips_area_unref", "vips_value_set_array_double", "vips_enum_from_nick",
            "vips_flags_from_nick", "vips_enum_nick", "vips_type_find", "vips_operation_get_type", "vips_operation_new",
            "vips_operation_get_flags", "vips_cache_operation_build", "vips_object_build", "vips_object_unref_outputs",
            "vips_object_get_argument", "vips_object_get_argument_flags", "vips_image_get_width",
            "vips_image_get_height", "vips_image_get_bands", "vips_image_get_n_pages", "vips_image_get_page_height",
            "vips_image_get_orientation", "vips_image_get_interpretation", "vips_image_hasalpha",
            "vips_image_get_fields", "vips_image_get_typeof", "vips_image_get_as_string", "vips_image_get_int",
            "vips_image_get_double", "vips_image_get_blob", "vips_foreign_find_load",
            "vips_foreign_find_load_buffer", "vips_foreign_find_save_buffer", "vips_foreign_get_suffixes");

    /** P = pointer, I = int/gboolean/guint/C enum, L = size_t/GType, D = double. */
    private static final MemoryLayout P = ADDRESS, I = JAVA_INT, L = JAVA_LONG, D = JAVA_DOUBLE;

    /**
     * Every C function with its descriptor, written from the libvips 8.18 and GLib 2.x headers (LP64), independently
     * of the main code: a layout that is wrong in both a descriptor and its wrapper still fails here.
     */
    static final Map<String, FunctionDescriptor> EXPECTED = expected();

    private static Map<String, FunctionDescriptor> expected() {
        Map<String, FunctionDescriptor> m = new LinkedHashMap<>();
        m.put("vips_init", FunctionDescriptor.of(I, P)); // int (const char *argv0)
        m.put("vips_version", FunctionDescriptor.of(I, I)); // int (int flag)
        m.put("vips_shutdown", FunctionDescriptor.ofVoid());
        m.put("vips_cache_drop_all", FunctionDescriptor.ofVoid());
        m.put("vips_concurrency_set", FunctionDescriptor.ofVoid(I)); // void (int)
        m.put("vips_concurrency_get", FunctionDescriptor.of(I)); // int (void)
        m.put("vips_cache_set_max", FunctionDescriptor.ofVoid(I)); // void (int)
        m.put("vips_cache_set_max_mem", FunctionDescriptor.ofVoid(L)); // void (size_t)
        m.put("vips_cache_set_max_files", FunctionDescriptor.ofVoid(I)); // void (int)
        m.put("vips_cache_get_max", FunctionDescriptor.of(I)); // int (void)
        m.put("vips_cache_get_size", FunctionDescriptor.of(I)); // int (void)
        m.put("vips_cache_get_max_mem", FunctionDescriptor.of(L)); // size_t (void)
        m.put("vips_cache_get_max_files", FunctionDescriptor.of(I)); // int (void)
        m.put("vips_block_untrusted_set", FunctionDescriptor.ofVoid(I)); // void (gboolean)
        m.put("vips_operation_block_set", FunctionDescriptor.ofVoid(P, I)); // void (const char *, gboolean)
        m.put("vips_tracked_get_mem", FunctionDescriptor.of(L)); // size_t (void)
        m.put("vips_error_buffer_copy", FunctionDescriptor.of(P)); // char * (void)
        m.put("vips_error_clear", FunctionDescriptor.ofVoid());
        m.put("g_free", FunctionDescriptor.ofVoid(P)); // void (gpointer)
        m.put("g_strfreev", FunctionDescriptor.ofVoid(P)); // void (gchar **)
        m.put("g_object_ref", FunctionDescriptor.of(P, P)); // gpointer (gpointer)
        m.put("g_object_unref", FunctionDescriptor.ofVoid(P)); // void (gpointer)
        m.put("g_type_name", FunctionDescriptor.of(P, L)); // const gchar * (GType)
        m.put("g_type_fundamental", FunctionDescriptor.of(L, L)); // GType (GType)
        m.put("g_type_is_a", FunctionDescriptor.of(I, L, L)); // gboolean (GType, GType)
        m.put("g_type_from_name", FunctionDescriptor.of(L, P)); // GType (const gchar *)
        m.put("g_value_init", FunctionDescriptor.of(P, P, L)); // GValue * (GValue *, GType)
        m.put("g_value_unset", FunctionDescriptor.ofVoid(P)); // void (GValue *)
        m.put("g_value_set_int", FunctionDescriptor.ofVoid(P, I)); // void (GValue *, gint)
        m.put("g_value_set_double", FunctionDescriptor.ofVoid(P, D)); // void (GValue *, gdouble)
        m.put("g_value_set_boolean", FunctionDescriptor.ofVoid(P, I)); // void (GValue *, gboolean)
        m.put("g_value_set_enum", FunctionDescriptor.ofVoid(P, I)); // void (GValue *, gint)
        m.put("g_value_set_flags", FunctionDescriptor.ofVoid(P, I)); // void (GValue *, guint)
        m.put("g_value_set_string", FunctionDescriptor.ofVoid(P, P)); // void (GValue *, const gchar *)
        m.put("g_value_set_object", FunctionDescriptor.ofVoid(P, P)); // void (GValue *, gpointer)
        m.put("g_value_set_boxed", FunctionDescriptor.ofVoid(P, P)); // void (GValue *, gconstpointer)
        m.put("g_value_get_int", FunctionDescriptor.of(I, P)); // gint (const GValue *)
        m.put("g_value_get_double", FunctionDescriptor.of(D, P)); // gdouble (const GValue *)
        m.put("g_value_get_boolean", FunctionDescriptor.of(I, P)); // gboolean (const GValue *)
        m.put("g_value_get_object", FunctionDescriptor.of(P, P)); // gpointer (const GValue *)
        m.put("g_value_get_boxed", FunctionDescriptor.of(P, P)); // gpointer (const GValue *)
        m.put("g_object_set_property", FunctionDescriptor.ofVoid(P, P, P)); // (GObject *, name, const GValue *)
        m.put("g_object_get_property", FunctionDescriptor.ofVoid(P, P, P)); // (GObject *, const gchar *, GValue *)
        m.put("g_param_spec_get_default_value", FunctionDescriptor.of(P, P)); // const GValue * (GParamSpec *)
        m.put("g_param_value_validate", FunctionDescriptor.of(I, P, P)); // gboolean (GParamSpec *, GValue *)
        m.put("g_param_spec_get_name", FunctionDescriptor.of(P, P)); // const gchar * (GParamSpec *)
        // GParamSpec * (GObjectClass *, const gchar *property_name), NULL when absent, no error text
        m.put("g_object_class_find_property", FunctionDescriptor.of(P, P, P));
        m.put("vips_image_get_type", FunctionDescriptor.of(L)); // GType (void)
        m.put("vips_blob_get_type", FunctionDescriptor.of(L));
        m.put("vips_array_double_get_type", FunctionDescriptor.of(L));
        m.put("vips_interpretation_get_type", FunctionDescriptor.of(L));
        m.put("vips_blob_copy", FunctionDescriptor.of(P, P, L)); // VipsBlob * (const void *, size_t)
        m.put("vips_blob_get", FunctionDescriptor.of(P, P, P)); // const void * (VipsBlob *, size_t *)
        m.put("vips_area_unref", FunctionDescriptor.ofVoid(P)); // void (VipsArea *)
        m.put("vips_value_set_array_double", FunctionDescriptor.ofVoid(P, P, I)); // (GValue *, const double *, int)
        m.put("vips_enum_from_nick", FunctionDescriptor.of(I, P, L, P)); // int (const char *, GType, const char *)
        m.put("vips_flags_from_nick", FunctionDescriptor.of(I, P, L, P)); // int (const char *, GType, const char *)
        m.put("vips_enum_nick", FunctionDescriptor.of(P, L, I)); // const char * (GType, int)
        m.put("vips_type_find", FunctionDescriptor.of(L, P, P)); // GType (const char *, const char *)
        m.put("vips_operation_get_type", FunctionDescriptor.of(L)); // GType (void)
        m.put("vips_operation_new", FunctionDescriptor.of(P, P)); // VipsOperation * (const char *)
        m.put("vips_operation_get_flags", FunctionDescriptor.of(I, P)); // VipsOperationFlags (VipsOperation *)
        m.put("vips_cache_operation_build", FunctionDescriptor.of(P, P)); // VipsOperation * (VipsOperation *)
        m.put("vips_object_build", FunctionDescriptor.of(I, P)); // int (VipsObject *)
        m.put("vips_object_unref_outputs", FunctionDescriptor.ofVoid(P)); // void (VipsObject *)
        // int (VipsObject *, const char *, GParamSpec **, VipsArgumentClass **, VipsArgumentInstance **)
        m.put("vips_object_get_argument", FunctionDescriptor.of(I, P, P, P, P, P));
        m.put("vips_object_get_argument_flags", FunctionDescriptor.of(I, P, P)); // VipsArgumentFlags (obj, name)
        m.put("vips_image_get_width", FunctionDescriptor.of(I, P)); // int (const VipsImage *)
        m.put("vips_image_get_height", FunctionDescriptor.of(I, P));
        m.put("vips_image_get_bands", FunctionDescriptor.of(I, P));
        m.put("vips_image_get_n_pages", FunctionDescriptor.of(I, P));
        m.put("vips_image_get_page_height", FunctionDescriptor.of(I, P));
        m.put("vips_image_get_orientation", FunctionDescriptor.of(I, P));
        m.put("vips_image_get_interpretation", FunctionDescriptor.of(I, P)); // VipsInterpretation (const VipsImage *)
        m.put("vips_image_hasalpha", FunctionDescriptor.of(I, P)); // gboolean (VipsImage *)
        m.put("vips_image_get_fields", FunctionDescriptor.of(P, P)); // gchar ** (VipsImage *)
        m.put("vips_image_get_typeof", FunctionDescriptor.of(L, P, P)); // GType (const VipsImage *, const char *)
        m.put("vips_image_get_as_string", FunctionDescriptor.of(I, P, P, P)); // int (image, name, char **)
        m.put("vips_image_get_int", FunctionDescriptor.of(I, P, P, P)); // int (image, name, int *)
        m.put("vips_image_get_double", FunctionDescriptor.of(I, P, P, P)); // int (image, name, double *)
        m.put("vips_image_get_blob", FunctionDescriptor.of(I, P, P, P, P)); // int (img, name, const void **, size_t *)
        m.put("vips_foreign_find_load", FunctionDescriptor.of(P, P)); // const char * (const char *)
        m.put("vips_foreign_find_load_buffer", FunctionDescriptor.of(P, P, L)); // const char * (const void *, size_t)
        m.put("vips_foreign_find_save_buffer", FunctionDescriptor.of(P, P)); // const char * (const char *)
        m.put("vips_foreign_get_suffixes", FunctionDescriptor.of(P)); // gchar ** (void)
        return m;
    }

    @Test
    void everyDescriptorMatchesTheHeaders() {
        assertThat(EXPECTED).hasSize(85);
        assertThat(EXPECTED.keySet()).isEqualTo(C_FUNCTIONS);
        EXPECTED.forEach((symbol, fd) -> assertThat(LibvipsNative.DOWNCALLS.get(symbol).descriptor()).as(symbol)
                .isEqualTo(fd));
    }

    @Test
    void exactlyTheCApi() {
        assertThat(C_FUNCTIONS).hasSize(85);
        assertThat(LibvipsNative.DOWNCALLS.keySet()).containsExactlyInAnyOrderElementsOf(C_FUNCTIONS);
        LibvipsNative.DOWNCALLS.forEach((k, d) -> assertThat(d.symbol()).isEqualTo(k));
    }

    @Test
    void noVariadicCallInV1() {
        assertThat(LibvipsNative.DOWNCALLS.values()).allSatisfy(d -> {
            assertThat(d.firstVariadic()).isEqualTo(-1);
            assertThat(d.options()).isEmpty();
        });
    }

    @Test
    void onlyModelledCarriers() {
        Set<Class<?>> ok = Set.of(MemorySegment.class, int.class, long.class, double.class);
        for (var d : LibvipsNative.DOWNCALLS.values()) {
            var layouts = new ArrayList<>(d.descriptor().argumentLayouts());
            d.descriptor().returnLayout().ifPresent(layouts::add);
            assertThat(layouts).as(d.symbol()).allSatisfy(l -> assertThat(((ValueLayout) l).carrier()).isIn(ok));
        }
    }

    @Test
    void knownShapes() {
        assertThat(shape("vips_blob_copy")).isEqualTo("void*(void*,jlong)");
        assertThat(shape("vips_object_get_argument")).isEqualTo("jint(void*,void*,void*,void*,void*)");
        assertThat(shape("g_value_init")).isEqualTo("void*(void*,jlong)");
        assertThat(shape("vips_cache_set_max_mem")).isEqualTo("void(jlong)");
        assertThat(shape("g_value_set_double")).isEqualTo("void(void*,jdouble)");
        assertThat(shape("vips_enum_nick")).isEqualTo("void*(jlong,jint)");
    }

    @Test
    void gvalueIs24Bytes() {
        assertThat(LibvipsNative.GVALUE.byteSize()).isEqualTo(24);
    }

    /** {@return "ret(params)" of a downcall, in the notation of the native-image registration} */
    static String shape(String symbol) {
        var d = LibvipsNative.DOWNCALLS.get(symbol).descriptor();
        String ret = d.returnLayout().map(DowncallsTest::name).orElse("void");
        return ret + "(" + d.argumentLayouts().stream().map(DowncallsTest::name).collect(Collectors.joining(","))
                + ")";
    }

    private static String name(MemoryLayout l) {
        if (l instanceof AddressLayout) {
            return "void*";
        }
        Class<?> c = ((ValueLayout) l).carrier();
        if (c == int.class) {
            return "jint";
        }
        if (c == long.class) {
            return "jlong";
        }
        if (c == double.class) {
            return "jdouble";
        }
        throw new IllegalArgumentException("unmodelled layout " + l);
    }
}
