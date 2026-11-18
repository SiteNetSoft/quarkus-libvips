package org.sitenetsoft.quarkus.libvips.internal;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sitenetsoft.quarkus.libvips.LibvipsUnavailableException;
import org.sitenetsoft.quarkus.libvips.Version;

/**
 * Finds and opens the system libvips; the extension never bundles it.
 * <p>
 * A configured value containing {@code /} is opened as that file; any other configured value is passed unchanged to
 * the dynamic linker. Without one, in order: {@code libvips.so.42} through the dynamic linker's own search, then
 * {@code libvips.so.42} in each {@code java.library.path} entry, each {@code LD_LIBRARY_PATH} entry and the usual
 * system directories. A candidate counts when it exports {@code vips_init} and {@code vips_version}; one that is older
 * than libvips 8.15, or lacks any {@link LibvipsNative#DOWNCALLS} function, fails the load with the version or the
 * missing functions named, read before anything is bound.
 * <p>
 * The library is opened with {@code dlopen} through {@link SymbolLookup#libraryLookup} in the global arena, never
 * with {@link System#load}, which would tie it to one class loader that a dev-mode restart cannot load it from again.
 * <p>
 * <b>One libvips per process.</b> Two copies would register the same GLib types twice. The first successful load
 * records the address of {@code vips_init} and the library in the system property {@link #LOADED_PROPERTY} (system
 * properties are process-wide, unlike a static field of a dev-mode class loader). A later load whose
 * {@code vips_init} is elsewhere is refused before anything is bound or initialised; the same library under another
 * name (the soname and its real path) has the same address and is accepted.
 * <p>
 * The address is only known once the candidate is open, so a refused copy has already been {@code dlopen}ed into the
 * process (its ELF constructors and dependencies loaded) and stays mapped: the global arena never unloads it. That is
 * harmless: libvips registers its types in {@code vips_init}, which never runs for the refused copy, and nothing is
 * linked against it.
 */
public final class LibraryLoader {

    /** The soname of libvips 8.x. */
    static final String SONAME = "libvips.so.42";

    /** System property holding {@code <hex address of vips_init> <library>} of the libvips this process uses. */
    static final String LOADED_PROPERTY = "org.sitenetsoft.quarkus.libvips.loaded";

    private static final List<String> SYSTEM_DIRS = List.of(
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/lib64",
            "/usr/local/lib");

    /**
     * An opened and bound libvips.
     *
     * @param library the path or name it was opened by
     * @param lookup its symbols
     */
    public record Loaded(String library, SymbolLookup lookup) {
    }

    private LibraryLoader() {
    }

    /**
     * Opens libvips and binds {@link LibvipsNative} to it.
     *
     * @param configured a path or name; empty to search
     * @return the library opened
     * @throws LibvipsUnavailableException listing every candidate tried with its error, when another libvips is
     *         already loaded in this process, or when the library found is older than 8.15 or lacks a function
     */
    public static Loaded load(Optional<String> configured) {
        return load(configured, SONAME);
    }

    /**
     * {@link #load(Optional)} with another soname, so that tests can reach the directory search.
     *
     * @param configured a path or name; empty to search
     * @param soname the name to search for
     * @return the library opened
     */
    static synchronized Loaded load(Optional<String> configured, String soname) {
        List<String> tried = new ArrayList<>();
        Optional<String> value = configured.map(String::strip).filter(s -> !s.isEmpty());
        if (value.isPresent()) {
            String v = value.get();
            Optional<Loaded> loaded = v.contains("/") ? tryOpen(v, Path.of(v), tried) : tryOpen(v, null, tried);
            return loaded.orElseThrow(() -> failure(tried));
        }
        Optional<Loaded> loaded = tryOpen(soname, null, tried);
        if (loaded.isPresent()) {
            return loaded.get();
        }
        Set<String> dirs = new LinkedHashSet<>();
        dirs.addAll(split(System.getProperty("java.library.path")));
        dirs.addAll(split(System.getenv("LD_LIBRARY_PATH")));
        dirs.addAll(SYSTEM_DIRS);
        for (String dir : dirs) {
            Path candidate = Path.of(dir, soname).toAbsolutePath();
            if (!Files.exists(candidate)) {
                tried.add(candidate + " (not found)");
                continue;
            }
            loaded = tryOpen(candidate.toString(), candidate, tried);
            if (loaded.isPresent()) {
                return loaded.get();
            }
        }
        throw failure(tried);
    }

