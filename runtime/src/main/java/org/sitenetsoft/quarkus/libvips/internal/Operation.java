package org.sitenetsoft.quarkus.libvips.internal;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

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
 * Arguments that libvips reads as file names ({@code input_profile}, {@code output_profile}, {@code export_profile},
 * {@code import_profile}, {@code profile}, {@code fontfile}, {@code filename}; recognised by the name the argument's
 * {@code GParamSpec} holds, whichever spelling GLib resolved to it) take a built-in profile name ({@code srgb},
 * {@code sgrey}, {@code p3}, {@code cmyk}, {@code none}, any case) or the absolute path of an existing regular
 * file; anything else is refused before libvips opens it. That keeps out FIFOs (opening one blocks the thread until a
 * writer appears), devices (libvips sizes the read with {@code fstat}, so {@code /dev/zero} reads nothing and fails;
 * they are never profiles), directories, and relative names (which libvips also looks up in its own ICC directory).
 * It does not decide which regular files may be read: such a name must come from the application, never from request
 * data.
 * <p>
 * {@code system}, {@code globalbalance} and {@code remosaic} are never created (see {@link #create}).
 * <p>
 * References: {@link #build()} returns a {@link Built} that holds a new reference to the built operation (often a
 * different, cached object); this operation's own reference is dropped then, on success and on failure. An operation
 * never built drops its reference on {@link #close()}.
 * <p>
 * Callers bracket the whole sequence with {@link VipsRuntime#enter()} / {@link VipsRuntime#exit()}.
 */
public final class Operation implements AutoCloseable {

    /** Arguments libvips opens as files, by their canonical name (GLib also accepts {@code -} for {@code _}). */
    private static final Set<String> FILE_ARGUMENTS = Set.of("input_profile", "output_profile", "export_profile",
            "import_profile", "profile", "fontfile", "filename");

    /**
     * The shape of every libvips argument name: GLib's form, with {@code -} or {@code _}; upper case occurs (the
     * savers' {@code Q}). It rules out GLib's {@code Type::name} lookup and anything a NUL would cut short.
     */
    private static final Pattern ARGUMENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    /**
     * Operations never created, by class (subclasses and nicknames included): {@code system} runs a shell command;
     * {@code globalbalance} and {@code remosaic} (libvips 8.18) open files named in the image's history, with
     * loader options, outside every limit here. A class this libvips lacks is skipped.
     */
    private static final List<String> DENIED = List.of("VipsSystem", "VipsGlobalbalance", "VipsRemosaic");

    /** libvips's built-in profiles ({@code vips_profile_load}, compared ignoring case), and {@code none}. */
    private static final Set<String> BUILT_IN_PROFILES = Set.of("none", "srgb", "sgrey", "p3", "cmyk");

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
     * @throws IllegalArgumentException for {@code system}, {@code globalbalance} or {@code remosaic}, which run
     *         external commands or open files named in image metadata
     * @throws LibvipsException when libvips has no such operation
     */
    public static Operation create(String nickname) {
        for (String denied : DENIED) {
            if (LibvipsNative.typeFind(denied, nickname) != 0) {
                throw new IllegalArgumentException(nickname
                        + " is not available: it runs external commands or opens files named in image metadata");
            }
        }
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
        checkName(name);
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
            checkName(name);
        }
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
        checkName(name);
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
        checkName(name);
        return spec(name).map(LibvipsNative::intMaximum).orElse(OptionalInt.empty());
    }

    /**
     * Validates and sets an input argument.
     *
     * @param name the argument name
     * @param value the value (see the class description for the accepted Java types)
     * @return this operation
     * @throws IllegalArgumentException when the name is not shaped {@code [A-Za-z][A-Za-z0-9_-]*} or is unknown; the
     *         argument is not an input, is deprecated or is modified in place ({@code VIPS_ARGUMENT_MODIFY},
     *         refused); the value does not fit its type; the value is outside the {@code GParamSpec}'s range
     *         ({@code g_param_value_validate}); or a file-name argument is neither a built-in profile name nor the
     *         absolute path of a regular file
     * @throws IllegalStateException when the operation has been built or closed
     */
    public Operation set(String name, Object value) {
        checkName(name);
        return setWithoutNameCheck(name, value);
    }

    /**
     * Refuses a name that is not shaped like a libvips argument name ({@code [A-Za-z][A-Za-z0-9_-]*}) before any
     * native call. GLib also resolves {@code Type::name} and stops at a NUL; neither may pick an argument here.
     */
    private void checkName(String name) {
        if (name == null || !ARGUMENT_NAME.matcher(name).matches()) {
            String shown = name == null ? "null" : "\"" + printable(name) + "\"";
            throw new IllegalArgumentException(
                    nickname + ": " + shown + " is not a valid argument name ([A-Za-z][A-Za-z0-9_-]*)");
        }
    }

    /** The name with control characters shown as {@code \\uXXXX}, at most 100 characters. */
    private static String printable(String name) {
        StringBuilder b = new StringBuilder();
        name.chars().limit(100).forEach(c -> {
            if (Character.isISOControl(c)) {
                b.append(String.format("\\u%04x", c));
            } else {
                b.append((char) c);
            }
        });
        return b.toString();
    }

    /** {@link #set} without the check of the name's shape: the rest, for tests of the later checks alone. */
    Operation setWithoutNameCheck(String name, Object value) {
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
        // the GParamSpec's own name, not the caller's spelling: GLib resolves '-' for '_', "Type::name" and stops at
        // a NUL, so only the name the argument really has decides whether libvips opens a file
        String canonical = spec.name().replace('-', '_');
        if (FILE_ARGUMENTS.contains(canonical) && value instanceof String file) {
            checkFileArgument(canonical, file);
        }
        try {
            setValue(spec, name, value);
        } catch (LibvipsNative.InvalidValueException e) {
            throw new IllegalArgumentException(nickname + ": " + name + " = " + show(value) + " is " + e.getMessage());
        }
        return this;
    }

    /** Refuses a file-name argument that is not a built-in profile name or an absolute regular file. */
    private void checkFileArgument(String name, String value) {
        if (BUILT_IN_PROFILES.contains(value.toLowerCase(Locale.ROOT))) {
            return;
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            path = null;
        }
        if (path == null || !path.isAbsolute() || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(nickname + ": " + name + " = " + value.substring(0,
                    Math.min(value.length(), 200)) + " is neither a built-in profile name (srgb, sgrey, p3, cmyk,"
                    + " none) nor the absolute path of a regular file");
        }
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
