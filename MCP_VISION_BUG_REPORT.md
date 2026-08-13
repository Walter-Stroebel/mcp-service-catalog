# look_at_image MCP call silently hangs in-session — investigation report

Date: 2026-08-13
Machine: this box (client), predator (server host, GPU box)
Claude Code version: 2.1.229 (confirmed current latest via npm registry at time of writing)

## Symptom

Calling `mcp__predator-catalog__look_at_image` from within a live Claude Code
session hangs indefinitely (observed up to ~2 minutes before user
interrupted with ESC), showing "Calling predator-catalog…" / "Undulating…"
in the UI. During the entire hang, **both this machine and predator show as
idle in the user's system monitoring** — no CPU/GPU activity on predator,
no sign LM Studio ever received a request.

This is not a "slow model" problem. It is a **dispatch problem**: the
request appears to never actually leave this Claude Code session's MCP
client, despite the UI indicating the call is in flight.

## What's confirmed NOT the cause

- **predator infra**: LM Studio is running on predator, `gemma-vision`
  model is loaded, listening on `127.0.0.1:1234` (confirmed via
  `ssh predator curl localhost:1234/v1/models` → 200, model listed).
- **The jar / catalog config**: `mcp-service-catalog.jar` process is
  running on predator (`ps aux` confirmed), `tools.json`'s `look_at_image`
  entry (kind `http`, target `http://localhost:1234/v1/chat/completions`)
  is correctly defined and matches the schema Claude Code's `/mcp` detail
  view shows.
- **The MCP server itself, end-to-end**: A standalone Python script
  (`/tmp/mcp_call.py` pattern below) that spawns
  `ssh predator java -jar .../mcp-service-catalog.jar .../tools.json` as a
  stdio subprocess, does the `initialize` / `notifications/initialized` /
  `tools/call` handshake by hand, and sends a real
  `look_at_image` call with a ~22KB base64 JPEG payload —
  **succeeded in ~20 seconds**, returning a correct, detailed
  vision-model description of the test image (a Clipdrop/Stability.ai
  alien-landscape generation, `/home/walter/Pictures/staDiff/Alien planet
  with cactus-like vegetation...jpg`, downscaled to 320x320 for the test).
- **Session's MCP connection to predator-catalog is not dead**: `/mcp` in
  the live session correctly lists all 9 catalog tools and shows the
  correct schema for `look_at_image` (`image_data`, `image_ext`,
  `question`, all required strings) — so it's not simply a broken/stale
  connection at the protocol-negotiation level.
- **Not a version issue**: `claude --version` → 2.1.229; npm registry
  `dist-tags.latest` for `@anthropic-ai/claude-code` is also 2.1.229 at
  time of writing. Already current.
