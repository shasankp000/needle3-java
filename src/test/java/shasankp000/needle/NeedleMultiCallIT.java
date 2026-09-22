package shasankp000.needle;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NeedleMultiCallIT {
    @Test
    void twoCallsInOneTurn() {
        Tool lights = Tool.named("set_lights")
                .description("Turn a room's lights on or off")
                .param("room", Tool.string(null))
                .param("on", Tool.bool(null))
                .build();
        Tool lock = Tool.named("lock_door")
                .description("Lock or unlock a door")
                .param("door", Tool.string(null))
                .param("locked", Tool.bool(null))
                .build();

        String q = "turn on the kitchen lights and lock the front door";
        try (Needle n = Needle.builder().tools(lights, lock).build()) {
            System.out.println("TURN1 " + n.complete(q).raw().path("function_calls"));
            n.reset();

            Needle.RunResult out = n.run(q, Map.of(
                    "set_lights", a -> Map.of("ok", true,
                            "room", a.path("room").asText(), "on", a.path("on").asBoolean()),
                    "lock_door", a -> Map.of("ok", true,
                            "door", a.path("door").asText(), "locked", a.path("locked").asBoolean())
            ), 4);

            System.out.println("RESULTS " + out.results());
            System.out.println("FINAL   " + out.last().raw());
            assertTrue(out.results().size() >= 2, "expected both tools to run");
            assertEquals("respond", out.last().type());
        }
    }

    @Test
    void singleCallRun() {
        Tool lights = Tool.named("set_lights")
                .description("Turn a room's lights on or off")
                .param("room", Tool.string(null))
                .param("on", Tool.bool(null))
                .build();
        try (Needle n = Needle.builder().tools(lights).build()) {
            Needle.RunResult out = n.run("turn on the kitchen lights",
                    Map.of("set_lights", a -> Map.of("ok", true)), 4);
            assertEquals(1, out.results().size());
            assertEquals("respond", out.last().type());
        }
    }
}