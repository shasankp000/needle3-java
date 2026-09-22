package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the Python package's grounding checks: a number in a call's arguments that the engine flags as
 * ungrounded is tolerated only if the same value is literally written in the query or system text
 * ({@link #groundedNumberPaths}); a {@code format: date}/{@code date-time} argument whose year contradicts
 * every year written in the conversation (or, for a relative phrase like "next Friday", the system's date
 * fact) is flagged by {@link #annotateTemporal}, matching the Python package's own client-side check (the
 * engine's {@code validation.ungrounded} does not cover this on its own).
 */
final class Grounding {
    private Grounding() {
    }

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.,])(?:[-+]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|[-+]?\\d+(?:\\.\\d+)?)(?!\\d)",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern DATE_FACT = Pattern.compile(
            "date:\\s*\\d{4}-\\d{2}-\\d{2}(?:\\s+[A-Za-z]{3})?(?:\\s+\\d{2}:\\d{2})?"
                    + "|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code "tool.path"} entries from {@code validation.ungrounded}, grouped by tool name. */
    static Map<String, Set<String>> ungroundedByTool(NeedleResponse r) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (String name : r.ungrounded()) {
            int dot = name.indexOf('.');
            String tool = dot < 0 ? name : name.substring(0, dot);
            String path = dot < 0 ? "" : name.substring(dot + 1);
            grouped.computeIfAbsent(tool, k -> new HashSet<>()).add(path.isEmpty() ? tool : path);
        }
        return grouped;
    }

    /** Argument paths whose numeric value is literally present in one of the sources. */
    static Set<String> groundedNumberPaths(JsonNode arguments, String... sources) {
        Set<BigDecimal> numbers = sourceNumbers(sources);
        Set<String> grounded = new HashSet<>();
        if (numbers.isEmpty()) return grounded;
        Map<String, BigDecimal> leaves = new TreeMap<>();
        numericLeaves(arguments, "", leaves);
        leaves.forEach((path, value) -> {
            if (numbers.contains(value.stripTrailingZeros())) grounded.add(path);
        });
        return grounded;
    }

    static Set<BigDecimal> sourceNumbers(String... sources) {
        StringBuilder sb = new StringBuilder();
        for (String s : sources) {
            if (s == null || s.isEmpty()) continue;
            String cleaned = DATE_FACT.matcher(s).replaceAll(" ");
            if (cleaned.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(cleaned);
        }
        Set<BigDecimal> out = new HashSet<>();
        Matcher m = NUMBER.matcher(sb);
        while (m.find()) {
            try {
                out.add(new BigDecimal(m.group().replace(",", "")).stripTrailingZeros());
            } catch (NumberFormatException ignored) {
                // not a number after all; skip
            }
        }
        return out;
    }

    private static void numericLeaves(JsonNode n, String path, Map<String, BigDecimal> out) {
        if (n == null || n.isBoolean()) return;
        if (n.isNumber()) {
            out.put(path, n.decimalValue());
        } else if (n.isObject()) {
            n.fields().forEachRemaining(e ->
                    numericLeaves(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) numericLeaves(n.get(i), path + "[" + i + "]", out);
        }
    }

    // ---- temporal (year) grounding -------------------------------------------------------------------

    private static final String MONTHS =
            "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?"
                    + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    private static final List<Pattern> YEAR_PATTERNS = List.of(
            Pattern.compile("\\b\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTHS + "[\\s,]+(\\d{1,4})(?![0-9A-Za-z])",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b" + MONTHS + "\\s+\\d{1,2}(?:st|nd|rd|th)?\\s*,?\\s+(\\d{1,4})(?![0-9A-Za-z])",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b" + MONTHS + "[\\s,]+(\\d{3,4})(?![0-9A-Za-z])", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byear\\s+(\\d{1,4})(?![0-9A-Za-z])", Pattern.CASE_INSENSITIVE),
            // Year-first numeric dates only (2024-03-15, 2024/03/15); a day- or month-first date such as
            // 5/6/24 must not mint its leading component as a year.
            Pattern.compile("(?<![0-9])(\\d{4})(?=[-/]\\d{1,2}[-/]\\d{1,2}(?![0-9]))"));

    private static final Pattern RELATIVE_CUE = Pattern.compile(
            "\\b(today|tonight|tomorrow|yesterday|next|this|coming|now|in \\d+ (?:days?|weeks?|months?|years?)|"
                    + "monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ISO_LEADING_YEAR = Pattern.compile("^(\\d{4})-");

    /** Years written literally in {@code text} (month names, "year N", or a year-first ISO date). */
    static Set<Integer> sourceYears(String text) {
        Set<Integer> years = new HashSet<>();
        if (text == null || text.isEmpty()) return years;
        for (Pattern p : YEAR_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                try {
                    years.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // not a plausible year; skip
                }
            }
        }
        return years;
    }

    /** Whether {@code text} contains a relative-time cue ("tomorrow", "next", a weekday, ...). */
    static boolean relativeCue(String text) {
        return text != null && !text.isEmpty() && RELATIVE_CUE.matcher(text).find();
    }

    /**
     * Years a date argument may carry: the ones already seen in the conversation, plus the system date's
     * year when the input reasons relatively ("tomorrow", "next week") and at least one year is already
     * known (an empty {@code seenYears} means there is nothing to reason relative to, and system years are
     * not licensed on their own).
     */
    static Set<Integer> licensedYears(Set<Integer> seenYears, String system, boolean relative) {
        Set<Integer> years = new HashSet<>(seenYears);
        if (!years.isEmpty() && system != null && relative) {
            years.addAll(sourceYears(system));
        }
        return years;
    }

    /** Result of walking a tool's arguments against its schema for date/date-time fields. */
    record TemporalCheck(Set<String> checked, Set<String> failures) {
    }

    /** Resolve a JSON Schema {@code $ref} (relative to {@code root}), following chains but not cycles. */
    static JsonNode resolveRef(JsonNode node, JsonNode root) {
        Set<String> seen = new HashSet<>();
        while (node != null && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (!seen.add(ref)) break;
            JsonNode target = root;
            String pointer = ref.startsWith("#/") ? ref.substring(2) : ref;
            for (String part : pointer.split("/")) {
                if (part.isEmpty()) continue;
                String key = part.replace("~1", "/").replace("~0", "~");
                target = target.path(key);
            }
            if (target == node) break;
            node = target;
        }
        return node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node;
    }

    /** Walk {@code arguments} against {@code schemaParameters}, checking every {@code format: date(-time)} leaf. */
    static TemporalCheck walkGrounding(JsonNode schemaParameters, JsonNode arguments, Set<Integer> years) {
        Set<String> checked = new TreeSet<>();
        Set<String> failures = new TreeSet<>();
        walk(arguments, schemaParameters, "", schemaParameters, years, checked, failures);
        return new TemporalCheck(checked, failures);
    }

    private static void walk(JsonNode value, JsonNode node, String path, JsonNode root, Set<Integer> years,
                             Set<String> checked, Set<String> failures) {
        node = resolveRef(node, root);
        JsonNode variants = node.has("anyOf") ? node.get("anyOf") : node.has("oneOf") ? node.get("oneOf") : null;
        if (variants != null && variants.isArray()) {
            List<JsonNode> concrete = new ArrayList<>();
            for (JsonNode v : variants) {
                if (!"null".equals(resolveRef(v, root).path("type").asText(""))) concrete.add(v);
            }
            if (concrete.size() == 1) node = resolveRef(concrete.get(0), root);
        }
        String fmt = node.path("format").asText("");
        if (("date".equals(fmt) || "date-time".equals(fmt)) && value != null && value.isTextual()) {
            Matcher m = ISO_LEADING_YEAR.matcher(value.asText());
            if (m.find() && !years.isEmpty()) {
                checked.add(path);
                if (!years.contains(Integer.parseInt(m.group(1)))) failures.add(path);
            }
            return;
        }
        if (value != null && value.isObject()) {
            JsonNode properties = node.path("properties");
            var it = value.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (properties.has(e.getKey())) {
                    String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                    walk(e.getValue(), properties.get(e.getKey()), child, root, years, checked, failures);
                }
            }
        } else if (value != null && value.isArray() && node.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                walk(value.get(i), node.get("items"), path + "[" + i + "]", root, years, checked, failures);
            }
        }
    }

    /**
     * Appends any temporally-ungrounded {@code "tool.path"} entries to {@code responseRoot}'s
     * {@code validation.ungrounded} array, mirroring the Python package's {@code _annotate_ungrounded}.
     * A no-op when there is nothing to check against (no calls, or no year known anywhere yet).
     */
    static void annotateTemporal(ObjectNode responseRoot, Map<String, JsonNode> toolParametersByName,
                                 Set<Integer> seenYears, String system, String text) {
        JsonNode calls = responseRoot.path("function_calls");
        boolean relative = text == null || relativeCue(text);
        Set<Integer> years = licensedYears(seenYears, system, relative);
        if (!calls.isArray() || calls.isEmpty() || years.isEmpty()) return;

        List<String> found = new ArrayList<>();
        for (JsonNode call : calls) {
            String name = call.path("name").asText();
            JsonNode params = toolParametersByName.get(name);
            if (params == null) continue;
            TemporalCheck tc = walkGrounding(params, call.path("arguments"), years);
            for (String p : tc.failures()) found.add(name + "." + p);
        }
        if (found.isEmpty()) return;

        ObjectNode validation = responseRoot.has("validation") && responseRoot.get("validation").isObject()
                ? (ObjectNode) responseRoot.get("validation") : responseRoot.putObject("validation");
        ArrayNode ungrounded = validation.has("ungrounded") && validation.get("ungrounded").isArray()
                ? (ArrayNode) validation.get("ungrounded") : validation.putArray("ungrounded");
        Set<String> existing = new LinkedHashSet<>();
        ungrounded.forEach(n -> existing.add(n.asText()));
        for (String f : found) {
            if (existing.add(f)) ungrounded.add(f);
        }
    }

    /**
     * Everything {@link Extraction#extract} should treat as an ungrounded value for one schema/arguments
     * pair: temporal failures from {@code text} alone (no cross-turn memory, unlike {@link #annotateTemporal}),
     * plus any engine- or temporally-flagged path in {@code response.ungrounded()} that isn't provably
     * grounded by a literal number, plus {@code "negated request"} when the engine reports negation. Ports
     * the Python package's {@code _validate_extraction}.
     */
    static Set<String> extractionFailures(String text, JsonNode schemaParameters, JsonNode arguments,
                                          NeedleResponse response, String system) {
        Set<Integer> years = licensedYears(sourceYears(text), system, relativeCue(text));
        TemporalCheck tc = walkGrounding(schemaParameters, arguments, years);
        Set<String> failures = new TreeSet<>(tc.failures());

        List<String> flagged = response.ungrounded();
        Set<String> groundedNums = flagged.isEmpty() ? Set.of() : groundedNumberPaths(arguments, text, system);
        for (String nameDotPath : flagged) {
            int dot = nameDotPath.indexOf('.');
            String path = dot < 0 ? nameDotPath : nameDotPath.substring(dot + 1);
            if (groundedNums.contains(path)) continue;
            if (!tc.checked().contains(path) || tc.failures().contains(path)) failures.add(path);
        }
        if (response.negation()) failures.add("negated request");
        return failures;
    }
}