- **Not a documented, still-open upstream bug** (as far as found): the
  closest matching GitHub issue,
  [anthropics/claude-code#36319](https://github.com/anthropics/claude-code/issues/36319)
  ("Claude Desktop silently drops MCP stdio tool calls when argument
  payload exceeds ~1KB"), was closed as stale, and its own investigation
  narrowed the bug to **Claude Desktop's Chat mode specifically** — "Desktop
  Code" and the VS Code extension were both confirmed unaffected at the
  same payload sizes in that issue's own testing. We are running the CLI
  (`claude` binary), not Desktop Chat mode, so this exact issue doesn't
  cleanly apply, though the symptom (request never reaches server stdin,
  confirmed via server-side silence) is otherwise identical to what we
  observed.
- Also checked and ruled out as *not* config-caused: no explicit deny rule
  for `predator-catalog` or `look_at_image` in
  `~/.claude/settings.json`, `~/.claude/settings.local.json`, project
  `.claude/settings*.json`, or `~/.claude.json`'s per-project
  `allowedTools`/`disabledMcpjsonServers` (all empty, not a deny list).

## Update 2026-08-13, post-reboot

Retried in-session:
- `roll_dice` (no payload): instant response. Connection is alive.
- 32x32 red JPEG, ~860 bytes base64: `look_at_image` succeeded in a few
  seconds, correct answer ("This image is **red**.").
- 160x160 alien-planet JPEG, ~7.4KB base64: hung indefinitely, same
  symptom as original report (UI shows call in flight, no server-side
  activity implied, had to interrupt).

So this reproduces post-reboot and **is payload-size-triggered**, with the
threshold somewhere between ~1KB and ~7KB of base64 tool-call argument
text. Not connection-wide (small payload and zero-payload calls both work
fine on the same connection), not a one-off wedge.

## Update 2026-08-13, MCP debug log analysis

Found the per-project MCP debug logs Claude Code writes on its own,
without any special flag:
`~/.cache/claude-cli-nodejs/<mangled-cwd>/mcp-logs-predator-catalog/*.jsonl`
(one file per MCP connection/session, named by connection timestamp).
For this repo that's
`~/.cache/claude-cli-nodejs/-home-walter-github-mcp-service-catalog/mcp-logs-predator-catalog/`.
Each line is one structured event: connection start/established,
`"Calling MCP tool: <name>"`, then either
`"Tool '<name>' completed successfully in Nms"` or a
`"Tool '<name>' failed after Ns: <error>"` line.

Cross-referenced against `ps aux` (client-side `ssh predator java -jar
...` subprocess PID) and `ssh predator ps aux` (server-side `java -jar`
PID) to make sure we were reading the log file for the *live* session,
not a stale one from an earlier `claude` process — there were three log
files present from different sessions today, easy to grab the wrong one.

Findings from the live session's log file, correlated with the repro
steps above:
- `roll_dice` call: `"Calling MCP tool: roll_dice"` →
  `"completed successfully in 17ms"`. Normal.
- 32x32 red JPEG `look_at_image` call (~860B base64, the one that
  worked): `"Calling MCP tool: look_at_image"` →
  `"completed successfully in 527ms"`. Normal — both the dispatch-start
  and completion lines are present, as expected.
- 160x160 alien-planet JPEG `look_at_image` call (~7.4KB base64, the one
  that hung): **no `"Calling MCP tool: look_at_image"` line appears at
  all**, before, during, or after the hang. Confirmed by watching the log
  file (`ps`-verified live PID) while the hang was in progress and after
  interrupting it — the line never appeared.

This is the key finding: **the hang happens before Claude Code's own MCP
client logs the call as dispatched.** Whatever is blocking is upstream of
the stdio tool-call send — i.e. NOT the JSON-RPC/stdio transport to the
jar (ruled out separately: standalone Python harness proved the jar
handles a 22KB payload over that same transport in ~20s), NOT the jar
itself, NOT LM Studio/predator (verified up and responsive via `curl`
*during* the hang). Whatever it is lives inside Claude Code's own
process, in the code path between "model emits a tool_use content block"
and "MCP client writes tools/call to the subprocess's stdin and logs
it" — something this investigation cannot see into from outside the
client binary.

Consistent with this: the user separately observed a slow "trickle of
tokens" rising in the CLI's status view during the hang, rather than
total silence — suggesting something CPU-bound and slow (e.g.
re-serializing/validating/hashing the large argument string
node-by-node) rather than a classic blocked-on-I/O wait, though this is
inference, not confirmed via any log evidence.

Root cause of the *design* that exposed this: `look_at_image`'s
`tools.json` schema takes `image_data` as an inline base64 string
argument — i.e. the entire image is embedded directly in the MCP
tool-call JSON payload, model-generated, char by char. This was always a
bad shape for anything but tiny images; the fix in progress is to
change the tool to take a filesystem path instead (model writes/points
to a file, jar reads bytes itself), which sidesteps whatever this client-
side pre-dispatch problem is entirely, rather than chasing it further.
That fix is **separate from and does not explain** why the client
appears to choke on a large *argument string* pre-dispatch — that
remains an open question about Claude Code's own internals, being
investigated in parallel by running `claude -d` / `claude --debug-file
<path>` (full unfiltered debug mode, since we don't know which category
covers whatever this pre-dispatch stage is) against a fresh repro of the
same 160x160 hang, to see if debug mode surfaces anything the
project's own background MCP log doesn't.

## Update 2026-08-13, `-d`/`--debug-file` result — ROOT CAUSE FOUND, revises the analysis above

Ran a second, separate `claude` process with full unfiltered debug
logging: `claude --debug-file /tmp/claude-debug.jsonl -d`, reproduced the
same 160x160 alien-planet `look_at_image` hang inside it, let it sit
~2 minutes, then interrupted (ESC). 626 lines of debug output captured.

Grepping for `look_at_image`/MCP dispatch showed a **first** `look_at_image`
call (a small image, in that fresh session) completing normally end to
end:

```
08:48:39.469Z  MCP server "predator-catalog": Calling MCP tool: look_at_image
08:48:40.061Z  MCP server "predator-catalog": Tool 'look_at_image' completed successfully in 592ms
08:48:40.063Z  [Stall] tool_dispatch_end ... outcome=ok durationMs=596
```

Then a few tool calls later (a couple of `Bash`/`Read` calls to load the
alien-planet image), the model started a new turn intending to call
`look_at_image` again with the large image. The tail of the log:

```
08:49:00.716Z  [API:timing] dispatching to firstParty model=claude-sonnet-5
08:49:00.717Z  [API REQUEST] /v1/messages ...
08:49:03.275Z  Stream started - received first chunk
08:49:03.275Z  [API:timing] first byte after 2559ms
08:49:22.413Z  Fast mode unavailable: ...              <- routine noise line
08:50:24.389Z  Fast mode unavailable: ...               <- next log line: ~82s later!
08:50:24.407Z  High write ratio: ...
08:50:25.681Z  [onCancel] source=local focusedInputDialog=undefined streamMode=tool-input
08:50:25.694Z  [engine] turn 1 end (turns=12 usage in=569 out=2674 cost=$0.2977 api=37120ms stop=tool_use resultLen=0)
08:50:25.695Z  [engine] turn ended in error: [ede_diagnostic] turn aborted (aborted_streaming) stop_reason=tool_use
```

**No `"Calling MCP tool: look_at_image"` line ever appears for this second
call** — confirming what the project's own background MCP log showed in
the earlier update. But this debug log shows *why*: the API response
stream (`/v1/messages`) started, got its first chunk at 08:49:03, and
then went **silent for ~82 seconds** with `streamMode=tool-input` —
i.e. the client was still receiving/assembling the streamed `tool_use`
block's `input` JSON (the `image_data` argument: the base64 blob) when
the stream stalled. The turn was only ever aborted because the user
interrupted it (`[onCancel] source=local`); left alone it might have
gone longer still. `stop_reason=tool_use` on the aborted turn confirms
the model was still inside generating the tool_use content, never got to
emit a complete one.

**This revises the earlier "hang is somewhere in Claude Code's own
pre-dispatch code" theory.** It is not client-side dispatch logic at
all — the client's MCP call code was never reached because the *model's
own streamed output* (generating a long base64 string as tool-input
content) stalled server-side, upstream of the client entirely. This is
an Anthropic API/model-serving-side streaming issue with long
tool-input generation, not a bug in this repo's Java server, not in
LM Studio/predator, and not in Claude Code's MCP transport or dispatch
code — all of which were independently verified healthy and are
irrelevant to this failure mode.

This also matches the user's live observation of a slow "trickle of
tokens rising" in the status view during the hang: that's the output
token counter for the still-in-progress tool_use content (the model
generating base64 characters), not any local processing.

**Practical fix (in progress, separate from this root-cause finding):**
change `look_at_image`'s `tools.json` schema so the model passes a
filesystem path instead of inline base64 image bytes, and have the jar
read the file itself. This avoids asking the model to ever generate a
large base64 blob as tool-call output, which sidesteps this exact
stream-stall regardless of what's actually causing it server-side.

**Worth filing upstream**, since this would affect any MCP tool whose
schema asks for a sizeable inline string argument (not unique to
`look_at_image`) — Anthropic's own docs generally recommend
resource/path-based patterns over inline blobs for exactly this class of
reason, but the actual failure mode (a multi-minute silent stream stall
with no error, no timeout, no retry) is worth reporting precisely, with
this log excerpt, rather than leaving it as "large arguments are slow."

## Filed upstream

https://github.com/anthropics/claude-code/issues/86314 — filed
2026-08-13 by Claude Sonnet 5 on Walter's explicit instruction, under
his GitHub account.

## Update 2026-08-13, file-path fix attempted and REVERTED — two real problems found

Attempted the "practical fix" described above: changed `look_at_image` to
`kind: "method"`, backed by a new `DemoBackend#lookAtImage(Map)` that
took a `file` path argument, read the bytes itself with
`Files.readAllBytes`, base64-encoded them locally, and POSTed to LM
Studio — so the model would only ever have to emit a short path string
as tool-call input, never a large base64 blob.

Built, deployed to predator, tested via the same standalone Python stdio
harness used earlier, with the *client-side* alien-planet image path.
Failed immediately with `NoSuchFileException` — because **this repo's
architecture is deliberately two machines**: this client box ("legion")
runs Claude Code and holds the image files; `predator` runs the deployed
jar (`ssh predator java -jar ...`) and has no access to legion's
filesystem. That's not a bug, it's the DTAP boundary the deployment is
built around — legion is Dev, predator is roughly Acceptance/Production,
and there's intentionally no implicit trust letting predator's tools
reach back and pull files from legion. A bare filesystem-path argument
can only ever work for images already staged on predator (which is what
`view_image`'s existing `file` parameter presumably assumes), not for
arbitrary client-side images — so this "fix" solved the streaming-stall
problem by introducing a cross-machine reachability problem instead.

Given that, the question became: does MCP itself have a first-class way
to submit a *client-side* file as tool-call input, other than putting
bytes inline in the JSON arguments? Checked the spec
(modelcontextprotocol.io, both a general search and a direct fetch of
the resources spec page) rather than relying on background knowledge.
Confirmed:

- MCP `resources` (`resources/list`, `resources/read`) are
  **server-exposed, client-pulled** — a server advertises resources *it*
  can see and a client asks to read them. This is still bounded by the
  server's own reachable filesystem/state; it gives predator no way to
  pull a file that only exists on legion.
- Tool-call **results** can carry binary content as first-class
  `ImageContent`/`EmbeddedResource` blocks (server → client, i.e.
  *output* direction) — not helpful here since we need bytes to flow the
  other way, client → server, as *input*.
- Tool-call **arguments** are plain JSON. There is no equivalent
  first-class "attach a local file as input" primitive in the spec as
  currently understood — inline base64 in a string argument (what the
  original `look_at_image` design already did) appears to be the
  standard, if awkward, approach every MCP client/tool author uses for
  this direction (see e.g. the linked Cline issue about tool-result
  image handling — the general friction around binary-in-JSON-RPC shows
  up across the ecosystem, not just this repo).

**Flagging explicitly for the record: this finding (no client-to-server
file-submission primitive in MCP) should be treated as provisional and
worth independently re-verifying, not taken as settled.** MCP is not a
brand-new protocol at time of writing, and "the wire protocol has no way
to send a file to a tool" is a large enough gap that it's the kind of
claim an LLM (this investigation was largely conducted by Claude Sonnet
5) could get wrong — via an out-of-date training cutoff, an incomplete
web-search read, or plausible-sounding but incorrect synthesis. Before
fully relying on this conclusion for future design decisions, worth a
second pass: checking the very latest spec revision directly (the
2026-07-28-era stateless-transport rewrite mentioned in passing during
this search was not itself fully read), and/or checking how established
MCP servers with real file-upload-like tools (if any exist) actually
handle this.

**Reverted** the file-path attempt entirely: `DemoBackend#lookAtImage`
removed, `tools.json`'s `look_at_image` entry restored to its original
`http`-kind, inline-base64 form, rebuilt and redeployed to predator. The
one change kept from this detour: `CatalogTool.call()`'s exception
handling now unwraps `InvocationTargetException` and includes the real
exception class + message (was previously producing bare "Tool error:
null" for exceptions like NPEs with no message) — a genuine, unrelated
bug fix, independent of the reverted design.

**Net conclusion:** the original inline-base64 design was architecturally
correct given the DTAP constraint; the actual, sole bug is the Claude
Code / Anthropic API-side streaming stall on large tool-input generation,
already filed as
[anthropics/claude-code#86314](https://github.com/anthropics/claude-code/issues/86314).
Practical mitigation going forward: keep `look_at_image` base64 payloads
small (comfortably under ~5KB, i.e. pre-shrink/recompress images
client-side before calling) until/unless that upstream issue is
resolved. Documented this constraint directly in `tools.json`'s
`look_at_image` description.

## Update 2026-08-13, follow-up on "MCP has no file-submission primitive" — verified against spec text directly, claim revised

Per the hallucination-risk flag above, went back and actually fetched
the spec's transports page
(`modelcontextprotocol.io/specification/2025-06-18/basic/transports`)
directly rather than relying on the earlier search-summary pass. Result:
**the earlier claim was overstated and needs correcting.**

The spec states **no size limit whatsoever**, for either standard
transport:

- **stdio**: "Messages are individual JSON-RPC requests, notifications,
  or responses. Messages are delimited by newlines, and **MUST NOT**
  contain embedded newlines." No length cap stated anywhere. A
  100MB single-line JSON-RPC message (e.g. containing a ~133MB base64
  string) is spec-legal — it's still "one line, no embedded newlines."
  This matches what was already proven empirically earlier in this
  investigation: this repo's own `McpServer.run()` uses a plain
  `BufferedReader.readLine()` with no length cap, and the standalone
  Python harness test moved a 22KB payload through it with zero issue —
  nothing in the spec or in this server's implementation would treat
  100MB differently in principle, only more slowly.
- **Streamable HTTP**: a JSON-RPC message is just an HTTP POST body.
  No size ceiling in the spec — subject only to whatever a specific
  deployment's server/proxy/load-balancer imposes, which is
  implementation/deployment-specific, not protocol-specific.
- The "Custom Transports" section reinforces this is deliberate:
  "transport-agnostic... any communication channel that supports
  bidirectional message exchange" — the spec intentionally does not
  constrain payload size; that's left to implementers.

So the corrected claim: **MCP does not forbid a client from submitting
a 100MB block to a willing server — nothing in the protocol blocks it.**
What's actually true, and was the real substance behind the earlier
overstated claim, is narrower: there is no distinct binary/streaming/
attachment channel — a large blob still has to travel as a single JSON
string value (base64, ~1.33x size overhead) inside one message, with no
chunking, streaming, or resumability *for that payload* (SSE's own
resumability, where applicable, is a different layer — it resumes the
*stream of messages*, not a partial single oversized message).

**Walter's read on why "MUST NOT contain embedded newlines" is a smell,
not neutral spec text — and the more precise diagnosis of what's really
going on:** requiring line-delimited framing at all is inherited,
uncritically, from an era of protocol design (HTTP/1.0-and-earlier
line-oriented text protocols) built around the *actual, real* memory
constraints of small machines, where a fixed-size stack- or heap-
allocated byte buffer was a hard physical limit (his example: a machine
with 64KB of RAM cannot allocate a 100MB read buffer, full stop — this
wasn't a style choice, it was survival). Line-delimited framing plus
"read until you see the delimiter into a bounded buffer" was the
standard, reasonable answer to that constraint at the time. HTTP itself
outgrew this with `Content-Length:` — a length-prefixed framing that
lets a reader allocate (or stream-process) exactly what's coming, no
delimiter-scanning, no unbounded-line risk — precisely because
delimiter-based framing doesn't compose well with large, arbitrary
binary payloads.

MCP's stdio transport, by choosing newline-delimited JSON-RPC over
`Content-Length`-style length-prefixed framing, inherited that same
older shape without evidently inheriting the modern fix for it. Nothing
in the spec text sets a numeric cap, so it isn't a *stated* limit — but
"delimiter-scan a line of unbounded length into memory" is exactly the
implementation pattern that produces silent truncation, unbounded
buffer growth, or naive-parser stalls in the real world the moment any
link in the chain (a client library, a proxy, this investigation's own
Claude Code client) uses a bounded or naively-growing buffer instead of
one sized from a known length up front — which is a very plausible
category of bug for large tool-input arguments specifically, and lines
up with the *symptom family* this whole investigation chased (payload
seemingly vanishing or stalling above some threshold, no error, no
distinct failure mode reported), even though the specific instance we
root-caused (issue #86314) turned out to be a stall during the model's
own response *generation*, upstream of any transport buffer at all.

## Update 2026-08-13, this is a known ecosystem-wide pattern — not unique to us

Searched for prior reports of large-payload MCP hangs/failures rather
than assuming this investigation was the first to hit it — with
"thousands of people building MCP servers," an unreported bug this
fundamental seemed unlikely. It's well-precedented; three independent,
unrelated reports found, spanning different clients, different
directions (request vs. response), and different concrete mechanisms,
all in the same "large single-message stdio payload" territory as
Walter's framing-smell diagnosis above:

1. **[anthropics/claude-code#55923](https://github.com/anthropics/claude-code/issues/55923)**
   (closed, filed against a custom MCP server) — a ~16KB+ string tool
   argument (no `maxLength` in the tool's schema) gets **rejected before
   transit** by Claude Code's MCP client, with a "validation-shaped"
   error, before any request reaches the server. Confirmed via the same
   server accepting a >5x larger payload (68KB) when invoked directly,
   bypassing the Claude Code MCP client. This is a different failure
   mode from ours (fast client-side rejection vs. our silent multi-
   minute stream stall), but the same size ballpark (~16KB reject
   threshold there; our own bracket was 860B-works /
   7.4KB-hangs) and the same underlying theme: an undocumented,
   non-schema-declared size ceiling somewhere in Claude Code's MCP
   client path, with no clear error surfaced to the model or user. Per
   the reporter, this contradicts the spec's own stance that "argument
   validation beyond shape is the server's responsibility."

2. **[Cursor forum #158804](https://forum.cursor.com/t/bug-stdio-mcp-server-hangs-on-macos-when-response-8-kb-pipe-buffer-exhaustion/158804)**
   (Cursor, not Claude Code) — response-direction (server → client) hang
   above ~8KB, root-caused precisely: macOS anonymous pipes have a fixed
   8KB kernel send buffer; the server's `write()` blocks once the pipe
   fills, while Cursor's Node stream starts in paused mode and never
   drains it — a genuine deadlock, not a timeout, matching our "hangs
   forever, no error" symptom shape exactly, just triggered from the
   opposite direction (large tool *result*, not large tool *input*) and
   via a different concrete mechanism (OS pipe buffer + stream
   backpressure handling, not model-generation streaming). macOS/Linux-
   pipe-size-specific, not spec-level, but still: another real,
   independent case of "large single stdio message → silent hang," this
   time with the mechanism fully nailed down by the reporters.

3. **[modelcontextprotocol/python-sdk#2546](https://github.com/modelcontextprotocol/python-sdk/issues/2546)**
   (closed, not-a-bug) — directly relevant to the newline-framing
   critique above, though it revised the picture: reported as "the
   TypeScript SDK sends `Content-Length`-framed messages, incompatible
   with the Python SDK's pure-NDJSON stdio reader." A maintainer
   (`maxisbey`) checked the TS SDK's actual git history (all branches/
   tags) and confirmed this claim was **wrong** — the TS SDK has never
   used `Content-Length` framing for stdio; it's `JSON.stringify(msg) +
   '\n'`, same as Python, Go, and C#'s official SDKs. The actual
   `Content-Length` sender was a third-party Go-based client
   (Qoder) that mistakenly borrowed LSP-style framing — a bug in that
   client, not an ecosystem split among official SDKs. Worth flagging
   precisely *because* the earlier web-search synthesis pass in this
   same investigation had repeated the same wrong "TS SDK uses
   Content-Length" claim uncritically — another instance of the
   don't-trust-the-synthesis discipline paying off, this time on a
   claim about the ecosystem rather than about our own bug.

**Net revision of Walter's framing-smell diagnosis, given this:** the
instinct holds, but on narrower and more precisely correct grounds than
"the SDKs disagree on framing." All **official** MCP SDKs (TypeScript,
Python, Go, C#) are consistently, deliberately newline-delimited-only
for stdio — there is no `Content-Length` escape hatch anywhere in the
reference implementations, confirmed directly from source by a
maintainer. That's not an inconsistency to point at; it's the spec's
actual, committed design, spec-legal and unbounded in size exactly as
found in the earlier transports-page review. The "smell" is therefore
not "some SDKs patched around it and some didn't" — it's that **the
whole ecosystem, spec included, made the same bet**: newline-delimited
unbounded-length framing, no Content-Length-style pre-sized allocation,
for every official transport implementation, without documenting any
practical size guidance for tool/server authors. That bet is exactly
what produces the three independent failure reports above, each hitting
a different concrete limit (a kernel pipe buffer, an undocumented
client-side validation ceiling, a model-generation stream stall) because
nothing in the spec or reference SDKs gives implementers (or the several
layers built on top of them) a reason to expect or handle a large single
message any differently from a small one.

## Update 2026-08-13, what Anthropic's own API does for this exact problem — the pattern MCP is missing

Prompted by Walter asking, essentially, "how does Claude Code itself
submit a user's file to claude.ai for processing" — i.e. how does
Anthropic's own stack solve the identical problem (get a possibly-large
file from a client into a model-facing request) — since presumably it
doesn't just inline the bytes into the model's context either.

Checked `platform.claude.com/docs` for the **Files API**. Confirmed
mechanism:

1. Client uploads the raw file once via a dedicated, separate HTTP
   endpoint: `POST https://api.anthropic.com/v1/files`, a normal
   multipart file upload — properly `Content-Length`-framed like any
   ordinary HTTP upload, not JSON-string-encoded, not passed through the
   model at all.
2. The API returns a `file_id` (a short opaque string).
3. That `file_id` is referenced in a subsequent `Messages` request's
   content block (e.g. `{"type": "image", "source": {"type": "file",
   "file_id": "..."}}`) — a few bytes, trivially cheap for the model to
   handle, versus inlining the actual file contents into the
   conversation.

This is the structural pattern our whole investigation was missing:
**upload-once-get-a-handle, then pass the handle**, keeping raw bytes
entirely off the model-generation path and off the JSON-RPC-esque
request/response bodies the model has to read or write directly. It's
exactly the shape that would have avoided both problems chased in this
investigation — the DTAP cross-machine boundary (upload registers the
bytes wherever the *uploader* runs, decoupled from wherever the file
ends up being *used*) and the model-generation stream stall root-caused
as issue #86314 (the model only ever has to emit a short `file_id`
string as tool-call input, never a multi-KB base64 blob).

**MCP has no equivalent primitive.** As established in the transports/
resources review above, MCP's `resources/read` is server-exposed,
client-pulled — the mirror image of what's needed here. There is no
`resources/write`, no `POST /files`-equivalent, no first-class way for
an MCP **client** to register a blob with a **server** and receive back
a short handle to pass as an ordinary tool-call string argument. Every
MCP tool that needs to accept client-side binary input as of this
writing is left choosing between: inline base64 in a JSON string
argument (what `look_at_image` does, and what this whole investigation
was chasing the failure modes of), or a filesystem path (only viable
when caller and tool executor share a filesystem — not viable here, per
the DTAP-boundary correction earlier in this report). Given Anthropic's
own API already solved this exact problem with a register/reference
indirection, its apparent absence from the MCP tool-input side of the
spec looks like a real, well-motivated gap — not a hypothetical one —
though per the earlier hallucination-risk flag, this is still worth an
independent check against the latest MCP spec revision before treating
as fully settled (an `resources/write`-shaped proposal or extension
may already exist and simply not have surfaced in this investigation's
searches).

## Update 2026-08-13, implemented: own-port HTTP file service, mirroring the Files API pattern

Walter's framing, once the Files API parallel above was on the table:
this project is a generic MCP server driven by a config file, and the
right fix for the underlying problem isn't a workaround inside any one
tool's schema (base64-vs-path, chunking, etc.) — it's a **service**, the
same way `identify`/`convert` are a service on whichever machine has
ImageMagick installed. Concretely: "in a team there is ONE machine that
has ImageMagick installed; all devs must upload any image files to that
machine, then invoke the command, then download the result" — a literal
file up/download service, own port, outside MCP/JSON-RPC entirely,
exactly mirroring `POST /v1/files` → `file_id` → reference it.

Implemented as `FileService` (new class): a plain-HTTP service using the
JDK's built-in `com.sun.net.httpserver.HttpServer` (zero extra deps,
consistent with this project's "prefer zero deps" rule), started by
`Main` on its own thread/port (default 8765, overridable as a second
`Main` argument) alongside the existing stdio MCP loop:

- `PUT /files` — body is raw bytes, response is `{"file_id":"..."}`.
  Bytes are written straight to a process-lifetime temp directory
  (`Files.createTempDirectory`) and tracked in an in-memory
  `ConcurrentHashMap<String, Path>` — no persistence, no TTL sweep, no
  config knob for storage location, matching every other "trusted local
  config, keep it simple" choice already in this catalog's design.
- `GET /files/{file_id}` — streams the bytes back, 404 if unknown.
- Binds `0.0.0.0` deliberately, trusting the LAN — Walter's explicit
  call, consistent with how predator's LM Studio/Ollama backends are
  already reachable: anyone who can reach this service already has
  SSH/MCP-level access to the host, so per-request auth would be
  ceremony without a real threat model behind it.

Wired into the existing catalog machinery via a small, additive
extension rather than a new protocol layer: `ProcessInvoker` now
resolves a call's `file_id` argument (if present) through
`Main.FILES.resolve(...)` into a real on-disk path, exposed to the
target's `{...}` template as `{file_path}` — the same "special
template var" pattern `LaunchInvoker` already used for `{uploaded_file}`,
just sourced from the file service instead of decoding inline base64.
A new `CatalogEntry.producesFile` boolean (process-kind only) makes
`ProcessInvoker` allocate a fresh temp path, expose it as
`{output_path}`, and — once the process exits — register whatever
landed there with `FileService`, appending `file_id: <uuid>` as plain
text to the tool's result. No changes needed to `Invoker`, `Tool`, or
`McpServer` — the whole mechanism fits inside the existing "kind" model.

Added two real catalog tools demonstrating the pattern end-to-end,
using ImageMagick (present on both this machine and predator) as the
concrete "one machine has the tool installed" example from Walter's
framing:

- `identify_image` — `{file_id}` in, runs `identify {file_path}`,
  returns its text output.
- `convert_image` — `{file_id, convert_args, output_ext}` in,
  `producesFile: true`, runs
  `sh -c "convert '{file_path}' {convert_args} '{output_path}'"`,
  returns a new `file_id` for the result.

**Verified end-to-end** with a real 822KB JPEG (the same alien-planet
image used throughout this investigation) via a standalone Python
harness (upload via `PUT /files`, `tools/call identify_image` → correct
`JPEG 2048x2048`, `tools/call convert_image` with `-resize 50%` → new
`file_id`, `GET /files/{file_id}` → downloaded and independently
verified via `identify` as a valid `1024x1024` JPEG). At no point did
any file byte travel through an MCP tool-call argument or result — the
822KB (and, uncompressed, would be far more once base64-encoded) never
came near the ~7KB threshold that triggered issue #86314, because it
never needed to: the model only ever handles a short `file_id` string,
exactly matching the Files API's `file_id`-reference shape.

Built, tested locally, and deployed (`scp`'d jar + `tools.json`) to
predator, which has ImageMagick installed (`/usr/bin/convert`,
`/usr/bin/identify`) — so `identify_image`/`convert_image` are now live,
real tools in the deployed catalog, not just a local proof of concept.

**Net result:** the actual, generalizable fix this whole investigation
was circling. `look_at_image` itself was left as-is (small-payload
inline base64, per the earlier revert) since migrating it to `file_id` +
a `method`-kind tool reading `{file_path}` and building the LM Studio
request itself is a small, mechanical follow-up using exactly this same
machinery — not done in this session, noted below.

## What was NOT yet tried

- Binary-search bisection of the exact byte/token threshold where the
  stream-stall starts (issue #86314) — lower priority since the actual
  mitigation is "keep payloads small," not "find and dodge the exact
  threshold." Might still be useful ammunition if the upstream issue
  needs more evidence.
- Checking whether any MCP spec proposal, extension, or SEP
  (spec-enhancement-proposal-equivalent) already covers a client-to-
  server blob registration/handle primitive analogous to Anthropic's
  Files API — the gap identified above is plausible and well-motivated
  but not yet confirmed absent from the *very latest* spec discussion,
  only absent from the 2025-06-18 spec text actually reviewed.
- Checking whether Claude Code's MCP client implementation itself uses
  length-prefixed or delimiter-scanned buffering internally for stdio
  message reads — would confirm or rule out whether the "newline-
  delimited framing without Content-Length" smell is mechanistically
  implicated in issue #86314 specifically (our stall was upstream of
  MCP dispatch entirely, so this is more relevant to understanding
  issue #55923's client-side rejection than our own root-caused issue).
- Migrating `look_at_image` itself onto the new `FileService`/`file_id`
  pattern (a `method`-kind tool reading `{file_id}` → `{file_path}` via
  `ProcessInvoker`'s resolution logic, base64-encoding server-side just
  before the LM Studio POST, same as the earlier reverted attempt — but
  now with the DTAP-boundary problem actually solved, since the file was
  uploaded to *predator's* `FileService` directly rather than assumed
  to exist on predator's filesystem already).
- No TTL/cleanup exists yet for `FileService`'s staged files or its temp
  directory — every upload and every `producesFile` output accumulates
  under `java.io.tmpdir` for the life of the process. Fine for now
  (personal-scale, process-lifetime storage was the explicit design
  choice), but worth a sweep or size cap if this sees real use.
- No auth/access control on `FileService` beyond LAN-level trust
  (`0.0.0.0` bind, Walter's explicit call) — anyone who can reach
  predator's port 8765 can upload/download any tracked file_id. Matches
  the trust model already accepted for LM Studio/Ollama on the same
  host; flagging only so it's a conscious, re-confirmable choice if the
  deployment's reachability ever changes.

## Repro payload used in the standalone (working) test

```python
import subprocess, json, sys, threading, time

b64 = open('/tmp/b64.txt').read().strip()  # ~22KB base64 of a 320x320 JPEG

proc = subprocess.Popen(
    ["ssh", "predator", "java", "-jar",
     "/home/walter/mcp-service-catalog/mcp-service-catalog.jar",
     "/home/walter/mcp-service-catalog/tools.json"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, bufsize=1
)
# ... initialize / initialized / tools/call("look_at_image", {...}) ...
# Response arrived in ~20s with a full vision-model description.
```

## What to tell fresh-session Claude

After the reboot, paste this file's path
(`/home/walter/github/mcp-service-catalog/MCP_VISION_BUG_REPORT.md`) and
say something like:

> Read MCP_VISION_BUG_REPORT.md in this repo. Before that report was
> written, `look_at_image` hung with zero server-side activity when called
> from inside the Claude Code session, despite the MCP connection,
> catalog, and predator-side LM Studio all being independently verified
> healthy. Machine has been rebooted since. Try the in-session call again
> (small image first, then the original alien-planet image if it works) and
> tell me if it's fixed or still hangs.

If it still hangs post-reboot, the "not yet tried" list above is the next
diagnostic path — starting with the smaller-payload / different-tool
isolation tests, since those are cheap and would narrow whether this is
payload-size-triggered or connection-wide.
