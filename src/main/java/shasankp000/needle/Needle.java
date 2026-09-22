package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.TreeSet;

/**
 * Java entry point for Needle 3. Wraps the native engine, which owns ONE process-global model and
 * conversation, so at most one {@code Needle} may be open per JVM at a time.
 *
 * <pre>{@code
 * Tool setLights = Tool.named("set_lights")
 *     .description("Turn a room's lights on or off and set brightness")
 *     .param("room", Tool.string("which room to control"))
 *     .param("on", Tool.bool(null))
 *     .param("brightness", Tool.integer(null, 0, 100), false)
 *     .build();
 *
 * try (Needle needle = Needle.builder().tools(setLights).build()) {
 *     NeedleResponse r = needle.complete("dim the living room to 30");
 * }
 * }</pre>
 *
 * Run the JVM with {@code --enable-native-access=ALL-UNNAMED} to silence the FFM warning.
 */
public final class Needle implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicBoolean IN_USE = new AtomicBoolean(false);
    private static final DateTimeFormatter DATE_FACT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.ENGLISH);

    /** Executes one tool call. Return any value Jackson can serialise; throw to report an error to the model. */
    @FunctionalInterface
    public interface ToolHandler {
        Object call(JsonNode arguments) throws Exception;
    }

    /** Outcome of {@link #run}: the last engine turn plus every tool result, in order. */
    public record RunResult(NeedleResponse last, List<JsonNode> results, NeedleResponse.Verdict verdict) {
    }

    private final NeedleNative nativeLib;
    private final Arena weightsArena = Arena.ofShared(); // weights are read in place; keep mapped until close()
    private final int bufferSize;
    private final int defaultMaxNewTokens;
    private final int prefixTokens;
    private final String systemText; // includes the auto date fact; used by the grounding check
    private final Map<String, JsonNode> toolParametersByName; // for temporal (year) grounding
    private final Set<Integer> seenYears = new HashSet<>(); // years written anywhere in this conversation so far
    private volatile boolean closed;

    private Needle(Builder b) {
        if (!IN_USE.compareAndSet(false, true)) {
            throw new NeedleException("Another Needle instance is open in this JVM; the engine is process-global. "
                    + "Close it first (or reuse it and call reset()).");
        }
        try {
            this.bufferSize = b.bufferSize;
            this.defaultMaxNewTokens = b.maxNewTokens;

            Path libPath = b.library;
            Path weightsPath = b.weights;
            if (b.autoDownload) {
                NeedleAssets.Options ao = b.assetOptions != null ? b.assetOptions : new NeedleAssets.Options();
                String envLib = System.getenv("NEEDLE3_LIB_PATH");
                if (libPath == null && (envLib == null || envLib.isBlank())) libPath = NeedleAssets.ensureLibrary(ao);
                if (weightsPath == null) weightsPath = NeedleAssets.ensureWeights(ao);
            }
            this.nativeLib = new NeedleNative(NeedleNative.locateLibrary(libPath));

            Path weights = NeedleNative.locateWeights(weightsPath);

            MemorySegment blob;
            try (FileChannel ch = FileChannel.open(weights, StandardOpenOption.READ)) {
                blob = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), weightsArena);
            } catch (IOException e) {
                throw new NeedleException("Cannot read weights " + weights, e);
            }
            int rc = nativeLib.load(blob);
            if (rc != 0) throw failure("needle_load failed for " + weights, rc);

            String system = withDateFact(b.system, b.autoDate);
            this.systemText = system == null ? "" : system;
            Map<String, JsonNode> params = new LinkedHashMap<>();
            for (Tool t : b.tools) params.put(t.name(), t.toJson().path("parameters"));
            this.toolParametersByName = Map.copyOf(params);
            String toolsJson = toolsToJson(b.tools);
            try (Arena a = Arena.ofConfined()) {
                int n = nativeLib.init(
                        system == null ? MemorySegment.NULL : a.allocateFrom(system),
                        b.tools.isEmpty() ? MemorySegment.NULL : a.allocateFrom(toolsJson),
                        b.toolIndexPath == null ? MemorySegment.NULL : a.allocateFrom(b.toolIndexPath.toString()));
                if (n < 0) throw failure("needle_init failed", n);
                this.prefixTokens = n;
            }
        } catch (RuntimeException | Error e) {
            weightsArena.close();
            IN_USE.set(false);
            throw e;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Tokenised static-prefix length (system + tools) reported by {@code needle_init}. */
    public int prefixTokens() {
        return prefixTokens;
    }

    // ---- core API -----------------------------------------------------------

    public NeedleResponse complete(String text) {
        return complete(text, defaultMaxNewTokens);
    }

    /**
     * One turn. Feed a tool result back as the next turn by passing its JSON text; per the docs a turn after
     * a call is treated as a tool result only when it parses as JSON, otherwise it is a new user turn.
     *
     * <p>Also runs the client-side temporal (year) grounding check ported from the Python package: a
     * {@code format: date}/{@code date-time} argument whose year contradicts every year written so far in
     * this conversation (or, for a relative phrase with no year of its own, the system date fact) is added
     * to {@link NeedleResponse#ungrounded()} even when the engine did not flag it itself, and conversely a
     * value the engine flagged but whose year IS confirmed by the input is left alone. See
     * {@link Grounding#annotateTemporal}.
     */
    public NeedleResponse complete(String text, int maxNewTokens) {
        return complete(text, maxNewTokens, true);
    }

    synchronized NeedleResponse complete(String text, int maxNewTokens, boolean ground) {
        ensureOpen();
        seenYears.addAll(Grounding.sourceYears(text));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocateFrom(text);
            MemorySegment out = a.allocate(bufferSize); // zero-filled, so the NUL terminator is guaranteed
            int rc = nativeLib.complete(in, maxNewTokens, out, bufferSize);
            if (rc < 0) throw failure("needle_complete failed", rc);
            String json = out.getString(0);
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(json);
            } catch (IOException e) {
                throw new NeedleException("Engine returned unparseable JSON: " + json, e);
            }
            if (ground && parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode root) {
                Grounding.annotateTemporal(root, toolParametersByName, seenYears, systemText, text);
            }
            return NeedleResponse.parse(parsed);
        }
    }

    /** {@link #run(String, Map, int, boolean, Double)} with {@code strict = true} and no confidence gate. */
    public RunResult run(String query, Map<String, ToolHandler> handlers, int maxSteps) {
        return run(query, handlers, maxSteps, true, null);
    }

    /** {@link #run(String, Map, int, boolean, Double)} with no confidence gate. */
    public RunResult run(String query, Map<String, ToolHandler> handlers, int maxSteps, boolean strict) {
        return run(query, handlers, maxSteps, strict, null);
    }

    /**
     * The agentic loop: the model picks calls, you execute them via {@code handlers}, results are fed back,
     * until the model answers {@code respond} or {@code maxSteps} is hit.
     *
     * <p>Results from each turn are always fed back as one JSON array in call order, even for a single
     * call, matching the Python package.
     *
     * <p>With {@code strict}, a call whose numeric or temporal (year) argument was flagged ungrounded
     * (engine-native or {@linkplain #complete(String, int) client-side}) is NOT executed unless that exact
     * number is written in {@code query} or the system text; the model gets
     * {@code {"error":"ungrounded <paths>"}} instead. Tool-result turns fed back into the conversation are
     * not themselves re-grounded (they are not user input), matching the Python package.
     *
     * <p>With a non-null {@code minConfidence}, the FIRST turn is passed through
     * {@link NeedleResponse#verdict(double)}; anything other than EXECUTE returns immediately with no handler
     * called, {@code results} empty and {@code last} holding that turn, so the caller can ask the user or
     * escalate. With {@code null} there is no gate and {@link RunResult#verdict()} is null.
     */
    public synchronized RunResult run(String query, Map<String, ToolHandler> handlers, int maxSteps,
                                      boolean strict, Double minConfidence) {
        List<JsonNode> results = new ArrayList<>();
        NeedleResponse r = complete(query);
        NeedleResponse.Verdict verdict = null;
        if (minConfidence != null) {
            verdict = r.verdict(minConfidence);
            if (verdict != NeedleResponse.Verdict.EXECUTE) return new RunResult(r, results, verdict);
        }
        for (int step = 0; step < maxSteps && r.isCall() && !r.functionCalls().isEmpty(); step++) {
            Map<String, Set<String>> flagged = Grounding.ungroundedByTool(r);
            ArrayNode turn = MAPPER.createArrayNode();
            for (NeedleResponse.FunctionCall c : r.functionCalls()) {
                JsonNode out = null;
                if (strict) {
                    Set<String> bad = new TreeSet<>(flagged.getOrDefault(c.name(), Set.of()));
                    if (!bad.isEmpty()) {
                        bad.removeAll(Grounding.groundedNumberPaths(c.arguments(), query, systemText));
                        if (!bad.isEmpty()) {
                            out = MAPPER.createObjectNode().put("error", "ungrounded " + String.join(", ", bad));
                        }
                    }
                }
                if (out == null) {
                    ToolHandler h = handlers.get(c.name());
                    if (h == null) {
                        out = MAPPER.createObjectNode().put("error", "no handler for " + c.name());
                    } else {
                        try {
                            out = MAPPER.valueToTree(h.call(c.arguments()));
                        } catch (Exception e) {
                            out = MAPPER.createObjectNode().put("error", String.valueOf(e.getMessage()));
                        }
                    }
                }
                turn.add(out);
                results.add(out);
            }
            r = complete(turn.toString(), defaultMaxNewTokens, false);
        }
        return new RunResult(r, results, verdict);
    }

    /**
     * One-shot structured extraction reusing this already-open instance's engine, for when {@code schema} is
     * already one of the tools this {@code Needle} was built with (so its weights/library/system are already
     * loaded, unlike the standalone {@link Extraction#extract(Tool, String)} which opens and closes its own
     * instance). Applies the same grounding checks; see {@link Extraction} for details.
     *
     * @throws IllegalArgumentException if {@code schema} was not one of this instance's declared tools
     */
    public synchronized JsonNode extract(Tool schema, String text, Extraction.Options opts) {
        ensureOpen();
        if (!toolParametersByName.containsKey(schema.name())) {
            throw new IllegalArgumentException(
                    "\"" + schema.name() + "\" is not one of this Needle instance's declared tools");
        }
        return Extraction.run(this, schema, text, opts);
    }

    public JsonNode extract(Tool schema, String text) {
        return extract(schema, text, new Extraction.Options());
    }

    /** Embedding for text or a serialised tool schema (3072 floats on the shipped checkpoint). */
    public synchronized float[] embed(String text) {
        ensureOpen();
        if (!nativeLib.hasEmbed()) throw new NeedleException("This engine build does not export needle_embed");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocateFrom(text);
            int dim = nativeLib.embed(in, MemorySegment.NULL, 0); // null output => dimension
            if (dim < 0) throw failure("needle_embed failed", dim);
            MemorySegment out = a.allocate(4L * dim, 4);
            int n = nativeLib.embed(in, out, dim);
            if (n < 0) throw failure("needle_embed failed", n);
            return out.asSlice(0, 4L * n).toArray(java.lang.foreign.ValueLayout.JAVA_FLOAT);
        }
    }

    /** Rewind the conversation, keep the tools loaded. Also forgets years seen so far (temporal grounding). */
    public synchronized void reset() {
        ensureOpen();
        nativeLib.reset();
        seenYears.clear();
    }

    /**
     * Releases the mapped weights. The engine cannot unload weights, so a second {@code Needle} in the same JVM
     * is best avoided; prefer one long-lived instance plus {@link #reset()}.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        weightsArena.close();
        IN_USE.set(false);
    }

    // ---- internals ----------------------------------------------------------

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Needle is closed");
    }

    private NeedleException failure(String what, int code) {
        String detail = nativeLib.lastError();
        return new NeedleException(what + " (code " + code + ")" + (detail == null ? "" : ": " + detail), code, null);
    }

    private static String toolsToJson(List<Tool> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        tools.forEach(t -> arr.add(t.toJson()));
        return arr.toString();
    }

    private static String withDateFact(String system, boolean autoDate) {
        if (!autoDate) return system;
        if (system != null && system.contains("date:")) return system;
        String fact = "date: " + LocalDateTime.now().format(DATE_FACT);
        return system == null || system.isBlank() ? fact : fact + "; " + system;
    }

    // ---- builder ------------------------------------------------------------

    public static final class Builder {
        private final List<Tool> tools = new ArrayList<>();
        private String system;
        private Path weights;
        private Path library;
        private Path toolIndexPath;
        private boolean autoDate = true;
        private boolean autoDownload = false;
        private NeedleAssets.Options assetOptions;
        private int bufferSize = 65536;
        private int maxNewTokens = 512;

        public Builder tools(Tool... ts) {
            tools.addAll(List.of(ts));
            return this;
        }

        public Builder tools(List<Tool> ts) {
            tools.addAll(ts);
            return this;
        }

        /** Download the engine and weights from Hugging Face on first use if they are not already cached. */
        public Builder autoDownload(boolean v) {
            this.autoDownload = v;
            return this;
        }

        /** Enables autoDownload with a custom cache directory, progress callback, mirror or checksum pins. */
        public Builder assets(NeedleAssets.Options o) {
            this.assetOptions = o;
            this.autoDownload = true;
            return this;
        }

        /** Environment facts, never instructions. Keys: date, locale, device, battery, network, location, user, assistant. */
        public Builder system(String facts) {
            this.system = facts;
            return this;
        }

        /** Path to needle3.cact (or a tuned archive). Defaults to the Python package cache. */
        public Builder weights(Path p) {
            this.weights = p;
            return this;
        }

        /** Path to the shared engine library. Defaults to $NEEDLE3_LIB_PATH, then the Python package cache. */
        public Builder library(Path p) {
            this.library = p;
            return this;
        }

        public Builder toolIndexPath(Path p) {
            this.toolIndexPath = p;
            return this;
        }

        /** Prefix a {@code date:} fact automatically, like the Python package. Default true. */
        public Builder autoDate(boolean v) {
            this.autoDate = v;
            return this;
        }

        public Builder bufferSize(int bytes) {
            this.bufferSize = bytes;
            return this;
        }

        public Builder maxNewTokens(int n) {
            this.maxNewTokens = n;
            return this;
        }

        public Needle build() {
            return new Needle(this);
        }
    }
}
