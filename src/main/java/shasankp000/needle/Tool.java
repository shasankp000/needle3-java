package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A tool schema in the shape the engine consumes:
 * {@code {"name", "description", "parameters": {"type":"object","properties":{...},"required":[...]}}}.
 *
 * <p>Needle reads descriptions literally, so prefer narrow tools with plain descriptions.
 */
public final class Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode schema;

    private Tool(ObjectNode schema) {
        this.schema = schema;
    }

    public String name() {
        return schema.path("name").asText();
    }

    public ObjectNode toJson() {
        return schema.deepCopy();
    }

    /** Wrap a raw schema (compact form or OpenAI-style {@code {"type":"function","function":{...}}}). */
    public static Tool fromJson(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!(n instanceof ObjectNode o)) throw new NeedleException("Tool schema must be a JSON object");
            return fromJson(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new NeedleException("Invalid tool JSON", e);
        }
    }

    public static Tool fromJson(ObjectNode schema) {
        ObjectNode copy = schema.deepCopy();
        JsonNode fn = copy.path("function");
        String name = fn.isObject() ? fn.path("name").asText() : copy.path("name").asText();
        if (name.isEmpty()) throw new NeedleException("Tool schema has no name");
        return new Tool(copy);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    // ---- property helpers ---------------------------------------------------

    public static ObjectNode string(String description) {
        return prop("string", description);
    }

    public static ObjectNode bool(String description) {
        return prop("boolean", description);
    }

    public static ObjectNode integer(String description) {
        return prop("integer", description);
    }

    public static ObjectNode integer(String description, long min, long max) {
        ObjectNode p = prop("integer", description);
        p.put("minimum", min).put("maximum", max);
        return p;
    }

    public static ObjectNode number(String description) {
        return prop("number", description);
    }

    /** An ISO date string ({@code YYYY-MM-DD}); enables temporal grounding (see {@link Needle#run}). */
    public static ObjectNode date(String description) {
        ObjectNode p = prop("string", description);
        p.put("format", "date");
        return p;
    }

    /** An ISO date-time string; enables temporal grounding (see {@link Needle#run}). */
    public static ObjectNode dateTime(String description) {
        ObjectNode p = prop("string", description);
        p.put("format", "date-time");
        return p;
    }

    /** A closed set of choices; the decode grammar will not leave it. */
    public static ObjectNode enumOf(String description, String... values) {
        ObjectNode p = prop("string", description);
        ArrayNode a = p.putArray("enum");
        for (String v : values) a.add(v);
        return p;
    }

    private static ObjectNode prop(String type, String description) {
        ObjectNode p = MAPPER.createObjectNode().put("type", type);
        if (description != null && !description.isBlank()) p.put("description", description);
        return p;
    }

    public static final class Builder {
        private final ObjectNode root = MAPPER.createObjectNode();
        private final ObjectNode properties = MAPPER.createObjectNode();
        private final ArrayNode required = MAPPER.createArrayNode();

        private Builder(String name) {
            root.put("name", name);
        }

        public Builder description(String d) {
            root.put("description", d);
            return this;
        }

        /** Add a required argument. */
        public Builder param(String name, ObjectNode propertySchema) {
            return param(name, propertySchema, true);
        }

        public Builder param(String name, ObjectNode propertySchema, boolean isRequired) {
            properties.set(name, propertySchema);
            if (isRequired) required.add(name);
            return this;
        }

        /** Request regexes that force this tool for matching requests (see Needle 3 "Route by pattern"). */
        public Builder triggers(String... regexes) {
            ArrayNode a = root.putArray("triggers");
            for (String r : regexes) a.add(r);
            return this;
        }

        public Tool build() {
            ObjectNode params = MAPPER.createObjectNode().put("type", "object");
            params.set("properties", properties);
            params.set("required", required);
            root.set("parameters", params);
            return new Tool(root);
        }
    }
}
