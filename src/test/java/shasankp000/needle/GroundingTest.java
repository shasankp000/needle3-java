package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroundingTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test void numbersGroundAcrossFormats() throws Exception {
        JsonNode args = M.readTree("{\"brightness\":30,\"n\":{\"x\":1000.0},\"l\":[5,true]}");
        assertEquals(Set.of("brightness", "n.x"),
                Grounding.groundedNumberPaths(args, "dim to 30.0 and 1,000 items", null));
    }

    @Test void yearInsideDateFactDoesNotGround() throws Exception {
        JsonNode args = M.readTree("{\"year\":2026}");
        assertTrue(Grounding.groundedNumberPaths(args, "date: 2026-09-21 Mon 14:30; locale: en-IN").isEmpty());
        assertEquals(Set.of("year"), Grounding.groundedNumberPaths(args, "in 2026 please"));
    }

    @Test void ungroundedEntriesGroupByTool() throws Exception {
        NeedleResponse r = NeedleResponse.parse(M.readTree(
                "{\"type\":\"call\",\"function_calls\":[],\"validation\":{\"ungrounded\":"
                        + "[\"set_lights.brightness\",\"lock_door\"]}}"));
        var g = Grounding.ungroundedByTool(r);
        assertEquals(Set.of("brightness"), g.get("set_lights"));
        assertEquals(Set.of("lock_door"), g.get("lock_door")); // no path: falls back to the tool name
    }

    // ---- temporal (year) grounding -------------------------------------------------------------------

    @Test void sourceYearsFindsMonthNamesAndIsoDatesButNotDayFirstDates() {
        assertEquals(Set.of(2026), Grounding.sourceYears("schedule it for March 20 2026"));
        assertEquals(Set.of(2026), Grounding.sourceYears("schedule it for 20th March, 2026"));
        assertEquals(Set.of(2019), Grounding.sourceYears("book it for 2019-01-05"));
        assertEquals(Set.of(), Grounding.sourceYears("book it for 5/6/24")); // day-first: no year minted
        assertEquals(Set.of(), Grounding.sourceYears("next Friday"));
        assertEquals(Set.of(1999), Grounding.sourceYears("back in the year 1999 things were different"));
    }

    @Test void relativeCueDetectsCommonPhrasingOnly() {
        assertTrue(Grounding.relativeCue("schedule it for next Friday"));
        assertTrue(Grounding.relativeCue("remind me tomorrow"));
        assertFalse(Grounding.relativeCue("schedule it for March 20 2026"));
    }

    @Test void licensedYearsAddsSystemYearsOnlyWhenSomeYearIsAlreadyKnown() {
        assertEquals(Set.of(), Grounding.licensedYears(Set.of(), "date: 2024-03-15", true));
        assertEquals(Set.of(2026), Grounding.licensedYears(Set.of(2026), "date: 2024-03-15", false));
        assertEquals(Set.of(2026, 2024), Grounding.licensedYears(Set.of(2026), "date: 2024-03-15", true));
    }

    @Test void walkGroundingFlagsOnlyAYearThatContradictsEveryKnownYear() throws Exception {
        JsonNode params = M.readTree(
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
                        + "\"date\":{\"type\":\"string\",\"format\":\"date\"}},\"required\":[\"title\",\"date\"]}");

        JsonNode okArgs = M.readTree("{\"title\":\"Meeting\",\"date\":\"2026-03-20\"}");
        var ok = Grounding.walkGrounding(params, okArgs, Set.of(2026));
        assertEquals(Set.of("date"), ok.checked());
        assertTrue(ok.failures().isEmpty());

        JsonNode badArgs = M.readTree("{\"title\":\"Meeting\",\"date\":\"2029-01-05\"}");
        var bad = Grounding.walkGrounding(params, badArgs, Set.of(2019));
        assertEquals(Set.of("date"), bad.checked());
        assertEquals(Set.of("date"), bad.failures());

        // No years known anywhere yet: nothing can be checked, so nothing is flagged either.
        var unchecked = Grounding.walkGrounding(params, badArgs, Set.of());
        assertTrue(unchecked.checked().isEmpty());
        assertTrue(unchecked.failures().isEmpty());
    }

    @Test void annotateTemporalAppendsWithoutDuplicatingOrDroppingEngineFlags() throws Exception {
        ObjectNode response = (ObjectNode) M.readTree(
                "{\"type\":\"call\",\"function_calls\":[{\"name\":\"schedule_event\","
                        + "\"arguments\":{\"title\":\"Meeting\",\"date\":\"2029-01-05\"}}],"
                        + "\"validation\":{\"ungrounded\":[\"schedule_event.title\"],\"negation\":false}}");
        JsonNode params = M.readTree(
                "{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\",\"format\":\"date\"}}}");

        Grounding.annotateTemporal(response, Map.of("schedule_event", params), Set.of(2019), null,
                "schedule a meeting for 2019-01-05");

        var ungrounded = response.path("validation").path("ungrounded");
        assertEquals(2, ungrounded.size(), ungrounded.toString());
        Set<String> names = Set.of(ungrounded.get(0).asText(), ungrounded.get(1).asText());
        assertEquals(Set.of("schedule_event.title", "schedule_event.date"), names);
    }
}