    /**
     * Opens one candidate.
     *
     * @param library the name to report
     * @param path the file to open, or {@code null} to pass {@code library} to the dynamic linker
     */
    private static Optional<Loaded> tryOpen(String library, Path path, List<String> tried) {
        SymbolLookup lookup;
        try {
            lookup = path != null ? SymbolLookup.libraryLookup(path, Arena.global())
                    : SymbolLookup.libraryLookup(library, Arena.global());
        } catch (IllegalArgumentException | SecurityException e) {
            tried.add(library + " (" + e.getMessage() + ")");
            return Optional.empty();
        }
        Optional<MemorySegment> init = lookup.find("vips_init");
        if (init.isEmpty()) {
            tried.add(library + " (symbol vips_init not found)");
            return Optional.empty();
        }
        String address = Long.toHexString(init.get().address());
        String recorded = System.getProperty(LOADED_PROPERTY);
        if (recorded != null && !recorded.startsWith(address + " ")) {
            int space = recorded.indexOf(' ');
            String other = space < 0 ? recorded : recorded.substring(space + 1);
            throw new LibvipsUnavailableException("libvips could not be loaded: another libvips (" + other
                    + ") is already loaded in this JVM; restart the JVM to use " + library);
        }
        Optional<MemorySegment> versionFunction = lookup.find("vips_version");
        if (versionFunction.isEmpty()) {
            tried.add(library + " (symbol vips_version not found)");
            return Optional.empty();
        }
        // a libvips that is too old, or lacks a function, is the library the operator installed: say what is wrong
        // with it rather than search on
        checkCompatible(library, LibvipsNative.versionOf(versionFunction.get()), lookup);
        LibvipsNative.bind(lookup);
        if (recorded == null) {
            System.setProperty(LOADED_PROPERTY, address + " " + library);
        }
        return Optional.of(new Loaded(library, lookup));
    }

    /**
     * Refuses a library older than the minimum 8.15, then one that lacks any {@link LibvipsNative#DOWNCALLS} function,
     * naming every missing one; the version comes first, as an old library lacks newer functions too.
     *
     * @param library the path or name it was opened by
     * @param version its version
     * @param lookup its symbols
     * @throws LibvipsUnavailableException {@code libvips 8.10 is older than the minimum 8.15 (...)}, or
     *         {@code libvips 8.15.0 (...) lacks <functions>, which the extension needs}, in
     *         {@link LibvipsUnavailableException#detail()}
     */
    static void checkCompatible(String library, Version version, SymbolLookup lookup) {
        if (!version.atLeast(VipsRuntime.MIN_MAJOR, VipsRuntime.MIN_MINOR)) {
            throw new LibvipsUnavailableException("libvips " + version.major() + "." + version.minor()
                    + " is older than the minimum " + VipsRuntime.MIN_MAJOR + "." + VipsRuntime.MIN_MINOR + " ("
                    + version + ", opened as " + library + ")");
        }
        List<String> missing = LibvipsNative.DOWNCALLS.keySet().stream()
                .filter(symbol -> lookup.find(symbol).isEmpty())
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new LibvipsUnavailableException("libvips " + version + " (opened as " + library + ") lacks "
                    + String.join(", ", missing) + ", which the extension needs");
        }
    }

    private static List<String> split(String pathList) {
        if (pathList == null || pathList.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : pathList.split(File.pathSeparator)) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static LibvipsUnavailableException failure(List<String> tried) {
        return new LibvipsUnavailableException("libvips could not be loaded; tried: " + String.join("; ", tried)
                + "; install libvips (Debian/Ubuntu: libvips42t64) or set quarkus.libvips.library");
    }
}
