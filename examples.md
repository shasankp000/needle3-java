# needle3-java -- usage examples

Worked examples for every public entry point, grouped by task. All snippets assume:

```java
import shasankp000.needle.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
```

Every real-engine example needs `--enable-native-access=ALL-UNNAMED` on the `java` command line (see the
main [README](README.md#build-and-run)).

## Contents

1. [Getting the engine and weights](#1-getting-the-engine-and-weights)
2. [Declaring tools](#2-declaring-tools)
3. [One turn: `complete()`](#3-one-turn-complete)
4. [Reading a response: refusal, clarification, negation](#4-reading-a-response-refusal-clarification-negation)
5. [Multi-call turns](#5-multi-call-turns)
6. [The agentic loop: `run()`](#6-the-agentic-loop-run)
7. [Grounding in depth: numeric and temporal](#7-grounding-in-depth-numeric-and-temporal)
8. [Confidence and `verdict()`](#8-confidence-and-verdict)
9. [One-shot extraction: `extract()`](#9-one-shot-extraction-extract)
10. [More than ~20 tools: the retrieval pattern](#10-more-than-20-tools-the-retrieval-pattern)
11. [Embeddings directly](#11-embeddings-directly)
12. [Error handling](#12-error-handling)
13. [Lifecycle, concurrency, and the one-instance rule](#13-lifecycle-concurrency-and-the-one-instance-rule)
14. [Downloading assets with `NeedleAssets`](#14-downloading-assets-with-needleassets)

---

## 1. Getting the engine and weights

Three ways, pick one:

**a) Let the SDK fetch them on first use** (checks the shared cache first, only hits the network if nothing's
there):

```java
try (Needle n = Needle.builder().tools(myTool).autoDownload(true).build()) {
    // ...
}
```

**b) Point at files you already have** (a local build, or files placed by CI):

```java
Needle.builder()
        .library(Path.of("/opt/needle/libneedle.so"))
        .weights(Path.of("/opt/needle/needle3.cact"))
        .tools(myTool)
        .build();
```

**c) Do nothing** and rely on auto-discovery: `$NEEDLE3_LIB_PATH` for the engine, then
`~/.cache/cactus-needle/v3/` for both files (shared with the Python package -- if you've ever run
`pip install cactus-needle && needle fetch && needle download needle3` on this machine, this just works):

```java
Needle.builder().tools(myTool).build(); // finds everything itself, or throws NeedleException saying what's missing
```

A plain `Needle.builder().build()` **never touches the network** by itself -- `autoDownload(true)` (or calling
`NeedleAssets` yourself, see [§14](#14-downloading-assets-with-needleassets)) is what opts in.

---

## 2. Declaring tools

A `Tool` is a name, a description, and a parameter schema. Needle reads the description literally when
deciding whether a tool applies, so keep it narrow and concrete -- "Turn a room's lights on or off and set
brightness", not "Manage smart home devices".

```java
Tool setLights = Tool.named("set_lights")
        .description("Turn a room's lights on or off and set brightness")
        .param("room", Tool.string("which room to control"))          // required by default
        .param("on", Tool.bool(null))                                  // required, no description
        .param("brightness", Tool.integer(null, 0, 100), false)        // optional (3rd arg = isRequired)
        .build();
```

### Property helpers

```java
Tool.string("a free-text description")
Tool.bool(null)
Tool.integer(null)                          // no bounds
Tool.integer("count", 1, 64)                // inclusive min/max
Tool.number("a decimal value")
Tool.enumOf("severity level", "low", "medium", "high")   // closed set; decode grammar can't leave it
Tool.date("the day of travel")              // ISO date string; enables temporal grounding, see §7
Tool.dateTime("when the event starts")      // ISO date-time string; same
```

### Route-by-pattern triggers

Force a tool for requests matching a regex, bypassing the model's own routing (see Needle 3's docs on
"Route by pattern"):

```java
Tool stopEverything = Tool.named("stop")
        .description("Stop whatever is happening immediately")
        .triggers("(?i)\\bstop\\b", "(?i)\\bemergency\\b")
        .build();
```

### Wrapping an existing schema

If you already have tool JSON (compact form, or OpenAI's `{"type":"function","function":{...}}` shape):

```java
Tool fromJson = Tool.fromJson("""
    {"name":"get_weather","description":"Get the weather for a city",
     "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}
    """);
```

---

## 3. One turn: `complete()`

```java
Tool weather = Tool.named("get_weather")
        .description("Get the current weather for a city")
        .param("city", Tool.string("City name"))
        .build();

try (Needle needle = Needle.builder().tools(weather).autoDownload(true).build()) {
    NeedleResponse r = needle.complete("what's the weather in Lagos?");
    for (var call : r.functionCalls()) {
        System.out.println(call.name() + " " + call.arguments());
        // get_weather {"city":"Lagos"}
    }
}
```

`complete(text)` uses the builder's default `maxNewTokens` (512); pass a second argument to override it for
one call: `needle.complete(text, 128)`.

**Conversations are stateful.** Each `complete()` call depends on the previous one -- the engine remembers
tool calls and results within a conversation. Call `needle.reset()` to start a fresh, independent
conversation on the same instance (cheaper than building a new `Needle`, and required anyway since only one
instance can be open per JVM -- see [§13](#13-lifecycle-concurrency-and-the-one-instance-rule)):

```java
needle.reset();
NeedleResponse r2 = needle.complete("what's the weather in Nairobi?"); // unrelated to the Lagos turn above
```

**Feeding a tool result back** is just another `complete()` call, with the result as JSON text -- the engine
treats a turn that parses as JSON as a tool result, not a new user request:

```java
NeedleResponse r = needle.complete("what's the weather in Lagos?");
var call = r.functionCalls().get(0);
String resultJson = "{\"temp_c\":27,\"sky\":\"clear\"}"; // whatever your handler returned, serialised
NeedleResponse followUp = needle.complete(resultJson);
System.out.println(followUp.type()); // "respond" once the model has enough to answer
```

`run()` (§6) does this loop for you; reach for `complete()` directly only when you want to own each step
yourself (e.g. to show intermediate state in a UI, or drive multiple tool calls with custom scheduling).

---

## 4. Reading a response: refusal, clarification, negation

A `NeedleResponse` is more than just `functionCalls()`. An **empty `functionCalls()` is not automatically a
refusal** -- the engine may have withheld a call because a required value was missing, which is a different
situation (ask the user) from a genuine refusal (nothing here applies).

```java
NeedleResponse r = needle.complete(userText);

if (r.isRefusal()) {
    // functionCalls() AND suppressedCalls() are both empty: nothing matched
    respond("Sorry, I can't help with that.");
} else if (r.needsClarification()) {
    // a call was withheld because a required argument had no basis in the text
    // r.suppressedCalls() shows what it WOULD have called
    // r.ungrounded() names the missing paths, e.g. ["set_timer.minutes"]
    respond("How many minutes?");
} else if (!r.functionCalls().isEmpty()) {
    for (var call : r.functionCalls()) {
        runTool(call.name(), call.arguments());
    }
} else {
    // type() == "respond": the model is just talking, no tool involved
    respond(r.reasoning());
}
```

`negation()` flags a request the engine detected as negated -- the literal call might mean the opposite of
what a naive reading suggests ("don't turn on the kitchen lights" can still come back as a `set_lights`
call with `"on": true` in the arguments; check `negation()` before trusting the call at face value):

```java
NeedleResponse r = needle.complete("don't turn on the kitchen lights");
if (r.negation()) {
    // the literal call may not mean what it looks like -- ask for confirmation
}
```

Other fields worth knowing about: `r.type()` (`"call"` or `"respond"`), `r.reasoning()` (the model's own
one-line explanation, handy for logs), `r.confidence()` (see §8), and `r.raw()` -- the full parsed JSON if you
need something this record doesn't surface yet (e.g. `r.raw().path("prefill_tps")`).

---

## 5. Multi-call turns

A single turn can produce several calls, returned in order:

```java
NeedleResponse r = needle.complete("turn on the kitchen lights and lock the front door");
// r.functionCalls() == [
//   {name: "set_lights", arguments: {room: "kitchen", on: true}},
//   {name: "lock_door",  arguments: {door: "front door", locked: true}}
// ]
```

If you're feeding results back manually (not using `run()`), send them as **one JSON array, in call order**
-- even for a single call, this matches the engine's expectation:

```java
var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
var results = mapper.createArrayNode();
for (var call : r.functionCalls()) {
    results.add(mapper.valueToTree(runTool(call.name(), call.arguments())));
}
NeedleResponse followUp = needle.complete(results.toString());
```

---

## 6. The agentic loop: `run()`

`run()` does the "call, execute, feed back" loop for you: you supply a handler per tool name, it calls them,
feeds results back, and repeats until the model responds or `maxSteps` is hit.

```java
Map<String, Needle.ToolHandler> handlers = Map.of(
        "set_lights", args -> Map.of("ok", true, "room", args.path("room").asText()),
        "lock_door",  args -> Map.of("ok", true, "door", args.path("door").asText())
);

Needle.RunResult out = needle.run("turn on the kitchen lights and lock the front door", handlers, /* maxSteps */ 6);

System.out.println(out.results());      // every tool result, in call order, across all steps
System.out.println(out.last().type());  // "respond" once the loop finished cleanly
```

A `ToolHandler` is `Object call(JsonNode arguments) throws Exception` -- return anything Jackson can
serialise; throwing reports `{"error": "<message>"}` back to the model instead of executing:

```java
Needle.ToolHandler setLights = args -> {
    String room = args.path("room").asText();
    if (room.isBlank()) throw new IllegalArgumentException("no room given");
    return Map.of("ok", true, "room", room, "on", args.path("on").asBoolean());
};
```

### Strict mode (default: on)

With `strict = true` (the default), a call whose argument was flagged **ungrounded** -- numeric or temporal,
see [§7](#7-grounding-in-depth-numeric-and-temporal) -- is **not executed**; the model gets
`{"error": "ungrounded <paths>"}` instead of your handler running on an invented value:

```java
// strict is implied; equivalent to run(query, handlers, maxSteps, true, null)
Needle.RunResult out = needle.run("set a timer", handlers, 6);
```

Turn it off if you'd rather always execute and let your own handler validate:

```java
Needle.RunResult out = needle.run(query, handlers, maxSteps, /* strict */ false);
```

### Confidence gating

Pass `minConfidence` to gate the **first** turn only through `NeedleResponse.verdict()` (see §8) before
anything runs:

```java
Needle.RunResult out = needle.run(query, handlers, 6, /* strict */ true, /* minConfidence */ 0.7);

switch (out.verdict()) {
    case EXECUTE   -> { /* out.results() already has the outcomes */ }
    case ASK_USER  -> respond("Which room?");                 // out.results() is empty
    case ESCALATE  -> respond("Not sure -- let me get a human."); // out.results() is empty
}
```

When `out.verdict()` is anything but `EXECUTE`, `run()` returns immediately -- no handler was called, and
`out.last()` holds that first, ungated turn so you can inspect what the model would have done.

---

## 7. Grounding in depth: numeric and temporal

Needle's own engine flags values it can't justify from the input text (`validation.ungrounded` in the raw
JSON), and this SDK adds a matching **temporal** (year) check the engine doesn't do on its own. Both surface
through `NeedleResponse.ungrounded()`:

```java
NeedleResponse r = needle.complete("set a timer");
System.out.println(r.ungrounded()); // ["set_timer.minutes"] -- no number in the text to fill it with
```

### Numeric grounding

A flagged numeric argument is treated as trustworthy by `run(..., strict)` only if that **exact number** is
written somewhere in the query or system text:

```java
// "dim to 30" -> brightness: 30 is grounded (30 is literally in the text)
// "dim it a bit" -> brightness: 40 (say) would NOT be grounded (40 appears nowhere)
```

### Temporal (year) grounding

A `format: date` / `date-time` argument (built with `Tool.date(...)` / `Tool.dateTime(...)`, §2) whose year
contradicts every year written so far in the **conversation** -- or, for a relative phrase like "next Friday"
with no year of its own, the system's `date:` fact -- is flagged the same way:

```java
Tool schedule = Tool.named("schedule_event")
        .description("Schedule a calendar event on a given date")
        .param("title", Tool.string(null))
        .param("date", Tool.date(null))
        .build();

try (Needle n = Needle.builder().tools(schedule).system("date: 2024-03-15 Fri 10:00").autoDate(false).build()) {
    NeedleResponse r = n.complete("schedule a meeting on 2019-01-05");
    var args = r.functionCalls().get(0).arguments();
    if (!args.path("date").asText().startsWith("2019-")) {
        // the engine silently swapped the year -- r.ungrounded() contains "schedule_event.date"
    }
}
```

**Important nuance, confirmed against the real engine:** the engine's own flag fires on almost *any*
reformatted date, correct year or not -- it has no year-aware reasoning of its own. So `ungrounded()` alone
can't tell a legitimate derived date apart from a hallucinated one. `Needle.extract()` / `Extraction.extract()`
(§9) is what actually reconciles the two; a plain `run(..., strict)` call treats *any* flagged date as
unsafe to execute on, which is the conservative (and correct) default for automatically-executed tool calls.

Years accumulate across turns in one conversation and reset on `reset()`:

```java
n.complete("we're planning around March 2019");  // no tool call, but the year 2019 is now "known"
n.complete("schedule it for 2019-06-01");         // 2019 is licensed by the earlier turn
n.reset();                                         // forgets it -- back to a blank slate
```

---

## 8. Confidence and `verdict()`

```java
NeedleResponse r = needle.complete("dim the living room to 30");
Double confidence = r.confidence(); // null for tuned/uncalibrated weights; a probability otherwise
```

`confidence` is only meaningful on the **first** turn of a conversation -- a turn following a fed-back tool
result has a different, incomparable distribution. Don't gate on it there (`run()` already knows this: its
`minConfidence` gate only ever applies to the first turn).

`verdict(minConfidence)` is a starting policy, not a guarantee:

```java
switch (r.verdict(0.7)) {
    case EXECUTE   -> runTheCalls(r.functionCalls());
    case ASK_USER  -> askForTheMissingValue(r.ungrounded());
    case ESCALATE  -> handOffToAHuman();
}
```

Roughly: negated requests always escalate; confident calls execute; a confident-but-incomplete call (missing
a required value) asks the user; anything else escalates. Confidence and grounding **do not catch every
wrong call** -- an out-of-scope request that happens to contain an in-vocabulary argument can still produce a
high-confidence, fully-grounded call to the *wrong* tool. Validate destructive or hard-to-undo actions in
your handlers regardless of the verdict.

---

## 9. One-shot extraction: `extract()`

For pulling structured data out of text without running the full agentic loop -- the tool schema doubles as
your extraction target:

```java
Tool trip = Tool.named("trip")
        .description("A trip a user wants to plan")
        .param("destination", Tool.string(null))
        .param("date", Tool.date("the day of travel"))
        .build();

JsonNode args = Extraction.extract(trip, "book me a flight to Lagos for March 20 2026");
// {"destination":"Lagos","date":"2026-03-20"}, or null on a refusal
```

`Extraction.extract` opens and closes its **own** `Needle` internally (loads weights, runs `needle_init`,
closes when done) -- convenient for occasional calls, but it means it can't run while another `Needle` is
already open in this JVM (the one-instance rule, §13).

### Options

```java
var opts = new Extraction.Options()
        .system("date: 2024-03-15 Fri 10:00")   // environment facts; also licenses a relative date's year
        .weights(Path.of("/opt/needle/needle3.cact"))
        .library(Path.of("/opt/needle/libneedle.so"))
        .maxNewTokens(256)
        .strict(true);                            // default; see below

JsonNode args = Extraction.extract(trip, text, opts);
```

### Strict mode: `NeedleExtractionException`

With `strict = true` (the default), an ungrounded result -- a hallucinated year, an engine-flagged value with
nothing to confirm it, or a detected negation -- throws instead of silently returning bad data:

```java
try {
    JsonNode args = Extraction.extract(trip, "book a flight for 2019-01-05",
            new Extraction.Options().system("date: 2024-03-15 Fri 10:00"));
} catch (NeedleExtractionException e) {
    // e.getMessage() names exactly what failed, e.g. "... not grounded in the input: date"
}
```

Turn it off to get whatever the engine produced regardless:

```java
JsonNode args = Extraction.extract(trip, text, new Extraction.Options().strict(false));
```

Note: with **no year anywhere** in the input (e.g. "next Friday" alone, in a fresh conversation), strict mode
deliberately rejects the derived date too -- there's nothing for the SDK to confirm it against, so it treats
the engine's own flag as a real failure rather than waving it through. This is intentional conservatism, not
a bug; pass more context (a literal date/year somewhere, or a `system` date fact plus something already
establishing a year) or use `strict(false)` if you'd rather accept it anyway.

### Reusing an already-open `Needle`

If you already have a `Needle` open with the schema as one of its declared tools, reuse its engine instead of
paying for another weights load:

```java
try (Needle n = Needle.builder().tools(trip).system("date: 2024-03-15 Fri 10:00").autoDate(false).build()) {
    JsonNode args = n.extract(trip, "book me a flight to Lagos for March 20 2026");
    // n.extract(trip, text, opts) also works, for the same Options as above
}
```

`n.extract(schema, ...)` throws `IllegalArgumentException` if `schema` wasn't one of the tools `n` was built
with -- it reuses the instance's engine, so the tool has to already be loaded.

### Converting to your own type

```java
record Trip(String destination, String date) {}

Trip t = Extraction.extractAs(Trip.class, trip, "book me a flight to Lagos for March 20 2026");
```

---

## 10. More than ~20 tools: the retrieval pattern

Above roughly 20 declared tools, Cactus's own guidance says accuracy degrades, and recommends shortlisting
tools per request. `Needle.builder().toolIndexPath(...)` exists in the API, but on the current engine build
it's a safe, inert pass-through (confirmed directly against the engine: pointing it at a file that doesn't
exist causes no error and no side effect) -- it isn't something you need to populate yourself, and it isn't
what makes shortlisting happen.

The pattern that actually works, verified end-to-end against the real engine with 30 tools:

```java
List<Tool> allTools = buildAllThirtyTools();
String query = "turn off the garage door";

float[] queryVector;
List<float[]> toolVectors = new ArrayList<>();
try (Needle n = Needle.builder().tools(allTools).build()) {
    queryVector = n.embed(query);
    for (Tool t : allTools) {
        toolVectors.add(n.embed(t.name() + ": " + t.toJson().path("description").asText()));
    }
} // close this instance -- the engine is process-global, only one open at a time

// rank by cosine similarity, keep the top few
record Scored(Tool tool, double sim) {}
List<Scored> ranked = new ArrayList<>();
for (int i = 0; i < allTools.size(); i++) {
    ranked.add(new Scored(allTools.get(i), cosine(queryVector, toolVectors.get(i))));
}
ranked.sort(Comparator.comparingDouble(Scored::sim).reversed());
List<Tool> shortlist = ranked.stream().limit(5).map(Scored::tool).toList();

// build a NEW Needle with just the shortlist -- the tool set is fixed at construction, there's no
// API to swap it on a live instance
try (Needle n2 = Needle.builder().tools(shortlist).build()) {
    NeedleResponse r = n2.complete(query);
    // the correct tool reliably survives the shortlist and gets called correctly
}

static double cosine(float[] a, float[] b) {
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
    return dot / (Math.sqrt(na) * Math.sqrt(nb));
}
```

This does mean two `Needle` instances (or more, if you re-shortlist per request) -- each one a weights
load + `needle_init`. For a fixed, known-in-advance tool catalogue, precompute the tool embeddings once
(they don't depend on the query) and only re-embed the query per request.

---

## 11. Embeddings directly

`embed()` works standalone too -- for your own similarity search, clustering, or the shortlisting pattern
above:

```java
float[] v = needle.embed("set an alarm"); // 3072 floats, unit-norm, on the shipped checkpoint
```

Throws `NeedleException` if the loaded engine build doesn't export `needle_embed` (older builds may not).

---

## 12. Error handling

Everything this SDK throws on its own is a `NeedleException` (unchecked). A negative return code from the
native engine carries `code()` and, when the engine build exports `needle_last_error`, real engine text in
the message:

```java
try (Needle n = Needle.builder().weights(Path.of("corrupt.cact")).build()) {
    // ...
} catch (NeedleException e) {
    System.err.println(e.getMessage()); // "needle_load failed for corrupt.cact (code -1): failed to load .cact model"
    System.err.println(e.code());       // -1
}
```

Common causes, from what's been reproduced against the real engine:

- **Corrupt/incompatible weights** -- `needle_load failed ... (code -1): failed to load .cact model`.
- **Tool set + system text too large for the model's context** -- `needle_init failed ... (code -1):
  needle_init context budget exceeded: static prefix is N tokens, model context is M tokens; reduce
  system/tools JSON or declare more than 5 tools to enable per-turn retrieval`. This is the real error you'll
  hit with a large, un-shortlisted tool set -- see [§10](#10-more-than-20-tools-the-retrieval-pattern).
- **Second `Needle` opened while one is still live** -- `NeedleException: Another Needle instance is open in
  this JVM; the engine is process-global. Close it first (or reuse it and call reset()).`
- **Missing engine/weights files** -- a clear message naming what's missing and where it looked (see §1).

`extract()`'s strict-mode failure is a distinct, narrower type (`NeedleExtractionException extends
NeedleException`), for when the call succeeded but the *value* isn't trustworthy -- see [§9](#9-one-shot-extraction-extract).

---

## 13. Lifecycle, concurrency, and the one-instance rule

The native engine has **one process-global model and conversation**. `Needle`'s constructor enforces this: a
second `Needle` while one is still open throws immediately, rather than risking the documented upstream
segfault from a second `needle_init` in one process (Cactus issue #130).

```java
Needle a = Needle.builder().tools(toolsA).build();
// Needle b = Needle.builder().tools(toolsB).build(); // throws: another instance is open
a.close();
Needle b = Needle.builder().tools(toolsB).build();    // fine now
```

Practical implications:

- **Prefer one long-lived instance** per process, with `reset()` between independent conversations, rather
  than opening/closing per request.
- **`Extraction.extract(...)`** opens and closes its own instance internally -- don't call it while another
  `Needle` is open; either close that one first, or call `n.extract(...)` on the already-open instance if the
  schema is one of its declared tools (§9).
- **The tool set is fixed at construction.** There's no API to change it on a live instance -- build a new one
  (after closing the old) to change tools, e.g. for the shortlisting pattern in §10.
- **`Needle`'s methods are synchronized**, so concurrent calls from multiple threads are serialized rather
  than racing -- but turns are still stateful (each depends on the last), so don't interleave unrelated
  conversations on one instance without `reset()` between them; use `try`/`finally` or a lock around a whole
  logical conversation if several threads share one instance.
- **Weights are memory-mapped** and stay mapped until `close()`. Always use try-with-resources or an explicit
  `close()` in a `finally` block.

```java
try (Needle n = Needle.builder().tools(myTools).build()) {
    // conversation 1
    n.complete("...");
    n.reset();
    // conversation 2, same instance, no shared state with the first
    n.complete("...");
} // weights unmapped, instance slot freed for the next Needle in this JVM
```

---

## 14. Downloading assets with `NeedleAssets`

Usually you don't call this directly -- `Needle.builder().autoDownload(true)` does it for you. Reach for it
yourself when you want a progress bar, custom cache location, or to pre-warm the cache separately from
constructing a `Needle`:

```java
var result = NeedleAssets.ensure(new NeedleAssets.Options()
        .onProgress(p -> updateProgressBar(p.file(), p.done(), p.total()))); // p.total() is -1 if unknown

Needle.builder().library(result.library()).weights(result.weights()).tools(myTools).build();
```

### Options

```java
new NeedleAssets.Options()
        .cacheDir(Path.of("/opt/needle-cache"))     // default ~/.cache/cactus-needle
        .revision("main")                            // float instead of the pinned commit (default: pinned)
        .engineVersion("3.0.1")                       // which engine build to fetch
        .endpoint("https://my-hf-mirror.example")     // default $HF_ENDPOINT or https://huggingface.co
        .offline(true)                                // never touch the network; fail if missing
        .weightsSha256("")                             // "" skips verification; unset uses the built-in pin
        .engineSha256(myPin)                           // pin the engine wheel too; unset trusts the Hub's own hash
        .onProgress(p -> { /* ... */ });
```

- **Offline mode** also follows `HF_HUB_OFFLINE=1` in the environment if you don't set `.offline(...)`
  explicitly.
- **Checksums**: weights are verified against a built-in pin while the default revision is used, or the
  Hub's own advertised `X-Linked-Etag` otherwise; pass your own to override either.
- **Concurrent processes** on the same cache directory are safe -- downloads are cross-process file-locked,
  and a `.part` file is used until the download is verified and atomically moved into place, so a crash
  mid-download never leaves a corrupt file where `Needle` would find it.

### Separate calls for library vs. weights

```java
Path lib = NeedleAssets.ensureLibrary(opts);
Path weights = NeedleAssets.ensureWeights(opts);
```

### Just the hash of a file (e.g. to compute a pin for `weightsSha256`)

```java
String sha = NeedleAssets.sha256(Path.of("needle3.cact"));
```
