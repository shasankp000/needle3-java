package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One turn from the engine. An empty {@link #functionCalls()} on a {@code "call"} turn is a refusal:
 * there is no free-text fallback, so always handle it.
 */
public record NeedleResponse(
        String type,                        // "call" or "respond"
        boolean success,
        String error,
        Integer errorCode,
        List<FunctionCall> functionCalls,
        List<FunctionCall> suppressedCalls, // withheld by the confidence floor / grounding gate
        String reasoning,
        Double confidence,                  // null for tuned weights
        double prefillTps,
        double decodeTps,
        double peakRamMb,
        JsonNode raw) {

    public record FunctionCall(String name, JsonNode arguments) {
    }

    public boolean isCall() {
        return "call".equals(type);
    }

    public boolean isRefusal() {
        return functionCalls.isEmpty() && suppressedCalls.isEmpty();
    }

    /** {@code "tool.argPath"} entries the engine flagged in {@code validation.ungrounded}; usually empty. */
    public List<String> ungrounded() {
        List<String> out = new ArrayList<>();
        JsonNode u = raw.path("validation").path("ungrounded");
        if (u.isArray()) for (JsonNode x : u) out.add(x.asText());
        return List.copyOf(out);
    }

    /** True when the engine detected a negated request; the call may not mean what a naive reading says. */
    public boolean negation() {
        return raw.path("validation").path("negation").asBoolean(false);
    }

    /** The engine withheld a call because an argument value was not in the input; ask the user for it. */
    public boolean needsClarification() {
        return functionCalls.isEmpty() && !ungrounded().isEmpty();
    }

    public enum Verdict {
        /** Run the calls. */
        EXECUTE,
        /** A value is missing from the request; ask the user for it. */
        ASK_USER,
        /** Needle is not sure or refused; hand the request to a planner (or ignore it). */
        ESCALATE
    }

    /**
     * A conservative default policy: execute only clean, confident, non-negated calls; ask when a value is
     * missing but the tool choice looks confident; otherwise escalate. {@code minConfidence} is ignored when
     * the weights report no confidence (tuned weights). Tune it on your own in-scope and out-of-scope phrases.
     * Do not apply it to the turn that follows a tool result: that turn's confidence is not meaningful.
     */
    public Verdict verdict(double minConfidence) {
        if (negation()) return Verdict.ESCALATE;
        boolean confident = confidence == null || confidence >= minConfidence;
        if (!functionCalls.isEmpty()) return confident ? Verdict.EXECUTE : Verdict.ESCALATE;
        if (needsClarification() && confident) return Verdict.ASK_USER;
        return Verdict.ESCALATE;
    }

    static NeedleResponse parse(JsonNode n) {
        return new NeedleResponse(
                n.path("type").asText(""),
                n.path("success").asBoolean(true),
                n.path("error").isNull() || n.path("error").isMissingNode() ? null : n.path("error").asText(),
                n.path("error_code").isNumber() ? n.path("error_code").asInt() : null,
                calls(n.path("function_calls")),
                calls(n.path("suppressed_calls")),
                n.path("reasoning").asText(""),
                n.path("confidence").isNumber() ? n.path("confidence").asDouble() : null,
                n.path("prefill_tps").asDouble(0),
                n.path("decode_tps").asDouble(0),
                n.path("peak_ram_mb").asDouble(0),
                n);
    }

    private static List<FunctionCall> calls(JsonNode arr) {
        List<FunctionCall> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode c : arr) out.add(new FunctionCall(c.path("name").asText(), c.path("arguments")));
        }
        return List.copyOf(out);
    }
}
