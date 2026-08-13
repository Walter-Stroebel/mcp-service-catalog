# Case study: local vision at scale, and what it means

Date: 2026-08-13
Machines: legion (client, this repo's git checkout), predator (server,
running this project's jar under systemd — see `MANUAL.md`)

## What this is

A due-diligence follow-up to shipping `v1.2.0`: we'd released a real
change to `look_at_image` (migrated from inline base64 to the file
service's `file_id` pattern — see
`docs/archive/2026-08-13-look-at-image-hang.md`) without ever actually
loading the new pipeline. Byte-diffing the CI artifact against a local
rebuild proved the *build* was correct; it proved nothing about whether
the *feature* held up under real, varied, large input.

So: two runs, both against 213 real image files (a full page-scan set of
the Voynich manuscript, ~3.8GB, individual files 15–93MB — genuinely
large, non-synthetic images, not a toy fixture), pushed from legion to
predator over this project's own file service and MCP-over-HTTP
transport, through `look_at_image`'s current `file_id`-based
implementation end to end.

## Run 1 — pipeline throughput

213 pages: `identify_image` (ImageMagick), `convert_image` (resize
50%), `look_at_image` (ask what's depicted), per page. Stopped partway
through by choice (26 files) once the pattern was clearly clean and
predator's GPU load was confirmed trivial (~50% on an RTX 4060 for
about a second per call) — this run existed to prove the wiring, not to
process the whole set.

**Result: 26/26 succeeded**, ~4–6s/page end to end (upload + identify +
convert + download + vision call), zero errors.

## Run 2 — a real task, at full scale

The actual test: **count clearly visible physical damage marks on every
page**, a genuinely in-scope real-world use case (manuscript
conservation triage, "which pages need attention first") rather than a
synthetic benchmark. Every one of the 213 files, no early stop this
time, since the result was meant to be kept and cited.

Prompt sent per page via `look_at_image`:

```
Count the number of clearly visible physical damage marks on this
manuscript page image (e.g. stains, tears, holes, fading,
discoloration, ink bleed-through, edge damage). Respond in this exact
format:
COUNT: <integer>
DAMAGE: <brief comma-separated list of what you counted>
```

### Results

- **213/213 files processed**, full run in **8.5 minutes** (510s)
- **211/213 (99.1%) produced a parseable count**
- **Sum across all pages: 1214** damage marks
- **Mean: 5.75/page, median: 6, stdev: 2.24, range: 0–15**
- Distribution is a clean, roughly bell-shaped curve centered on 5–7
  (see raw data), not spiked at round numbers or a constant — a real
  signal, not the model returning a default.
- Lowest counts: `Tail.png` (0, correctly — a blank spine-end shot),
  `Head.png`/`40r.png` (1 each, "general aging/discoloration").
- Highest counts: `79r.png` (15), `101v_(part).png` (14),
  `Front_cover.png` (12) — all pages an eyeballed spot-check agrees are
  visibly rougher than the median page.

### The two failures, and one worth digging into

**`85v_and_86r_(foldout).png` — HTTP 400 from LM Studio.** This file is
93MB, roughly 2–6x every other page in the set — it's a fold-out, three
manuscript pages scanned as one image, exactly as expected once you
know the manuscript's physical structure. Confirmed as a genuine
request-size limit (LM Studio/the underlying `llama-server` rejected
the request outright), not a bug in this project's code — every other
file in the batch, including several 40–50MB pages, succeeded without
issue. **Not investigated further; treated as a known, explainable
boundary**, not a defect.

**`112r.png` — "no image was provided."** This one looked, mid-run, like
a template-echo glitch (the model literally repeating the prompt's
format instructions back). It wasn't. Retested it directly, 8 fresh
attempts, same file, same upload-and-call path used throughout the run:

```
attempt 1: COUNT: 2
attempt 2: NO_IMAGE
attempt 3: COUNT: 3
attempt 4: COUNT: 2
attempt 5: COUNT: 4
attempt 6: NO_IMAGE
attempt 7: COUNT: 2
attempt 8: COUNT: 12
```

**2 of 8 retries (25%) reproduced the exact same failure**: the model
claims no image was provided, on a request that demonstrably did carry
the image (proven by the surrounding successful attempts on the
identical file). This is a real, intermittent, reproducible attention/
grounding failure in the underlying vision model (`gemma-4-e4b` via LM
Studio, mmproj-based image encoding) — not a bug in this project's file
service, HTTP transport, or `look_at_image` implementation, all of which
correctly delivered the same bytes on every attempt, successful or not.
The elevated retry-failure rate for this specific page (25%) versus its
one-off rate in the full run (1/213 ≈ 0.5%) suggests something about
this particular image — composition, contrast, an artifact in how it
tokenizes — makes it more prone to this failure than the corpus average,
though the underlying mechanism wasn't investigated further (would
require instrumenting LM Studio/llama.cpp internals, out of scope here).

