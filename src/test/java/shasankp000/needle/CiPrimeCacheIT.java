package shasankp000.needle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI-only helper: downloads the real engine + weights into the DEFAULT cache location (unlike
 * {@link NeedleDownloadIT}, which uses a throwaway {@code @TempDir}), so the discovery order used by the rest
 * of the behavioural {@code *IT} suite finds them without every test needing an explicit path. Not part of
 * the documented test surface -- see {@code .github/workflows/ci.yml}, which runs this before the full
 * matrix's behavioural tests. Off unless {@code NEEDLE_CI_PRIME=1}.
 */
@EnabledIfEnvironmentVariable(named = "NEEDLE_CI_PRIME", matches = "1")
class CiPrimeCacheIT {
    @Test
    void downloadsIntoTheDefaultCache() {
        NeedleAssets.Result r = NeedleAssets.ensure();
        assertNotNull(r.library());
        assertNotNull(r.weights());
        System.out.println("primed default cache: library=" + r.library() + " weights=" + r.weights());
    }
}
