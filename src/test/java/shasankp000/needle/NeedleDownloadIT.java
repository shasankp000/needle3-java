package shasankp000.needle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Hits the real Hugging Face repo. Off unless NEEDLE_NET_TESTS=1, since it downloads about 36 MB. */
@EnabledIfEnvironmentVariable(named = "NEEDLE_NET_TESTS", matches = "1")
class NeedleDownloadIT {
    @Test
    void realDownloadThenRealInference(@TempDir Path cache) {
        NeedleAssets.Options o = new NeedleAssets.Options().cacheDir(cache).offline(false)
                .onProgress(p -> System.out.println("DL " + p.file() + " " + p.done() + "/" + p.total()));

        long t0 = System.nanoTime();
        NeedleAssets.Result r = NeedleAssets.ensure(o);
        System.out.println("DL took " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        System.out.println("LIB " + r.library() + " bytes=" + size(r.library()));
        System.out.println("WEIGHTS " + r.weights() + " bytes=" + size(r.weights()) + " sha256=" + NeedleAssets.sha256(r.weights()));

        Tool lights = Tool.named("set_lights")
                .description("Turn a room's lights on or off and set brightness")
                .param("room", Tool.string(null))
                .param("on", Tool.bool(null))
                .param("brightness", Tool.integer(null, 0, 100), false)
                .build();
        try (Needle n = Needle.builder().library(r.library()).weights(r.weights())
                .tools(lights).autoDate(false).build()) {
            NeedleResponse resp = n.complete("dim the living room to 30");
            assertEquals(1, resp.functionCalls().size());
            assertEquals("set_lights", resp.functionCalls().get(0).name());
            assertEquals(30, resp.functionCalls().get(0).arguments().path("brightness").asInt());
        }
    }

    @Test
    void hubAdvertisesTheSameChecksumAsTheBuiltInPin() {
        NeedleAssets.Options o = new NeedleAssets.Options();
        String advertised = NeedleAssets.remoteSha256(NeedleAssets.base(o) + "/needle3.cact");
        System.out.println("ADVERTISED " + advertised);
        assertNotNull(advertised, "the Hub did not send X-Linked-Etag for needle3.cact");
        assertEquals(NeedleAssets.DEFAULT_WEIGHTS_SHA256, advertised);
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
