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

`CatalogTool` itself holds no behavior — it only picks the `Invoker` by
`kind` and reports exceptions as MCP tool errors. The catalog is trusted
local configuration (reflection-by-string and shelling out from JSON are
normally avoided in this codebase's style; here that's the entire point,
not an oversight), not something derived from an untrusted MCP client
request.
