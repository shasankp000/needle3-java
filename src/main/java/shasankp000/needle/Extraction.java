package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One-shot structured extraction: given a {@link Tool} schema and a piece of text, run a single turn and
 * return the arguments the engine filled in (or {@code null} on a refusal). Port of the Python package's
 * module-level {@code extract()} function.
 *
 * <p>Opens its own {@link Needle} instance for the call (weights load + {@code needle_init}) and closes it
 * before returning, so it is no cheaper than a normal {@code Needle} turn and, like any {@code Needle},
 * cannot run while another instance is already open in this JVM — close that one first, or call
 * {@link Needle#extract} on it instead to reuse its already-loaded engine and tool set.
 *
 * <pre>{@code
 * Tool trip = Tool.named("trip")
 *     .description("A trip a user wants to plan")
 *     .param("destination", Tool.string(null))
 *     .param("date", Tool.date("the day of travel"))
 *     .build();
 *
 * JsonNode args = Extraction.extract(trip, "book me a flight to Lagos for March 20 2026");
 * }</pre>
 */
public final class Extraction {
    private Extraction() {
    }

    /** {@link #extract(Tool, String, Options)} with default options (strict, no explicit weights/library). */
    public static JsonNode extract(Tool schema, String text) {
        return extract(schema, text, new Options());
    }

    public static JsonNode extract(Tool schema, String text, Options opts) {
        try (Needle agent = Needle.builder()
                .tools(schema)
                .system(opts.system)
                .weights(opts.weights)
                .library(opts.library)
                .build()) {
            return agent.extract(schema, text, opts);
        }
    }

    /**
     * Like {@link #extract(Tool, String)} but deserialises the arguments into {@code type} with Jackson.
     * Returns {@code null} on a refusal, exactly like the {@link JsonNode}-returning overloads.
     */
    public static <T> T extractAs(Class<T> type, Tool schema, String text, Options opts) {
        JsonNode args = extract(schema, text, opts);
        if (args == null) return null;
        try {
            return new ObjectMapper().treeToValue(args, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new NeedleException("Extracted arguments do not match " + type.getSimpleName(), e);
        }
    }

    public static <T> T extractAs(Class<T> type, Tool schema, String text) {
        return extractAs(type, schema, text, new Options());
    }

    /** Runs the extraction turn on an already-open {@code agent} and applies the grounding checks. */
    static JsonNode run(Needle agent, Tool schema, String text, Options opts) {
        NeedleResponse r = agent.complete(text, opts.maxNewTokens);
        List<NeedleResponse.FunctionCall> calls =
                !r.functionCalls().isEmpty() ? r.functionCalls() : r.suppressedCalls();
        if (calls.isEmpty()) return null;
        JsonNode arguments = calls.get(0).arguments();
        if (opts.strict) {
            Set<String> failures = Grounding.extractionFailures(
                    text, schema.toJson().path("parameters"), arguments, r, opts.system);
            if (!failures.isEmpty()) {
                throw new NeedleExtractionException(
                        "extraction returned values not grounded in the input: " + String.join(", ", failures));
            }
        }
        return arguments;
    }

    /** Options for {@link #extract(Tool, String, Options)}. Weights/library default like {@link Needle.Builder}. */
    public static final class Options {
        Path weights;
        Path library;
        String system;
        int maxNewTokens = 512;
        boolean strict = true;

        public Options weights(Path p) {
            this.weights = p;
            return this;
        }

        public Options library(Path p) {
            this.library = p;
            return this;
        }

        /** Environment facts, never instructions; also used to license a relative date's year. */
        public Options system(String facts) {
            this.system = facts;
            return this;
        }

        public Options maxNewTokens(int n) {
            this.maxNewTokens = n;
            return this;
        }

        /** Raise {@link NeedleExtractionException} instead of silently returning ungrounded values. Default true. */
        public Options strict(boolean v) {
            this.strict = v;
            return this;
        }
    }
}
