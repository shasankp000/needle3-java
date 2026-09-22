package shasankp000.needle;

import org.junit.jupiter.api.Test;

class NeedleTimerIT {
    @Test void requiredNumberWithNoValueInQuery() {
        Tool timer = Tool.named("set_timer")
                .description("Start a countdown timer")
                .param("minutes", Tool.integer(null, 1, 600))   // required
                .build();
        try (Needle n = Needle.builder().tools(timer).autoDate(false).build()) {
            for (String q : new String[]{"set a timer", "set a timer for a while", "set a timer for 10 minutes"}) {
                n.reset();
                var r = n.complete(q);
                System.out.println("TIMER " + q + " | calls=" + r.raw().path("function_calls")
                        + " | validation=" + r.raw().path("validation")
                        + " | suppressed=" + r.raw().path("suppressed_calls"));
            }
        }
    }
}