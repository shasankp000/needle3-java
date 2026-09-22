package shasankp000.needle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NeedleBadWeightsIT {
    @Test
    void garbageWeightsReportEngineDetail(@TempDir Path dir) throws Exception {
        byte[] junk = new byte[4096];
        new Random(1).nextBytes(junk);
        Path bad = dir.resolve("bad.cact");
        Files.write(bad, junk);

        NeedleException e = assertThrows(NeedleException.class,
                () -> Needle.builder().weights(bad).build());
        System.out.println("MESSAGE: " + e.getMessage());

        assertNotEquals(0, e.code());
        // "(code -1): <engine text>" means needle_last_error returned something
        assertTrue(e.getMessage().matches("(?s).*\\(code -?\\d+\\): .+"),
                "no engine detail in: " + e.getMessage());
    }
}