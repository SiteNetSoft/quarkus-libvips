package org.sitenetsoft.quarkus.libvips.internal;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.VipsEnum;

/**
 * One libvips operation, built from arguments that are checked against the operation's own {@code GParamSpec}s and
 * set as {@code GValue}s (no variadic call).
 * <p>
 * Every argument is validated before it is set: an unknown name, an output, deprecated or modified-in-place argument,
 * a Java value that does not fit the argument's {@code GType}, or a value outside the {@code GParamSpec}'s range
 * ({@code g_param_value_validate}: int and double bounds, NaN, flag bits outside the mask) is an
 * {@link IllegalArgumentException}, and nothing reaches the operation. Values by fundamental type: {@code gint}
 * {@link Integer}; {@code gdouble} any {@link Number}; {@code gboolean} {@link Boolean}; {@code gchararray}
 * {@link String}; enums a {@link VipsEnum} or a {@link String} nick; flags {@link Integer} bits or a {@link String}
 * nick (such as {@code "icc:exif"}); {@code VipsImage} a {@link NativeImage}; {@code VipsBlob} {@code byte[]}
 * (copied); {@code VipsArrayDouble} {@code double[]}.
 * <p>
 * References: {@link #build()} returns a {@link Built} that holds a new reference to the built operation (often a
 * different, cached object); this operation's own reference is dropped then, on success and on failure. An operation
 * never built drops its reference on {@link #close()}.
 * <p>
 * Callers bracket the whole sequence with {@link VipsRuntime#enter()} / {@link VipsRuntime#exit()}.
 */
public final class Operation implements AutoCloseable {

    private final String nickname;
    private final MemorySegment op;
    private boolean built;

    private Operation(String nickname, MemorySegment op) {
        this.nickname = nickname;
        this.op = op;
    }

    /**
     * {@return a new operation}
     *
     * @param nickname the operation's nickname (or class name)
     * @throws LibvipsException when libvips has no such operation
     */
    public static Operation create(String nickname) {
        MemorySegment op = LibvipsNative.operationNew(nickname);
        if (op.equals(MemorySegment.NULL)) {
            throw new LibvipsException(nickname, ErrorBuffer.take());
        }
        return new Operation(nickname, op);
    }

    /** {@return the nickname this operation was created with} */
    public String nickname() {
        return nickname;
    }

    /** {@return whether libvips blocks this operation ({@code quarkus.libvips.security.*})} */
    public boolean blocked() {
        return (LibvipsNative.operationGetFlags(op) & LibvipsNative.VIPS_OPERATION_BLOCKED) != 0;
    }

    /**
     * {@return whether the operation has an input argument {@code name} that is not deprecated}
     *
     * @param name the argument name
     */
    public boolean has(String name) {
        return spec(name).filter(Operation::isInput).isPresent();
    }

