package shasankp000.needle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Thin, 1:1 binding to needle.h using the Foreign Function &amp; Memory API (final in Java 22).
 *
 * <pre>
 * int  needle_load(const char* weights_blob, uint64_t len);
 * int  needle_init(const char* system, const char* tools_json, const char* tool_index_path);
 * int  needle_complete(const char* text, int max_new_tokens, char* out_buf, int out_buf_len);
 * void needle_reset(void);
 * int  needle_embed(const char* text, float* out, int capacity);   // inferred from Needle 3 docs
 * const char* needle_last_error(void);                              // inferred from Needle 3 docs
 * </pre>
 *
 * The first four are taken from the published Needle 2 header; the last two are described in prose
 * in the Needle 3 docs but I could not see their exact prototypes. Verify against your needle.h.
 */
final class NeedleNative {
    private final Arena libArena = Arena.ofShared();
    private final MethodHandle load, init, complete, reset;
    private final MethodHandle embed;      // may be null on older engines
    private final MethodHandle lastError;  // may be null on older engines

    NeedleNative(Path library) {
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(library, libArena);
        } catch (RuntimeException e) {
            throw new NeedleException("Cannot load native engine at " + library
                    + " (native targets ship a static libneedle.a; see README to wrap it as a shared library)", e);
        }
        Linker linker = Linker.nativeLinker();
        load = bind(linker, lookup, "needle_load", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG), true);
        init = bind(linker, lookup, "needle_init", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), true);
        complete = bind(linker, lookup, "needle_complete",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), true);
        reset = bind(linker, lookup, "needle_reset", FunctionDescriptor.ofVoid(), true);
        embed = bind(linker, lookup, "needle_embed", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), false);
        lastError = bind(linker, lookup, "needle_last_error", FunctionDescriptor.of(ADDRESS), false);
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd, boolean required) {
        Optional<MemorySegment> sym = lookup.find(name);
        if (sym.isEmpty()) {
            if (required) throw new NeedleException("Native engine is missing required symbol: " + name);
            return null;
        }
        return linker.downcallHandle(sym.get(), fd);
    }

    boolean hasEmbed() {
        return embed != null;
    }

    int load(MemorySegment blob) {
        try {
            return (int) load.invokeExact(blob, blob.byteSize());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int init(MemorySegment system, MemorySegment toolsJson, MemorySegment toolIndexPath) {
        try {
            return (int) init.invokeExact(system, toolsJson, toolIndexPath);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int complete(MemorySegment text, int maxNewTokens, MemorySegment out, int outLen) {
        try {
            return (int) complete.invokeExact(text, maxNewTokens, out, outLen);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    void reset() {
        try {
            reset.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int embed(MemorySegment text, MemorySegment out, int capacity) {
        if (embed == null) throw new NeedleException("This engine build does not export needle_embed");
        try {
            return (int) embed.invokeExact(text, out, capacity);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Best-effort description of the last failure, or null if the engine has no needle_last_error. */
    String lastError() {
        if (lastError == null) return null;
        try {
            MemorySegment p = (MemorySegment) lastError.invokeExact();
            if (p.equals(MemorySegment.NULL)) return null;
            return p.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- library discovery -------------------------------------------------

    /** Resolution order: explicit path, $NEEDLE3_LIB_PATH, Python package cache, java.library.path. */
    static Path locateLibrary(Path explicit) {
        if (explicit != null) return requireFile(explicit, "library");
        String env = System.getenv("NEEDLE3_LIB_PATH");
        if (env != null && !env.isBlank()) return requireFile(Path.of(env), "NEEDLE3_LIB_PATH");

        Path cache = cacheDir();
        if (Files.isDirectory(cache)) {
            try (Stream<Path> s = Files.walk(cache, 2)) {
                Optional<Path> hit = s.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return (n.startsWith("libneedle") && (n.endsWith(".so") || n.endsWith(".dylib")))
                                    || (n.startsWith("needle") && n.endsWith(".dll"));
                        })
                        .max(java.util.Comparator.comparing(Path::toString)); // highest version dir wins
                if (hit.isPresent()) return hit.get();
            } catch (java.io.IOException ignored) {
                // fall through to the next strategy
            }
        }
        String libPath = System.getProperty("java.library.path", "");
        String mapped = System.mapLibraryName("needle");
        for (String dir : libPath.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, mapped);
            if (Files.isRegularFile(p)) return p;
        }
        throw new NeedleException("Could not find the Needle 3 engine. Set NEEDLE3_LIB_PATH, call "
                + "Needle.builder().library(path), or run `pip install cactus-needle && needle fetch` "
                + "(looked in " + cache + ").");
    }

    static Path locateWeights(Path explicit) {
        if (explicit != null) return requireFile(explicit, "weights");
        Path p = cacheDir().resolve("needle3.cact");
        if (Files.isRegularFile(p)) return p;
        throw new NeedleException("Could not find needle3.cact. Call Needle.builder().weights(path) or run "
                + "`needle download needle3` (looked in " + p + ").");
    }

    static Path cacheDir() {
        return Path.of(System.getProperty("user.home"), ".cache", "cactus-needle", "v3");
    }

    private static Path requireFile(Path p, String what) {
        if (!Files.isRegularFile(p)) throw new NeedleException("Needle " + what + " not found: " + p);
        return p;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new NeedleException("Native call failed", t);
    }
}
