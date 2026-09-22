package shasankp000.needle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the tool-index / retrieval path against the real engine, above the ~20-tool threshold Cactus's
 * own docs warn about (see the README).
 *
 * <p>Two things are checked:
 * <ol>
 *   <li>{@code toolIndexPath} itself: on this engine build, an index file that does not exist is accepted by
 *   {@code needle_init} without error and without being created as a side effect — it is a safe, inert
 *   pass-through, not something this SDK needs to build. (Confirmed directly against libneedle.so via a raw
 *   probe before writing this test, so a future engine build that starts requiring/writing that file would
 *   be a real behaviour change, not a bug in this test.)</li>
 *   <li>The actual recommended retrieval pattern: embed the query and each tool description with
 *   {@link Needle#embed}, rank by cosine similarity, and construct a NEW {@code Needle} with just the
 *   shortlisted tools (the engine tool set is fixed at construction, so this always means a fresh instance).
 *   This is what Cactus's guidance means by "shortlisting tools per request".</li>
 * </ol>
 */
class NeedleToolIndexIT {
    private static List<Tool> thirtySmartHomeTools() {
        String[] domains = {"lights", "music", "timer", "weather", "calendar", "email", "messages", "alarm",
                "thermostat", "door", "camera", "tv", "vacuum", "garage", "blinds", "fan", "speaker", "router",
                "printer", "lock", "coffee", "oven", "dishwasher", "washer", "dryer", "fridge", "aircon",
                "heater", "humidifier", "purifier"};
        List<Tool> tools = new ArrayList<>();
        for (int i = 0; i < domains.length; i++) {
            tools.add(Tool.named("control_" + domains[i] + "_" + i)
                    .description("Control the " + domains[i] + " device: adjust its settings, turn it on or "
                            + "off, or query its status")
                    .param("action", Tool.string(null))
                    .build());
        }
        return tools;
    }

    @Test
    void unbuiltToolIndexPathIsAcceptedWithoutErrorAndCompletionStillWorks(@org.junit.jupiter.api.io.TempDir
                                                                            java.nio.file.Path tmp) {
        List<Tool> tools = thirtySmartHomeTools();
        java.nio.file.Path idx = tmp.resolve("does-not-exist.idx");
        try (Needle n = Needle.builder().tools(tools).toolIndexPath(idx).autoDate(false).build()) {
            assertFalse(java.nio.file.Files.exists(idx), "the engine should not have created an index file");
            var r = n.complete("turn off the garage door");
            assertFalse(r.functionCalls().isEmpty());
            assertTrue(r.functionCalls().get(0).name().startsWith("control_garage_"), r.raw().toString());
        }
    }

    @Test
    void embedBasedShortlistPicksTheSameCorrectToolAsTheFullSet() {
        List<Tool> tools = thirtySmartHomeTools();
        String query = "turn off the garage door";

        String correctFromFullSet;
        List<float[]> toolVectors = new ArrayList<>();
        float[] queryVector;
        try (Needle n = Needle.builder().tools(tools).autoDate(false).build()) {
            var full = n.complete(query);
            assertFalse(full.functionCalls().isEmpty());
            correctFromFullSet = full.functionCalls().get(0).name();

            queryVector = n.embed(query);
            for (Tool t : tools) {
                toolVectors.add(n.embed(t.name() + ": " + t.toJson().path("description").asText()));
            }
        }

        record Scored(Tool tool, double sim) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            scored.add(new Scored(tools.get(i), cosine(queryVector, toolVectors.get(i))));
        }
        scored.sort(Comparator.comparingDouble(Scored::sim).reversed());
        List<Tool> shortlist = scored.subList(0, 5).stream().map(Scored::tool).toList();
        assertTrue(shortlist.stream().anyMatch(t -> t.name().equals(correctFromFullSet)),
                "the correct tool must survive the embed-based shortlist: " + correctFromFullSet
                        + " not in " + shortlist.stream().map(Tool::name).toList());

        try (Needle n2 = Needle.builder().tools(shortlist).autoDate(false).build()) {
            var shortlisted = n2.complete(query);
            assertFalse(shortlisted.functionCalls().isEmpty());
            assertEquals(correctFromFullSet, shortlisted.functionCalls().get(0).name());
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
