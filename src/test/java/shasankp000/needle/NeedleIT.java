package shasankp000.needle;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

class NeedleIT {
    static Needle needle;

    @BeforeAll static void up() {
        Tool lights = Tool.named("set_lights")
                .description("Turn a room's lights on or off and set brightness")
                .param("room", Tool.string(null))                              // was Tool.string("which room to control")
                .param("on", Tool.bool(null))
                .param("brightness", Tool.integer(null, 0, 100), false).build();
        needle = Needle.builder().tools(lights).autoDate(false).build();   // added .autoDate(false)
    }
    @AfterAll static void down() { needle.close(); }
    @BeforeEach void fresh() { needle.reset(); }

    @Test void initReportsPrefix() { assertTrue(needle.prefixTokens() > 0); }

    @Test void picksToolAndFillsArgs() {
        var r = needle.complete("dim the living room to 30");
        assertEquals(1, r.functionCalls().size());
        var c = r.functionCalls().get(0);
        assertEquals("set_lights", c.name());
        assertEquals(30, c.arguments().path("brightness").asInt());
        assertTrue(c.arguments().path("room").asText().contains("living"));
    }

    @Test void offTopicIsRefusal() {
        assertTrue(needle.complete("what is the capital of France?").isRefusal());
    }

    @Test void deterministicAfterReset() {
        var a = needle.complete("turn on the kitchen lights").raw().path("function_calls");
        needle.reset();
        var b = needle.complete("turn on the kitchen lights").raw().path("function_calls");
        assertEquals(a, b);
    }

    @Test void embedShape() {
        float[] v = needle.embed("set an alarm");
        assertEquals(3072, v.length);              // per the porting notes
        double n = 0; for (float x : v) n += x * x;
        assertEquals(1.0, Math.sqrt(n), 1e-3);     // unit norm
    }

    @Test void soakNoCrashOrLeak() {
        for (int i = 0; i < 2000; i++) { needle.reset(); needle.complete("dim the bedroom to " + (i % 100)); }
    }

    @Test void parityWithPython() throws Exception {
        JsonNode expected = new ObjectMapper().readTree("""
            {
              "dim the living room to 30": [
                {"name": "set_lights", "arguments": {"room": "living room", "brightness": 30, "on": false}}
              ],
              "turn on the kitchen lights": [
                {"name": "set_lights", "arguments": {"room": "kitchen", "on": true}}
              ],
              "turn off the bedroom": [
                {"name": "set_lights", "arguments": {"room": "bedroom", "on": false}}
              ],
              "what is the capital of France?": []
            }
            """);

        expected.fieldNames().forEachRemaining(query -> {
            needle.reset();
            JsonNode actual = needle.complete(query).raw().path("function_calls");
            assertEquals(expected.get(query), actual, "mismatch for: " + query);
        });
    }

    @Test void confidenceMatchesPython() {
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("dim the living room to 30", 0.508);
        expected.put("turn on the kitchen lights", 0.7579);
        expected.put("turn off the bedroom", 1.0);
        expected.put("what is the capital of France?", 1.0);

        expected.forEach((q, exp) -> {
            needle.reset();
            Double got = needle.complete(q).confidence();
            System.out.println("CONF " + q + " | " + got);
            assertNotNull(got, q);
            assertEquals(exp, got, 1e-3, q);
        });
    }

    @Test void whatDoesEngineFlag() {
        for (String q : new String[]{"dim the living room", "set the bedroom lights to a comfortable brightness",
                "dim the living room to 30"}) {
            needle.reset();
            System.out.println("VALID " + q + " | " + needle.complete(q).raw().path("validation"));
        }
    }

    @Test void whatDoesNegationLookLike() {
        for (String q : new String[]{"don't turn on the kitchen lights", "turn on the kitchen lights"}) {
            needle.reset();
            var r = needle.complete(q);
            System.out.println("NEG " + q + " | calls=" + r.raw().path("function_calls")
                    + " | validation=" + r.raw().path("validation"));
        }
    }
}