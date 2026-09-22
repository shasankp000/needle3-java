# needle3-java (unofficial)

A Java SDK for Cactus Compute's [Needle 3](https://cactuscompute.com/needle) -- a small on-device model that
turns a sentence into structured tool calls. This binds straight to the native engine using the Foreign
Function & Memory API (final in **Java 22**), so there is no JNI glue, no C to compile, and Python is not
required at build time or runtime.

Not affiliated with Cactus Compute. Needle 3 itself, and the weights this SDK downloads, are Cactus's.

## What this gives you

- `Needle` -- load the engine and weights, declare tools, run turns
- `Tool` -- build a tool's JSON schema (name, description, parameters) in Java
- `NeedleResponse` -- a parsed turn: calls, confidence, grounding/negation flags, a `verdict()` policy helper
- `NeedleAssets` -- download the engine and weights from Hugging Face, with no Python involved
- `Extraction` (and `Needle.extract`) -- one-shot structured extraction from a `Tool` schema and text, with the
  same grounding checks as `run()`

```java
Tool weather = Tool.named("get_weather")
        .description("Get the current weather for a city")
        .param("city", Tool.string("City name"))
        .build();

try (Needle needle = Needle.builder().tools(weather).autoDownload(true).build()) {
    NeedleResponse r = needle.complete("what's the weather in Lagos?");
    if (r.isRefusal()) {
        // no tool covers this request
    } else {
        for (var call : r.functionCalls()) {
            System.out.println(call.name() + " " + call.arguments());
        }
    }
}
```

For several turns where you supply the tool implementations and let the SDK feed results back:

```java
var out = needle.run("what's the weather in Lagos?", Map.of(
        "get_weather", args -> fetchWeather(args.get("city").asText())
), /* maxSteps */ 6);
```

One-shot structured extraction, with the same grounding checks applied on the way out:

```java
Tool trip = Tool.named("trip")
        .description("A trip a user wants to plan")
        .param("destination", Tool.string(null))
        .param("date", Tool.date("the day of travel"))
        .build();

JsonNode args = Extraction.extract(trip, "book me a flight to Lagos for March 20 2026");
// throws NeedleExtractionException instead if the engine's date doesn't match a year in the text
```

## Verified against the real engine

