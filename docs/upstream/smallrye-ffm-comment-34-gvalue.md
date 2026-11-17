Posted as a comment on smallrye/smallrye-ffm#34 (struct layouts). The text below is as posted.

A data point from binding libvips, whose recommended binding API (`vips_operation_new`, `g_object_set_property`,
`vips_cache_operation_build`, `g_object_get_property`) passes GLib's `GValue` by pointer everywhere:

```c
struct _GValue {
    GType g_type;          /* gsize */
    union { gint v_int; guint v_uint; glong v_long; gulong v_ulong; gint64 v_int64; guint64 v_uint64;
            gfloat v_float; gdouble v_double; gpointer v_pointer; } data[2];
};                         /* 24 bytes on LP64; must start zeroed (G_VALUE_INIT) */
```

What works today on 0.5 and `main`: a hand-written `StructLayout` and a `MemorySegment` parameter, since the C side
only ever needs the pointer and the fields are read through `g_value_*` accessors:

```java
static final StructLayout GVALUE = MemoryLayout.structLayout(
        JAVA_LONG.withName("g_type"), MemoryLayout.sequenceLayout(2, JAVA_LONG).withName("data"));

@Link static native MemorySegment g_value_init(MemorySegment value, @As(AsType.size_t) long type);
@Link static native void g_object_set_property(MemorySegment object, String name, MemorySegment value);

MemorySegment gv = arena.allocate(GVALUE);   // zeroed
g_value_init(gv, gint);
g_value_set_int(gv, 120);
g_object_set_property(op, "width", gv);
g_value_unset(gv);
```

So for this case the "raw class" design would mostly buy the layout declaration and type safety
(`GValue gv = FFM.allocate(arena, GValue.class)` passed where a `MemorySegment` goes today). The `data` member is the
union case from your notes above. One more thing that would matter here: a zero-initialized allocation by default,
since GLib rejects a GValue whose `g_type` is not 0 before `g_value_init`.
