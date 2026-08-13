# CLAUDE.md

## Java Style — Non-Negotiable

Same rules as every other Java project of Walter's, not repo-specific:

- **No `->` and no `::`, anywhere, full stop.** Use an explicit anonymous
  (or named) class implementing the real functional interface instead
  (`new Runnable() { public void run() { ... } }`). Lambdas and method
  references hide which method is being overridden and let code get
  invoked with no textual import trail for the reader (human or AI) to
  follow — this is a defense against a real language failure mode, not a
  style preference.
- No Spring, no annotation-magic frameworks. Explicit construction.
- Every dependency must justify its transitive closure. Prefer zero deps;
  Jackson is the one exception here (JSON parsing), used deliberately, not
  by default.
- Represent instants as epoch-millis `long`, not `java.time`.
- Javadoc only where the code can't speak for itself (class role/lifecycle,
  non-obvious contracts) — not on getters/setters or self-explanatory code.

## What this project is

Started as a hand-rolled MCP (Model Context Protocol) server built to learn
the wire protocol — deliberately not using the official Java MCP SDK
(`io.modelcontextprotocol.sdk`), because that SDK pulls in Project Reactor
(reactive-streams async plumbing) for a use case (stdio JSON-RPC) that
doesn't need it. It implements the protocol directly: newline-delimited
JSON-RPC 2.0 over stdin/stdout, the `initialize`/`initialized` handshake,
`tools/list`, and `tools/call` — see `McpServer`/`JsonRpc`.