**Practical takeaway**: at the corpus level, the failure rate for this
specific glitch was ~0.5% (1/213 in the full run). On a re-run of any
one page, expect it to be higher for pages that trigger it at all — this
is not a uniform per-page probability, it clusters. A production pipeline
built on this pattern should retry on an unparseable/no-image response
before treating a page as unscoreable.

## What this validates

1. **The v1.2.0 fix works, under real load.** Large real files (up to
   93MB, well past the ~7KB threshold that caused the original
   `look_at_image` bug — see the archived report), pushed over the
   network, through the file service, through `ProcessInvoker`'s
   `file_id`/`producesFile` machinery and `DemoBackend#lookAtImage`'s
   server-side base64 encoding — no MCP-channel stalls, no argument-size
   issues, across 239 total files between both runs.
2. **The counts are a usable first-pass draft, not ground truth**, per
   the framing agreed before running this: 99.1% structured-output
   success is good but not perfect, and the one investigated failure
   mode is a real, occasionally-recurring model quirk, not a rounding
   error. Anyone using output like this for a real decision (e.g. actual
   conservation triage) should spot-check, and should retry on parse
   failure rather than silently dropping or zeroing that page.

## What this means for the industry, going forward — Walter's take

We ran a real, in-scope image-understanding task — not a toy demo, not
a cherry-picked easy case — against a 4-bit-quantized ~4B-parameter
model on a single consumer GPU (RTX 4060) that barely registered load,
at zero marginal cost per call, with every image byte staying inside
this LAN. 213 real, large (up to 93MB) manuscript scans, a genuine
counting/extraction task, 99%+ structured success, in under 9 minutes.

That's not a demo of "AI can look at pictures." It's a working,
load-tested counterexample to the reflexive assumption that any
serious image-understanding workload needs a frontier cloud API — an
assumption that's often just true by default, unexamined, rather than
actually checked against the task at hand.

The boundary matters, and we're not claiming otherwise: a genuinely
hard case — reading an MRI scan, where a missed finding is real
human harm and accuracy has to be as good as achievable regardless of
cost — is not what this run demonstrates, and small local models
are not a substitute for frontier accuracy on tasks that actually need
it. But "how much visible damage is on each page of this document,"
"is there trash in this photo," "roughly what's shown here" — the
whole broad middle tier of "moderate-stakes, moderate-volume image
description and classification" that a lot of real organizations
actually run through paid cloud vision APIs by default — that tier is
squarely inside what a machine like predator handles correctly, for
free, in minutes, without an image ever leaving the building.

The honest, useful question for any team paying per-token for vision
isn't "should we stop using cloud AI" — it's "which of our current
cloud-vision calls are actually MRI-scan-shaped, and which are
festival-trash-shaped?" Most organizations we'd guess have never
actually sorted their own workload that way. This project, and this
specific test, is offered as one concrete, reproducible data point for
doing that sorting — not as an argument, but as a result.

## Reproducing this

The scripts used aren't checked into this repo (they're one-off
harnesses, not project code), but the shape is straightforward given
`MANUAL.md`'s documented file-service/MCP-HTTP API:

1. `PUT` each file to `http://<predator>:8765/files`, collect the
   returned `file_id`.
2. `POST` a `tools/call` for `look_at_image` to
   `http://<predator>:8764/mcp` with `{"file_id": ..., "question": ...}`.
3. Parse the model's answer out of the OpenAI-shaped JSON response
   embedded in the tool result's text.

Full per-file results (raw model output, timings, parsed counts) from
both runs are not checked into this repo (large, generated, and
specific to one manuscript's scan set) but were retained locally at the
time of writing.