Everything below was run against the real Needle 3 engine and weights (`needle3.cact`, Cactus generation 3),
not a mock, and checked against the official Python package (`pip install cactus-needle`) for parity. Last
full re-run: 2026-09-22, Linux x86-64, Java 25, `mvn test` for the fast suite plus an explicit `-Dtest=...`
run (see [Build and run](#build-and-run)) for every real-engine test -- 26/26 and 26/26 green.

- `needle_load`, `needle_init`, `needle_complete`, `needle_reset`, `needle_embed`, `needle_last_error` -- all six
  native calls, including the two (`embed`, `last_error`) whose prototypes are undocumented for Needle 3 and
  had to be inferred
- Identical `function_calls` output to Python for the same tools and queries
- Identical `confidence` values to Python (matched to 1e-3) once both sides are built with the same prompt
  (same tool schema text, `autoDate(false)` on both)
- Multi-call turns: several calls in one request come back in order, and results are fed back as one JSON
  array -- including for a single call, matching Python's `run()` -- confirmed identical to Python on both counts
- Numeric grounding: the engine withholds a call into `suppressed_calls` when a required value has no basis in
  the request text; `NeedleResponse.ungrounded()` and `Needle.run(..., strict)` (on by default) block a
  handler from running on an invented number, ported from the Python package's grounding check
- Negation detection (`validation.negation`) surfaces as `NeedleResponse.negation()`
- `needle_last_error` returns real engine text (confirmed via bad weights and an oversized tool schema)
- Library/weights auto-discovery of the Python package's cache (`~/.cache/cactus-needle/v3/<ver>/`), including
  version subfolders, with no environment variable required
- `NeedleAssets`: real download from Hugging Face (`Cactus-Compute/needle3`), engine-wheel unpacking per
  platform, checksum verification against both a built-in pin and the Hub's own advertised checksum
  (`X-Linked-Etag`), atomic writes, cross-process locking, offline mode, retry on truncation
- A 2000-turn soak with `reset()` between turns, no crash
- **Temporal (year) grounding**, ported from the Python package: a `format: date`/`date-time` argument whose
  year contradicts every year written so far in the conversation (or, for a relative phrase like "next
  Friday" with no year of its own, the system date fact) is surfaced through `NeedleResponse.ungrounded()`
  and `Needle.run(..., strict)` the same way a numeric mismatch is. Confirmed against the real engine that it
  (a) does not itself reason about years -- it flags almost any reformatted `date` argument in
  `validation.ungrounded`, correct year or not -- and (b) `Needle.extract(...)` / `Extraction.extract(...)`
  correctly tells a literal, correct year apart from one the engine silently swapped for another, using that
  flag plus this check together, not either alone.
- **`extract()`** (`Extraction.extract` / `Needle.extract`, one-shot structured extraction): confirmed against
  the real engine that it returns filled-in arguments when the engine is confident, returns `null` on a
  refusal, and -- in strict mode (the default) -- throws `NeedleExtractionException` when a value isn't
  grounded, including when there is no year anywhere in the input for the engine's own date-format flag to be
  reconciled against (a deliberately conservative case, not a bug: see `NeedleTemporalGroundingIT`).
- **The tool-index / retrieval path**, above the ~20-tool threshold Cactus's own docs warn about. Confirmed
  directly against the engine (a raw probe against `libneedle.so`, before writing the Java test) that
  `Needle.builder().toolIndexPath(...)` pointing at a file that does not exist is accepted by `needle_init`
  without error and without the engine creating that file as a side effect -- it is a safe, inert pass-through
  on this engine build, not something this SDK needs to build itself. The actual recommended retrieval
  pattern -- embed the query and each tool description with `Needle.embed`, rank by cosine similarity, and
  construct a **new** `Needle` with just the shortlisted tools (the tool set is fixed at construction) -- is
  verified end to end with 30 tools: the top-5 shortlist always keeps the correct tool and the shortlisted
  `Needle` still calls it correctly. See `NeedleToolIndexIT`.
- The `type`/`success`/`error` response envelope was fuzzed against a battery of malformed, partial and
  oddly-typed JSON (wrong field types, missing fields, a non-object top level, garbage nested arguments):
  `NeedleResponse.parse` never throws and always falls back to a safe default. See `NeedleResponseFuzzTest`.

## What is not verified

- **Windows and macOS.** Everything above ran on Linux x86-64 only. The engine-wheel platform tags and the
  library file name per OS (`libneedle.so` / `.dylib` / `needle.dll`) are implemented but untested. See
  [CI](#ci) below.

## Getting the engine and weights

**Easiest:** let the SDK download them.

```java
Needle.builder().tools(...).autoDownload(true).build();
```

This checks `~/.cache/cactus-needle/v3/` first (shared with the Python package, so nothing re-downloads if
you've already used `pip install cactus-needle`), and otherwise fetches `needle3.cact` and the right engine
wheel for your OS/CPU from Hugging Face, verifies checksums, and caches them. It's off by default so a plain
`Needle.builder().build()` never touches the network; opt in explicitly, or point at files yourself:

```java
Needle.builder().library(Path.of("/path/to/libneedle.so")).weights(Path.of("/path/to/needle3.cact")).build();
```

Other ways to get the files:

- **Python package:** `pip install cactus-needle && needle fetch && needle download needle3` -- the SDK finds
  its cache automatically.
- **`NEEDLE3_LIB_PATH`** environment variable, for the engine specifically.
- **Build it yourself** from the static `libneedle.a` the native platform folders ship
  (`gcc -shared -o libneedle.so -Wl,--whole-archive libneedle.a -Wl,--no-whole-archive`; macOS:
  `clang -dynamiclib -o libneedle.dylib -Wl,-force_load,libneedle.a`).

`NeedleAssets` also works standalone, e.g. to show a progress bar before constructing `Needle`:

```java
var result = NeedleAssets.ensure(new NeedleAssets.Options()
        .onProgress(p -> updateProgressBar(p.file(), p.done(), p.total())));
Needle.builder().library(result.library()).weights(result.weights()).tools(...).build();
```

## Build and run

    mvn package
    java --enable-native-access=ALL-UNNAMED -cp target/needle3-java-<version>.jar:<jackson jars> examples/Demo.java

Without Maven: `javac --release 22 -cp <jackson jars> -d out src/main/java/**/*.java`.

Tests:

    mvn test                                              # fast; no network, no real engine

This project has no Failsafe plugin, so Surefire's default `*Test.java` pattern is all a plain `mvn test`
picks up (`GroundingTest`, `NeedleAssetsTest`, `NeedleResponseFuzzTest`). Every `*IT.java` class needs an
explicit `-Dtest=...`, most conveniently all together:

    mvn test -Dtest=NeedleIT,NeedleChainIT,NeedleMultiCallIT,NeedleTimerIT,NeedleBadWeightsIT,\
NeedleOversizeIT,NeedleTemporalGroundingIT,NeedleExtractIT,NeedleToolIndexIT

or one at a time, e.g. `mvn test -Dtest=NeedleExtractIT`. These need `needle3.cact` and `libneedle.so`
reachable via the discovery order above; they are not gated behind an env var, so make sure the cache is
populated first (`NeedleAssets.ensure()` once, or the Python package). `NeedleIT` alone takes ~2 minutes
(it includes a 2000-turn soak).

    env NEEDLE_NET_TESTS=1 mvn test -Dtest=NeedleDownloadIT # real download from Hugging Face (~36 MB)

## Notes and known constraints

- **One instance per JVM.** The engine has one process-global model and conversation. `Needle`'s constructor
  enforces this and throws if a second instance is opened while one is still open. Keep one long-lived
  instance and call `reset()` between independent conversations/commands. A second `needle_init` in one
  process has been reported to segfault upstream (Cactus issue #130) -- this SDK's single-instance guard exists
  specifically to avoid that path.
- **Tools are fixed at construction.** There's no API to change the declared tool set on a live `Needle`; to
  shortlist tools per request (see the tool-index bullet above) or to swap `extract()`'s schema, build a new
  `Needle`/close the old one, rather than trying to reuse one instance.
- **`Extraction.extract(...)` opens and closes its own `Needle`**, so it cannot run while another instance is
  already open in this JVM (the one-instance-per-JVM rule above) -- close it first, or call
  `Needle.extract(schema, text)` on the already-open instance instead, provided `schema` is one of the tools
  it was already built with.
- **Weights are memory-mapped**, read in place, and stay mapped until `close()`.
- **An empty `functionCalls()` is not necessarily a refusal.** Check `needsClarification()` /
  `ungrounded()` first -- the engine may have withheld a call for a missing required value rather than refused
  outright. `isRefusal()` accounts for this (true only when nothing was called and nothing was withheld).
- **`confidence` is `null` for uncalibrated tuned weights** (no confidence head). It's meaningful only on the
  *first* turn of a conversation; the turn following a fed-back tool result has a different, not directly
  comparable, confidence distribution -- don't gate on it there.
- **`Needle.run(..., minConfidence)`** applies `NeedleResponse.verdict()` to the first turn only, for the
  reason above. `verdict()` is a starting policy (act / ask / escalate), not a guarantee -- see next point.
- **Confidence and grounding do not catch every wrong call.** In testing, an out-of-scope request containing
  an in-vocabulary tool argument (e.g. a numeric value and a valid enum member) can produce a *high-confidence,
  fully-grounded* call to the wrong tool. Grounding and confidence reduce risk; they are not a safety
  guarantee. Validate destructive or hard-to-undo actions in your tool handlers regardless of the verdict.
- **Language:** confidence calibration is documented as English-only by Cactus; treat scores from other
  languages with caution, or skip confidence-based gating for non-English input.
- **Thread safety:** `Needle`'s methods are internally synchronized, so concurrent calls are serialized rather
  than racing, but turns are stateful (each depends on the previous one) -- don't interleave unrelated
  conversations on one instance without `reset()` between them.

## CI

See [`.github/workflows/ci.yml`](.github/workflows/ci.yml). It runs the no-network unit tests on every push,
and a full matrix (Linux x86-64/arm64, Windows, macOS Intel/Apple Silicon) that downloads the real engine and
weights and runs the behavioural tests against them, gated so it doesn't run on every commit. See the workflow
file for how to trigger it.

## License

This SDK is licensed under the [Apache License, Version 2.0](LICENSE).

Needle 3 itself, the engine binaries, and the model weights are Cactus Compute's; this SDK never bundles or
redistributes them — `NeedleAssets` downloads them directly from Cactus's own Hugging Face repository
([`Cactus-Compute/needle3`](https://huggingface.co/Cactus-Compute/needle3)) at runtime, on the end user's
machine. As of this writing, Cactus publishes both the [`needle` engine repo](https://github.com/cactus-compute/needle)
and the `needle3` model weights under Apache-2.0 as well, but that's their license to change; check it yourself
before bundling or redistributing those assets rather than downloading them through `NeedleAssets`.
