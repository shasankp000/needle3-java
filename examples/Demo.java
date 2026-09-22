import shasankp000.needle.Needle;
import shasankp000.needle.NeedleResponse;
import shasankp000.needle.Tool;

import java.util.Map;

/** Run: java --enable-native-access=ALL-UNNAMED -cp <classes>:<jackson jars> examples/Demo.java */
public class Demo {
    public static void main(String[] args) {
        Tool weather = Tool.named("get_weather")
                .description("Get the current weather for a city.")
                .param("city", Tool.string("City name"))
                .build();

        // Needle.builder().weights(Path.of("needle3.cact")).library(Path.of("libneedle.so")) to be explicit;
        // otherwise NEEDLE3_LIB_PATH and ~/.cache/cactus-needle/v3/ are used.
        try (Needle needle = Needle.builder().tools(weather).system("locale: en-IN; device: laptop").build()) {

            // 1) One turn, you own execution
            NeedleResponse r = needle.complete("what's the weather in Lagos right now?");
            if (r.isRefusal()) {
                System.out.println("Refused: no tool covers that.");
            } else {
                r.functionCalls().forEach(c -> System.out.println(c.name() + " " + c.arguments()));
                System.out.println("confidence=" + r.confidence());
            }
            needle.reset();

            // 2) Full loop, the SDK executes your handlers and feeds results back
            var out = needle.run("what's the weather in Lagos right now?",
                    Map.of("get_weather", args2 -> Map.of("city", args2.path("city").asText(), "temp_c", 27, "sky", "clear")),
                    8);
            System.out.println("results=" + out.results() + " final=" + out.last().type());
            System.out.println("embed dim=" + needle.embed("set an alarm").length);
        }
    }
}
