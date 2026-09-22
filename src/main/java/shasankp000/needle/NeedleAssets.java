package shasankp000.needle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the Needle 3 engine and weights from Hugging Face into a local cache, with no Python involved.
 *
 * <p>The weights are {@code needle3.cact} in the {@code Cactus-Compute/needle3} repo. The engine ships there as
 * one wheel per platform ({@code python/cactus_needle-<ver>-py3-none-<tag>.whl}); a wheel is a zip, so the
 * shared library is unpacked from it. The cache layout matches the Python package
 * ({@code ~/.cache/cactus-needle/v3/<ver>/libneedle.so} and {@code v3/needle3.cact}), so the two share files.
 *
 * <p>URLs are pinned to a repo commit so a download is reproducible. Nothing is fetched when the files are
 * already cached, and inference itself never touches the network.
 */
public final class NeedleAssets {
    public static final String DEFAULT_REPO = "Cactus-Compute/needle3";
    /** Hugging Face commit the URLs are pinned to (the repo head when this SDK was written). Use "main" to float. */
    public static final String DEFAULT_REVISION = "b274efcb211a9eef48c9a88da4b43bd569696a39";
    public static final String DEFAULT_ENGINE_VERSION = "3.0.1";
    /** SHA-256 of needle3.cact at {@link #DEFAULT_REVISION}; only applied while that revision is in use. */
    static final String DEFAULT_WEIGHTS_SHA256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38";
    static final String WEIGHTS_FILE = "needle3.cact";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Hugging Face puts X-Linked-Etag (the file's SHA-256) only on its first-hop redirect, so do not follow it. */
    private static final HttpClient HEAD_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private NeedleAssets() {
    }

    /** Where the files ended up. */
    public record Result(Path library, Path weights) {
    }

    /** Download progress; {@code total} is -1 when the server did not say. */
    public record Progress(String file, long done, long total) {
    }

    /** Settings for {@link #ensureLibrary} / {@link #ensureWeights}. Defaults suit most callers. */
    public static final class Options {
        Path cacheDir = defaultCacheDir();
        String repo = DEFAULT_REPO;
        String revision = DEFAULT_REVISION;
        String engineVersion = DEFAULT_ENGINE_VERSION;
        String endpoint = defaultEndpoint();
        boolean offline = envOffline();
        String weightsSha256;
        String engineSha256;
        Consumer<Progress> progress;

        /** Cache root; files land under {@code <root>/v3/}. Default {@code ~/.cache/cactus-needle}. */
        public Options cacheDir(Path p) {
            this.cacheDir = p;
            return this;
        }

        /** Repo commit, branch or tag to download from. */
        public Options revision(String r) {
            this.revision = r;
            return this;
        }

        public Options engineVersion(String v) {
            this.engineVersion = v;
            return this;
        }

        /** Mirror base URL; default {@code $HF_ENDPOINT} or https://huggingface.co. */
        public Options endpoint(String url) {
            this.endpoint = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            return this;
        }

        /** Never touch the network; fail if a file is missing. Default follows {@code HF_HUB_OFFLINE}. */
        public Options offline(boolean v) {
            this.offline = v;
            return this;
        }

        /**
         * Expected SHA-256 (hex) of needle3.cact; a mismatch aborts and discards the download. Left unset, the
         * built-in checksum applies while the default revision is used; pass "" to skip verification.
         */
        public Options weightsSha256(String hex) {
            this.weightsSha256 = hex;
            return this;
        }

        /** Expected SHA-256 (hex) of the engine wheel for this platform; unset, the Hub's advertised value is used. */
        public Options engineSha256(String hex) {
            this.engineSha256 = hex;
            return this;
        }

        /** Called during downloads, at most about every 256 KB. */
        public Options onProgress(Consumer<Progress> c) {
            this.progress = c;
            return this;
        }
    }

    // ---- public API ---------------------------------------------------------

    public static Result ensure() {
        return ensure(new Options());
    }

    public static Result ensure(Options o) {
        return new Result(ensureLibrary(o), ensureWeights(o));
    }

    /** Path to needle3.cact, downloading it first if it is not cached. */
    public static Path ensureWeights(Options o) {
        Path target = o.cacheDir.resolve("v3").resolve(WEIGHTS_FILE);
        if (isUsable(target)) return target;
        requireOnline(o, "weights " + target);
        String url = base(o) + "/" + WEIGHTS_FILE;
        synchronized (NeedleAssets.class) {
            return withLock(target.getParent(), () -> {
                if (isUsable(target)) return target; // another process finished while we waited
                Path part = target.resolveSibling(WEIGHTS_FILE + ".part");
                try {
                    download(url, part, WEIGHTS_FILE, o);
                    verifySha256(part, expectedWeightsSha256(o, url), WEIGHTS_FILE);
                    move(part, target);
                } finally {
                    deleteQuietly(part);
                }
                return target;
            });
        }
    }

    /** Path to the shared engine library for this OS/CPU, downloading and unpacking its wheel if needed. */
    public static Path ensureLibrary(Options o) {
        Path dir = o.cacheDir.resolve("v3").resolve(o.engineVersion);
        Path target = dir.resolve(libraryFileName());
        if (isUsable(target)) return target;
        requireOnline(o, "engine " + target);
        String tag = platformTag();
        String wheel = "cactus_needle-" + o.engineVersion + "-py3-none-" + tag + ".whl";
        String url = base(o) + "/python/" + wheel;
        synchronized (NeedleAssets.class) {
            return withLock(dir, () -> {
                if (isUsable(target)) return target;
                Path wheelPart = dir.resolve(wheel + ".part");
                Path libPart = dir.resolve(libraryFileName() + ".part");
                try {
                    download(url, wheelPart, wheel, o);
                    verifySha256(wheelPart, o.engineSha256 != null ? o.engineSha256 : remoteSha256(url), wheel);
                    extractLibrary(wheelPart, libPart);
                    move(libPart, target);
                } finally {
                    deleteQuietly(wheelPart);
                    deleteQuietly(libPart);
                }
                return target;
            });
        }
    }

    /** SHA-256 of a file as lowercase hex; handy for pinning {@link Options#weightsSha256}. */
    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new NeedleException("Cannot hash " + file, e);
        }
    }

    // ---- platform -----------------------------------------------------------

    /** Wheel platform tag for the running JVM, e.g. {@code manylinux2014_x86_64} or {@code win_amd64}. */
    public static String platformTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (os.contains("win")) {
            if (arm64) return "win_arm64";
            if (x64) return "win_amd64";
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (arm64) return "macosx_11_0_arm64";
            if (x64) return "macosx_11_0_x86_64";
        } else if (os.contains("linux")) {
            String libc = isMusl() ? "musllinux_1_2" : "manylinux2014";
            if (arm64) return libc + "_aarch64";
            if (x64) return libc + "_x86_64";
        }
        throw new NeedleException("No prebuilt Needle 3 engine wheel for " + os + " / " + arch
                + ". Build one for your platform with the Cactus tooling and pass it via Needle.builder().library(path).");
    }

    static String libraryFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "needle.dll";
        if (os.contains("mac") || os.contains("darwin")) return "libneedle.dylib";
        return "libneedle.so";
    }

    private static boolean isMusl() {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of("/lib"), "ld-musl-*")) {
            return s.iterator().hasNext();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ---- internals ----------------------------------------------------------

    static Path defaultCacheDir() {
        return Path.of(System.getProperty("user.home"), ".cache", "cactus-needle");
    }

    private static String defaultEndpoint() {
        String e = System.getenv("HF_ENDPOINT");
        if (e == null || e.isBlank()) return "https://huggingface.co";
        return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
    }

    private static boolean envOffline() {
        String v = System.getenv("HF_HUB_OFFLINE");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    /**
     * Checksum to verify the weights against: your explicit pin, else the built-in pin while the default revision
     * is used (trust anchored in this SDK release), else whatever the Hub advertises for that file.
     */
    private static String expectedWeightsSha256(Options o, String url) {
        if (o.weightsSha256 != null) return o.weightsSha256;
        if (DEFAULT_REVISION.equals(o.revision)) return DEFAULT_WEIGHTS_SHA256;
        return remoteSha256(url);
    }

    /**
     * The SHA-256 the Hub advertises for a file (its {@code X-Linked-Etag}), or null if it does not say. It comes
     * from the same origin as the file, so it catches corruption and truncation but not a compromised repo;
     * a built-in or explicit pin is what covers that.
     */
    static String remoteSha256(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "needle3-java")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = HEAD_CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            String v = resp.headers().firstValue("x-linked-etag").orElse(null);
            if (v == null) return null;
            v = v.trim();
            if (v.startsWith("W/")) v = v.substring(2);
            v = v.replace("\"", "");
            if (v.startsWith("sha256:")) v = v.substring(7);
            return v.matches("[0-9a-fA-F]{64}") ? v.toLowerCase(Locale.ROOT) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static String base(Options o) {
        return o.endpoint + "/" + o.repo + "/resolve/" + o.revision;
    }

    private static boolean isUsable(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void requireOnline(Options o, String what) {
        if (o.offline) {
            throw new NeedleException("Missing " + what + " and offline mode is on (HF_HUB_OFFLINE or "
                    + "Options.offline). Copy the file into place or allow downloads.");
        }
    }

    @SuppressWarnings("try")
    private static <T> T withLock(Path dir, java.util.function.Supplier<T> body) {
        try {
            Files.createDirectories(dir);
            try (FileChannel ch = FileChannel.open(dir.resolve(".needle-download.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = ch.lock()) {
                return body.get();
            }
        } catch (IOException e) {
            throw new NeedleException("Cannot prepare cache directory " + dir, e);
        }
    }

    private static void download(String url, Path dest, String label, Options o) {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .header("User-Agent", "needle3-java")
                        .GET().build();
                HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    resp.body().close();
                    throw new IOException("HTTP " + resp.statusCode());
                }
                long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                long done = 0, reported = 0;
                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (o.progress != null && done - reported >= (1 << 18)) {
                            o.progress.accept(new Progress(label, done, total));
                            reported = done;
                        }
                    }
                }
                if (total >= 0 && done != total) throw new IOException("truncated: " + done + " of " + total + " bytes");
                if (done == 0) throw new IOException("empty response");
                if (o.progress != null && done != reported) o.progress.accept(new Progress(label, done, total));
                return;
            } catch (IOException e) {
                last = e;
                if (attempt < 3) sleep(700L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NeedleException("Interrupted while downloading " + label, e);
            }
        }
        throw new NeedleException("Could not download " + label + " from " + url
                + " (" + (last == null ? "unknown error" : last.getMessage()) + ")", last);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void verifySha256(Path file, String expectedHex, String label) {
        if (expectedHex == null || expectedHex.isBlank()) return;
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expectedHex.trim())) {
            throw new NeedleException("SHA-256 mismatch for " + label + ": expected " + expectedHex
                    + " but downloaded file is " + actual);
        }
    }

    /** Copies the shared library out of the wheel to a fixed target name; entry paths are never used to write. */
    private static void extractLibrary(Path wheel, Path libPart) {
        try (ZipFile zip = new ZipFile(wheel.toFile())) {
            ZipEntry best = null;
            StringBuilder seen = new StringBuilder();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (seen.length() < 400) seen.append(name).append(' ');
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                boolean lib = base.contains("needle")
                        && (base.endsWith(".so") || base.contains(".so.") || base.endsWith(".dylib") || base.endsWith(".dll"));
                if (!lib) continue;
                if (best == null || base.equals(libraryFileName())) best = e;
            }
            if (best == null) {
                throw new NeedleException("No engine library found inside " + wheel.getFileName() + " (entries: " + seen + ")");
            }
            if (best.getSize() > 256L * 1024 * 1024) throw new NeedleException("Engine library in wheel is implausibly large");
            try (InputStream in = zip.getInputStream(best)) {
                Files.copy(in, libPart, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new NeedleException("Cannot unpack engine wheel " + wheel.getFileName(), e);
        }
    }

    private static void move(Path from, Path to) throws NeedleException {
        try {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new NeedleException("Cannot place " + to, e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
