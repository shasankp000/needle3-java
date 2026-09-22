package shasankp000.needle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a local server that mimics the Hugging Face layout, so it needs no network and no engine. */
class NeedleAssetsTest {
    private static final String REV = "TESTREV";
    private final byte[] weights = new byte[200_000];
    private final byte[] fakeLib = "not a real library".getBytes();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String advertisedWeightsSha; // what the mock "Hub" claims for needle3.cact

    private HttpServer serve() throws Exception {
        new Random(3).nextBytes(weights);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("cactus_needle-3.0.1.dist-info/METADATA"));
            z.write("Name: cactus-needle\n".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("needle/" + NeedleAssets.libraryFileName()));
            z.write(fakeLib);
            z.closeEntry();
        }
        byte[] wheel = bos.toByteArray();
        String root = "/" + NeedleAssets.DEFAULT_REPO + "/resolve/" + REV;
        String wheelName = "cactus_needle-3.0.1-py3-none-" + NeedleAssets.platformTag() + ".whl";

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext(root + "/needle3.cact", ex -> {   // like the real Hub: 302 to a CDN, checksum on the 302
            if (!ex.getRequestMethod().equals("HEAD")) hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/cdn/needle3");
            if (advertisedWeightsSha != null) ex.getResponseHeaders().add("X-Linked-Etag", "\"" + advertisedWeightsSha + "\"");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        srv.createContext("/cdn/needle3", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, weights.length);
            ex.getResponseBody().write(weights);
            ex.close();
        });
        srv.createContext(root + "/python/" + wheelName, ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, wheel.length);
            ex.getResponseBody().write(wheel);
            ex.close();
        });
        srv.start();
        return srv;
    }

    private NeedleAssets.Options opts(HttpServer srv, Path cache) {
        return new NeedleAssets.Options().cacheDir(cache).revision(REV).offline(false)
                .endpoint("http://127.0.0.1:" + srv.getAddress().getPort());
    }

    @Test
    void downloadsUnpacksAndThenUsesTheCache(@TempDir Path cache) throws Exception {
        HttpServer srv = serve();
        try {
            NeedleAssets.Options o = opts(srv, cache);
            NeedleAssets.Result r = NeedleAssets.ensure(o);
            assertEquals(cache.resolve("v3").resolve("needle3.cact"), r.weights());
            assertArrayEquals(weights, Files.readAllBytes(r.weights()));
            assertEquals(cache.resolve("v3").resolve("3.0.1").resolve(NeedleAssets.libraryFileName()), r.library());
            assertArrayEquals(fakeLib, Files.readAllBytes(r.library()));
            try (var files = Files.walk(cache)) {
                assertTrue(files.noneMatch(p -> p.toString().endsWith(".part")));
            }

            int before = hits.get();
            NeedleAssets.ensure(o);
            assertEquals(before, hits.get(), "second call must not touch the network");
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void offlineAndMissingFailsWithoutTouchingTheNetwork(@TempDir Path cache) throws Exception {
        HttpServer srv = serve();
        try {
            NeedleException e = assertThrows(NeedleException.class,
                    () -> NeedleAssets.ensureWeights(opts(srv, cache).offline(true)));
            assertTrue(e.getMessage().contains("offline"));
            assertEquals(0, hits.get());
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void checksumMismatchIsRejectedAndNothingIsKept(@TempDir Path cache) throws Exception {
        HttpServer srv = serve();
        try {
            NeedleException e = assertThrows(NeedleException.class,
                    () -> NeedleAssets.ensureWeights(opts(srv, cache).weightsSha256("00".repeat(32))));
            assertTrue(e.getMessage().contains("mismatch"));
            assertFalse(Files.exists(cache.resolve("v3").resolve("needle3.cact")));

            Path ok = NeedleAssets.ensureWeights(opts(srv, cache).weightsSha256(sha(weights)));
            assertTrue(Files.exists(ok));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void verifiesAgainstTheChecksumTheHubAdvertises(@TempDir Path cache) throws Exception {
        HttpServer srv = serve();
        try {
            advertisedWeightsSha = "ab".repeat(32);                       // wrong on purpose
            NeedleException e = assertThrows(NeedleException.class,
                    () -> NeedleAssets.ensureWeights(opts(srv, cache)));
            assertTrue(e.getMessage().contains("mismatch"));
            assertFalse(Files.exists(cache.resolve("v3").resolve("needle3.cact")));

            advertisedWeightsSha = sha(weights);                          // right
            assertTrue(Files.exists(NeedleAssets.ensureWeights(opts(srv, cache))));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void anAbsentAdvertisedChecksumIsNotAnError(@TempDir Path cache) throws Exception {
        HttpServer srv = serve();
        try {
            advertisedWeightsSha = null;   // mirrors that omit the header must still work
            assertTrue(Files.exists(NeedleAssets.ensureWeights(opts(srv, cache))));
        } finally {
            srv.stop(0);
        }
    }

    private static String sha(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }
}