    /**
     * {@return the first of {@code names} that {@link #has}} Used for the image input, which libvips names {@code in}
     * or {@code input} depending on the operation, and for arguments renamed between libvips versions.
     *
     * @param names candidate argument names, in order of preference
     */
    public Optional<String> firstInput(String... names) {
        for (String name : names) {
            if (has(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /**
     * {@return whether the operation has an output argument {@code name} that holds an image}
     *
     * @param name the argument name, usually {@code out}
     */
    public boolean hasImageOutput(String name) {
        return spec(name).filter(s -> (s.flags() & ArgumentSpec.OUTPUT) != 0 && s.valueType() != 0
                && LibvipsNative.typeIsA(s.valueType(), LibvipsNative.imageType())).isPresent();
    }

    /**
     * {@return the maximum of an {@code int} argument as libvips reports it (on libvips 8.18 the coordinate limits
     * follow {@code VIPS_MAX_COORD}); empty when there is no such argument or it is not an {@code int}}
     *
     * @param name the argument name
     */
    public OptionalInt intMaximum(String name) {
        return spec(name).map(LibvipsNative::intMaximum).orElse(OptionalInt.empty());
    }

    /**
     * Validates and sets an input argument.
     *
     * @param name the argument name
     * @param value the value (see the class description for the accepted Java types)
     * @return this operation
     * @throws IllegalArgumentException when the name is unknown; the argument is not an input, is deprecated or is
     *         modified in place ({@code VIPS_ARGUMENT_MODIFY}, refused); the value does not fit its type; or the value
     *         is outside the {@code GParamSpec}'s range ({@code g_param_value_validate})
     * @throws IllegalStateException when the operation has been built or closed
     */
    public Operation set(String name, Object value) {
        if (built) {
            throw new IllegalStateException(nickname + " has been built");
        }
        ArgumentSpec spec = spec(name)
                .orElseThrow(() -> new IllegalArgumentException(nickname + " has no argument " + name));
        if ((spec.flags() & ArgumentSpec.MODIFY) != 0) {
            throw new IllegalArgumentException(nickname + ": " + name + " is modified in place; not supported");
        }
        if (!isInput(spec)) {
            throw new IllegalArgumentException(nickname + ": " + name + " is not an input");
        }
        try {
            setValue(spec, name, value);
        } catch (LibvipsNative.InvalidValueException e) {
            throw new IllegalArgumentException(nickname + ": " + name + " = " + show(value) + " is " + e.getMessage());
        }
        return this;
    }

    private void setValue(ArgumentSpec spec, String name, Object value) {
        long type = spec.valueType();
        if (type == 0) { // never hand an invalid GType to libvips
            throw new IllegalArgumentException(nickname + ": " + name + " has no type");
        }
        long fundamental = LibvipsNative.fundamental(type);
        if (fundamental == LibvipsNative.G_TYPE_INT) {
            LibvipsNative.setInt(op, name, expect(name, type, value, Integer.class));
        } else if (fundamental == LibvipsNative.G_TYPE_DOUBLE) {
            LibvipsNative.setDouble(op, name, expect(name, type, value, Number.class).doubleValue());
        } else if (fundamental == LibvipsNative.G_TYPE_BOOLEAN) {
            LibvipsNative.setBoolean(op, name, expect(name, type, value, Boolean.class));
        } else if (fundamental == LibvipsNative.G_TYPE_STRING) {
            LibvipsNative.setString(op, name, expect(name, type, value, String.class));
        } else if (fundamental == LibvipsNative.G_TYPE_ENUM) {
            String nick = value instanceof VipsEnum e ? e.nick() : expect(name, type, value, String.class);
            int v = LibvipsNative.enumFromNick(type, nick);
            if (v < 0) {
                ErrorBuffer.take(); // "enum ... has no member ...": reported below instead
                throw new IllegalArgumentException(
                        nickname + ": " + name + " has no value " + nick + " in " + LibvipsNative.typeName(type));
            }
            LibvipsNative.setEnum(op, name, type, v);
        } else if (fundamental == LibvipsNative.G_TYPE_FLAGS) {
            int bits;
            if (value instanceof Integer i) {
                bits = i;
            } else {
                String nick = expect(name, type, value, String.class);
                bits = LibvipsNative.flagsFromNick(type, nick);
                if (bits < 0) {
                    ErrorBuffer.take();
                    throw new IllegalArgumentException(nickname + ": " + name + " has no value " + nick + " in "
                            + LibvipsNative.typeName(type));
                }
            }
            LibvipsNative.setFlags(op, name, type, bits);
        } else if (fundamental == LibvipsNative.G_TYPE_OBJECT
                && LibvipsNative.typeIsA(type, LibvipsNative.imageType())) {
            NativeImage image = expect(name, type, value, NativeImage.class);
            image.use(segment -> {
                LibvipsNative.setImage(op, name, segment); // the operation takes its own reference
                return null;
            });
        } else if (fundamental == LibvipsNative.G_TYPE_BOXED && LibvipsNative.typeIsA(type, LibvipsNative.blobType())) {
            LibvipsNative.setBlobCopy(op, name, expect(name, type, value, byte[].class));
        } else if (fundamental == LibvipsNative.G_TYPE_BOXED
                && LibvipsNative.typeIsA(type, LibvipsNative.arrayDoubleType())) {
            LibvipsNative.setArrayDouble(op, name, expect(name, type, value, double[].class));
        } else {
            throw new IllegalArgumentException(
                    nickname + ": " + name + " has unsupported type " + LibvipsNative.typeName(type));
        }
    }

    /**
     * Builds the operation through libvips's operation cache. This operation's reference is dropped whatever the
     * outcome; on failure its outputs are dropped too.
     *
     * @return the built operation, to read outputs from and close
     * @throws LibvipsException when the build fails, with libvips's error text
     * @throws IllegalStateException when already built
     */
    public Built build() {
        if (built) {
            throw new IllegalStateException(nickname + " has been built");
        }
        built = true;
        MemorySegment result = LibvipsNative.cacheOperationBuild(op);
        if (result.equals(MemorySegment.NULL)) {
            String detail = ErrorBuffer.take();
            LibvipsNative.unrefOutputs(op);
            LibvipsNative.unref(op);
            throw new LibvipsException(nickname, detail);
        }
        LibvipsNative.unref(op);
        return new Built(nickname, result);
    }

    /** Drops the reference of an operation that was never built; idempotent. */
    @Override
    public void close() {
        if (!built) {
            built = true;
            LibvipsNative.unref(op);
        }
    }

    private Optional<ArgumentSpec> spec(String name) {
        Optional<ArgumentSpec> spec = LibvipsNative.argument(op, name);
        if (spec.isEmpty()) {
            ErrorBuffer.take(); // "no property named ...": the caller reports the unknown name itself
        }
        return spec;
    }

    private static boolean isInput(ArgumentSpec spec) {
        return (spec.flags() & ArgumentSpec.INPUT) != 0
                && (spec.flags() & (ArgumentSpec.DEPRECATED | ArgumentSpec.MODIFY)) == 0;
    }

    private static String show(Object value) {
        return switch (value) {
            case double[] d -> Arrays.toString(d);
            case byte[] b -> "byte[" + b.length + "]";
            case NativeImage unused -> "Image";
            default -> String.valueOf(value);
        };
    }

    private <T> T expect(String name, long type, Object value, Class<T> javaType) {
        if (javaType.isInstance(value)) {
            return javaType.cast(value);
        }
        throw new IllegalArgumentException(nickname + ": " + name + " expects " + LibvipsNative.typeName(type)
                + ", got " + describe(value));
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof NativeImage ? "Image" : value.getClass().getSimpleName();
    }

    /** A built operation: its outputs, then {@link #close()}. */
    public static final class Built implements AutoCloseable {

        private final String nickname;
        private final MemorySegment op;
        private boolean closed;

        private Built(String nickname, MemorySegment op) {
            this.nickname = nickname;
            this.op = op;
        }

        /**
         * {@return a new reference to an output image, owned by the result}
         *
         * @param name the output argument, usually {@code out}
         * @param runtime the runtime that counts the image
         * @throws LibvipsException when the operation left the output unset
         */
        public NativeImage image(String name, VipsRuntime runtime) {
            MemorySegment image = LibvipsNative.getImage(op, name);
            if (image.equals(MemorySegment.NULL)) {
                throw new LibvipsException(nickname, "no output image " + name);
            }
            return NativeImage.adopt(image, runtime);
        }

        /**
         * {@return a Java copy of an output blob}
         *
         * @param name the output argument, such as {@code buffer}
         */
        public byte[] blob(String name) {
            return LibvipsNative.getBlob(op, name);
        }

        /**
         * {@return an output {@code gint}}
         *
         * @param name the output argument
         */
        public int intValue(String name) {
            return LibvipsNative.getInt(op, name);
        }

        /**
         * {@return an output {@code gdouble}}
         *
         * @param name the output argument
         */
        public double doubleValue(String name) {
            return LibvipsNative.getDouble(op, name);
        }

        /** Drops the outputs and the reference; idempotent. */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                LibvipsNative.unrefOutputs(op);
                LibvipsNative.unref(op);
            }
        }
    }
}