Grew from that into a universal MCP gateway: a single server whose actual
tool roster is decided entirely by a JSON catalog (`tools.json`) read at
startup, not by compiled-in tool classes. Any MCP client that trusts this
one server (approved once, under one stable name, in the client's
`.mcp.json`) gets whatever tools the catalog currently lists — existing
Java methods, CLI commands, or HTTP APIs (local LLM backends like Ollama/LM
Studio included, see `tools.json`'s `ask_gemma`/`ask_mistral`) — without
the client ever needing its own config changed or re-approved when the
catalog changes. The point isn't hiding what runs; it's that one name can
front an arbitrary, swappable set of backends, decided by whoever controls
`tools.json`.

The larger idea this project is aimed at: any machine — a spare box, a
real team server, or a throwaway container/VM standing in for one —
becomes an MCP server just by dropping a `tools.json` next to this jar.
Whatever that machine has installed becomes a tool every MCP client on
the network can call, without anyone writing bespoke MCP server code —
the barrier drops from "implement the MCP protocol" to "write a JSON
entry." Each server stays a standalone, individually-configured island
on purpose: no discovery layer, a user adds each server to their own
client's config by hand. See `MANUAL.md`.

## Catalog-driven tools

Tools are no longer hardcoded classes. `Main` loads a JSON catalog
(`tools.json` next to the jar by default, or a path given as the first
argument) into a `List<CatalogEntry>` and registers one `CatalogTool` per
entry — the server's `tools/list` is exactly whatever the catalog
contains, decided at startup, not at compile time.

Each `CatalogEntry` has a `kind` naming which `Invoker` serves it:

- `method` — target is `fully.qualified.ClassName#methodName`, a public
  static `String methodName(Map<String,Object>)`, called via reflection
  (`MethodInvoker`). Demo backend methods live in `DemoBackend`
  (`add`/`echo`/`rollDice`, successors to the old toy tool classes).
- `process` — target is a JSON array of argv elements, each run through
  `TemplateSubstitution`'s `{argName}` filling, executed via
  `ProcessBuilder` (`ProcessInvoker`), stdout+stderr captured as the
  result.
- `http` — target is either a bare URL template (GET, no body) or an
  object `{"url":..., "method":"POST", "body":{...template...}}` whose
  body's string leaves are template-filled recursively — needed for JSON
  request bodies like chat-completions calls. Response body returned as-is
  (`HttpInvoker`) — the actual "one real backend call shared across many
  MCP clients" gateway case; `ask_gemma`/`ask_mistral` in `tools.json` hit
  LM Studio's and Ollama's chat endpoints on predator this way.
- `launch` — like `process`, but detached (`LaunchInvoker`): starts via
  `ProcessBuilder` and returns the launched PID immediately without
  waiting for exit or capturing output. For effects like opening a GUI
  window (`view_image`/`view_image_data` front infimg this way).

`CatalogTool` itself holds no behavior — it only picks the `Invoker` by
`kind` and reports exceptions as MCP tool errors (unwrapping
`InvocationTargetException` so a method-kind tool's real exception class
+ message surfaces, not a bare "Tool error: null"). The catalog is
trusted local configuration (reflection-by-string and shelling out from
JSON are normally avoided in this codebase's style; here that's the
entire point, not an oversight), not something derived from an untrusted
MCP client request.

## File service and dual MCP transport

Two additions beyond the original stdio-only, four-`kind` design,
motivated by an investigated bug (see
`docs/archive/2026-08-13-look-at-image-hang.md`): a large base64 image
argument to `look_at_image` stalled the *model's own response stream*
mid-generation, well before the request reached MCP dispatch at all —
not a bug in this server, not in MCP's stdio framing, but real and
reproducible, and part of a wider pattern of similar bugs found across
unrelated MCP clients/servers. MCP also has no client-to-server
file-submission primitive analogous to Anthropic's own Files API
(`resources/read` is server-exposed, client-pulled — the mirror image of
what inline binary tool arguments would need).

**`FileService`** (own class, own port, default 8765): a plain-HTTP
up/download service — `PUT /files` (raw bytes in, `{"file_id":...}` out),
`GET /files/{id}` (raw bytes out) — using the JDK's built-in
`com.sun.net.httpserver.HttpServer`, zero extra deps. Files are staged
under a process-lifetime temp directory, tracked in an in-memory
`ConcurrentHashMap<String, Path>` — no persistence, no TTL, no config
knob for storage location; matches the rest of this catalog's "trusted
local config, keep it simple" stance. Binds `0.0.0.0` deliberately,
trusting the LAN the same way local LM Studio/Ollama backends already
are.

`ProcessInvoker` is the one invoker wired to it: a call's `file_id`
argument (if present) resolves through `Main.FILES.resolve(...)` to a
real path, exposed to the target template as `{file_path}`. A
`CatalogEntry.producesFile` boolean (process-kind only) makes
`ProcessInvoker` allocate a fresh `{output_path}` before running, then
register whatever landed there with `FileService` after the process
exits, appending `file_id: <uuid>` to the returned text. See
`identify_image`/`convert_image` in `tools.json` for a worked example
(ImageMagick — the "one machine on the team has this tool installed,
everyone else uses it via the catalog" scenario this project targets).

**`HttpMcpTransport`** (own class, own port, default 8764): Streamable
HTTP per the MCP spec's simple branch only — `POST /mcp`, one JSON-RPC
message per request, `application/json` response with one message back;
no SSE, no `Mcp-Session-Id`, both spec-optional and unneeded here (no
server-initiated messages, no per-client state). Required extracting
`McpServer`'s per-message handling into a transport-agnostic
`handleLine(String) -> String` (was previously `handleLine(String)`
writing directly to a `PrintStream`, stdio-only) and `JsonRpc` building
response strings (`responseString`) instead of writing them
(`sendResponse` is gone). `run(BufferedReader)` — the stdio loop — now
just calls `handleLine` and writes the result itself; behavior
unchanged from the caller's perspective. Exists so a machine can run the
catalog as an always-on, systemd-managed service multiple simultaneous
clients can reach, rather than only as a subprocess spawned per client
session (`ssh host java -jar ...`, one process per session, which stdio
alone requires) — see `MANUAL.md` for the systemd unit shape. Both
transports run in the same process, over the same `McpServer`/tool
roster; stdio is unchanged and still works exactly as before for anyone
still using the per-session spawn pattern.
