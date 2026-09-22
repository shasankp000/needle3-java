package shasankp000.needle;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the temporal (year) grounding check (ported from the Python package, see {@link Grounding})
 * against the real engine.
 *
 * <p>One thing confirmed directly against the engine first (raw ctypes probe, before writing these tests):
 * its own {@code validation.ungrounded} flags a {@code format: date} argument whenever the returned string
 * does not textually match the input, EVEN WHEN the year is correct (e.g. input "March 20 2026" -> output
 * "2026-03-20" is flagged, purely because of the reformatting) — the engine has no year-aware reasoning of
 * its own. So a regular {@link Needle#complete} turn cannot be used to tell a correct year apart from a
 * hallucinated one just by checking {@link NeedleResponse#ungrounded()}; that reconciliation is exactly what
 * {@link Grounding#extractionFailures} (used by {@link Extraction#extract}) adds on top, and is what these
 * tests exercise for real, against the real engine.
 */
class NeedleTemporalGroundingIT {
    private static Tool scheduleTool() {
        return Tool.named("schedule_event")
                .description("Schedule a calendar event on a given date")
                .param("title", Tool.string(null))
                .param("date", Tool.date(null))
                .build();
    }

    private static final String SYSTEM = "date: 2024-03-15 Fri 10:00";

    @Test
    void extractionFailuresDistinguishesCorrectYearFromHallucinatedYear() {
        Tool schedule = scheduleTool();
        JsonNode params = schedule.toJson().path("parameters");

        try (Needle n = Needle.builder().tools(schedule).system(SYSTEM).autoDate(false).build()) {
            n.reset();
            String q1 = "schedule a meeting on March 20 2026";
            var r1 = n.complete(q1);
            assertFalse(r1.functionCalls().isEmpty());
            JsonNode args1 = r1.functionCalls().get(0).arguments();
            assertTrue(args1.path("date").asText().startsWith("2026-"), args1.toString());
            Set<String> failures1 = Grounding.extractionFailures(q1, params, args1, r1, SYSTEM);
            assertTrue(failures1.isEmpty(),
                    "a literal, correct year must not be treated as a grounding failure: " + failures1
                            + " / engine said: " + r1.raw());

            n.reset();
            String q2 = "schedule a meeting on 2019-01-05";
            var r2 = n.complete(q2);
            assertFalse(r2.functionCalls().isEmpty());
            JsonNode args2 = r2.functionCalls().get(0).arguments();
            String date2 = args2.path("date").asText();
            System.out.println("engine returned date=" + date2 + " for a 2019-01-05 query");
            if (!date2.startsWith("2019-")) {
                // the engine changed the year the caller wrote; the SDK's check must catch that
                Set<String> failures2 = Grounding.extractionFailures(q2, params, args2, r2, SYSTEM);
                assertTrue(failures2.contains("date"),
                        "a hallucinated year must be a grounding failure: " + failures2 + " / " + r2.raw());
            }
        }
    }

    @Test
    void extractionIsConservativeWhenNoYearIsKnownAnywhere() {
        // Confirmed against the real engine: it flags essentially every `format: date` argument in
        // validation.ungrounded, since the filled-in ISO string is (almost) never a verbatim substring of
        // the input — "next Friday" included, even though the derived date is exactly what a date-typed
        // argument is for. With no literal year anywhere in the input either, the SDK's own temporal check
        // has nothing to vouch for the value with, so strict extraction deliberately treats the engine's
        // flag as a real failure rather than silently waving it through — matching the Python package.
        Tool schedule = scheduleTool();
        JsonNode params = schedule.toJson().path("parameters");
        try (Needle n = Needle.builder().tools(schedule).system(SYSTEM).autoDate(false).build()) {
            n.reset();
            String q = "schedule a meeting next Friday";
            var r = n.complete(q);
            assertFalse(r.functionCalls().isEmpty());
            assertTrue(r.ungrounded().contains("schedule_event.date"), r.raw().toString());
            JsonNode args = r.functionCalls().get(0).arguments();
            Set<String> failures = Grounding.extractionFailures(q, params, args, r, SYSTEM);
            assertTrue(failures.contains("date"), failures + " / " + r.raw());
        }
    }
}
