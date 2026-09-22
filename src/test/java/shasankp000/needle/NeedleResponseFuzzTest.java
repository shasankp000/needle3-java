package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The README notes that the {@code type}/{@code success}/{@code error} envelope is read defensively but was
 * not exhaustively fuzzed against malformed engine output. This exercises {@link NeedleResponse#parse} and
 * {@link Grounding#annotateTemporal} against a battery of deliberately broken, partial or oddly-typed
 * envelopes: none of these should ever throw, since a real engine bug or a version skew should surface as a
 * best-effort/degraded {@link NeedleResponse}, not an uncaught exception from deep inside parsing.
 */
class NeedleResponseFuzzTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test void emptyObjectParsesToSafeDefaults() throws Exception {
        NeedleResponse r = NeedleResponse.parse(M.readTree("{}"));
        assertEquals("", r.type());
        assertTrue(r.success()); // Python/engine default is success unless told otherwise
        assertNull(r.error());
        assertNull(r.errorCode());
        assertTrue(r.functionCalls().isEmpty());
        assertTrue(r.suppressedCalls().isEmpty());
        assertNull(r.confidence());
        assertTrue(r.ungrounded().isEmpty());
        assertFalse(r.negation());
        assertTrue(r.isRefusal());
        assertFalse(r.isCall());
        assertFalse(r.needsClarification());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"just a string\"", "42", "null", "true"})
    void nonObjectTopLevelNeverThrows(String json) throws Exception {
        assertDoesNotThrow(() -> {
            NeedleResponse r = NeedleResponse.parse(M.readTree(json));
            r.isRefusal();
            r.ungrounded();
            r.negation();
        });
    }

    @Test void functionCallsOfTheWrongShapeAreTreatedAsAbsent() throws Exception {
        for (String badCalls : new String[]{"\"call\"", "42", "null", "{}", "true"}) {
            JsonNode n = M.readTree("{\"type\":\"call\",\"function_calls\":" + badCalls + "}");
            NeedleResponse r = NeedleResponse.parse(n);
            assertTrue(r.functionCalls().isEmpty(), badCalls);
        }
    }

    @Test void callEntriesMissingFieldsGetSafeFallbacks() throws Exception {
        JsonNode n = M.readTree("{\"type\":\"call\",\"function_calls\":[{}, {\"name\":42}, "
                + "{\"name\":\"ok\",\"arguments\":null}]}");
        NeedleResponse r = NeedleResponse.parse(n);
        assertEquals(3, r.functionCalls().size());
        assertEquals("", r.functionCalls().get(0).name());
        assertEquals("42", r.functionCalls().get(1).name());
        assertEquals("ok", r.functionCalls().get(2).name());
        // arguments() must never be null so callers can safely .path(...) off it
        for (var c : r.functionCalls()) {
            assertNotNull(c.arguments());
            assertDoesNotThrow(() -> c.arguments().path("anything").asText(""));
        }
    }

    @Test void confidenceAndNumericFieldsOfTheWrongTypeFallBack() throws Exception {
        JsonNode n = M.readTree("{\"confidence\":\"high\",\"prefill_tps\":\"fast\","
                + "\"decode_tps\":null,\"peak_ram_mb\":{}}");
        NeedleResponse r = NeedleResponse.parse(n);
        assertNull(r.confidence());
        assertEquals(0.0, r.prefillTps());
        assertEquals(0.0, r.decodeTps());
        assertEquals(0.0, r.peakRamMb());
    }

    @Test void errorCodeOfTheWrongTypeIsNull() throws Exception {
        JsonNode n = M.readTree("{\"error_code\":\"E_BAD\"}");
        assertNull(NeedleResponse.parse(n).errorCode());
    }

    @Test void ungroundedAndNegationSurviveWrongTypesInValidation() throws Exception {
        for (String validation : new String[]{
                "\"oops\"", "42", "{\"ungrounded\":\"nope\"}", "{\"ungrounded\":[1,2,null]}",
                "{\"negation\":\"yes\"}"}) {
            JsonNode n = M.readTree("{\"validation\":" + validation + "}");
            NeedleResponse r = NeedleResponse.parse(n);
            assertDoesNotThrow(r::ungrounded, validation);
            assertDoesNotThrow(r::negation, validation);
        }
    }

    @Test void deeplyNestedGarbageInArgumentsNeverThrowsOnFunctionCallAccess() throws Exception {
        JsonNode n = M.readTree("{\"type\":\"call\",\"function_calls\":["
                + "{\"name\":\"f\",\"arguments\":{\"a\":[1,[2,[3,{\"b\":null}]]],\"c\":\"x\"}}]}");
        NeedleResponse r = NeedleResponse.parse(n);
        assertEquals(1, r.functionCalls().size());
        assertEquals("f", r.functionCalls().get(0).name());
    }

    // ---- Grounding.annotateTemporal against malformed response roots ---------------------------------

    @Test void annotateTemporalNeverThrowsOnMalformedOrPartialEnvelopes() throws Exception {
        JsonNode dateParams = M.readTree(
                "{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\",\"format\":\"date\"}}}");
        Map<String, JsonNode> tools = Map.of("t", dateParams);

        for (String body : new String[]{
                "{}",                                              // no function_calls at all
                "{\"function_calls\":\"nope\"}",                   // wrong type
                "{\"function_calls\":[]}",                          // empty
                "{\"function_calls\":[{\"name\":\"unknown_tool\",\"arguments\":{\"date\":\"2029-01-01\"}}]}",
                "{\"function_calls\":[{\"name\":\"t\"}]}",          // no arguments at all
                "{\"function_calls\":[{\"name\":\"t\",\"arguments\":{\"date\":42}}]}", // date is a number
                "{\"function_calls\":[{\"name\":\"t\",\"arguments\":{\"date\":\"not-a-date\"}}]}",
                "{\"function_calls\":[{\"name\":\"t\",\"arguments\":{\"date\":\"2029-01-01\"}}],"
                        + "\"validation\":\"not an object\"}"}) {
            ObjectNode root = (ObjectNode) M.readTree(body);
            assertDoesNotThrow(
                    () -> Grounding.annotateTemporal(root, tools, java.util.Set.of(2019), null, "no year here"),
                    body);
        }
    }
}
