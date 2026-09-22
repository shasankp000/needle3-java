package shasankp000.needle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies {@link Extraction#extract} (port of the Python package's module-level {@code extract()}) against the real engine. */
class NeedleExtractIT {
    private static final Tool SCHEMA = Tool.named("schedule_event")
            .description("Schedule a calendar event on a given date")
            .param("title", Tool.string(null))
            .param("date", Tool.date(null))
            .build();
    private static final String SYSTEM = "date: 2024-03-15 Fri 10:00";

    @Test
    void extractsArgumentsWhenTheEngineIsConfident() {
        var args = Extraction.extract(SCHEMA, "schedule a meeting on March 20 2026",
                new Extraction.Options().system(SYSTEM));
        assertNotNull(args);
        assertTrue(args.path("date").asText().startsWith("2026-"), args.toString());
        assertTrue(args.path("title").asText().toLowerCase().contains("meeting"), args.toString());
    }

    @Test
    void returnsNullOnRefusal() {
        var args = Extraction.extract(SCHEMA, "what is the capital of France?",
                new Extraction.Options().system(SYSTEM));
        assertNull(args);
    }

    @Test
    void strictModeRejectsAHallucinatedYear() {
        // The engine can silently swap the query's year for another one (confirmed against the real engine
        // via a raw ctypes probe); strict extraction must catch that instead of returning it silently.
        var opts = new Extraction.Options().system(SYSTEM);
        try {
            var args = Extraction.extract(SCHEMA, "schedule a meeting on 2019-01-05", opts);
            // if the engine happened to get the year right this run, there is nothing to reject
            assertTrue(args.path("date").asText().startsWith("2019-"), args.toString());
        } catch (NeedleExtractionException e) {
            assertTrue(e.getMessage().contains("date"), e.getMessage());
        }
    }

    @Test
    void nonStrictModeReturnsValuesEvenWhenUngrounded() {
        var opts = new Extraction.Options().system(SYSTEM).strict(false);
        var args = assertDoesNotThrow(() -> Extraction.extract(SCHEMA, "schedule a meeting on 2019-01-05", opts));
        assertNotNull(args);
        assertTrue(args.has("date"));
    }

    @Test
    void instanceMethodReusesAnAlreadyOpenEngine() {
        try (Needle n = Needle.builder().tools(SCHEMA).system(SYSTEM).autoDate(false).build()) {
            var args = n.extract(SCHEMA, "schedule a meeting on March 20 2026");
            assertNotNull(args);
            assertTrue(args.path("date").asText().startsWith("2026-"), args.toString());
        }
    }

    @Test
    void instanceMethodRejectsAToolNotDeclaredOnThatInstance() {
        Tool other = Tool.named("other_tool").description("unrelated").build();
        try (Needle n = Needle.builder().tools(SCHEMA).system(SYSTEM).autoDate(false).build()) {
            assertThrows(IllegalArgumentException.class, () -> n.extract(other, "anything"));
        }
    }
}
