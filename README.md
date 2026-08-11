# mcp-service-catalog

A hand-rolled [MCP](https://modelcontextprotocol.io) (Model Context
Protocol) server whose entire tool roster is decided by a JSON catalog
(`tools.json`) read at startup — not by compiled-in tool classes. Point any
MCP client at this one server, approve it once under one stable name, and
it hands back whatever tools the catalog currently lists: existing Java
methods, CLI commands, or HTTP APIs (local LLM backends like LM Studio or
Ollama included), without the client ever needing its own config touched
or re-approved when the catalog changes.

No SDK dependency: implements the wire protocol directly — newline-delimited
JSON-RPC 2.0 over stdin/stdout, the `initialize`/`initialized` handshake,
`tools/list`, `tools/call`. The official Java MCP SDK pulls in Project
Reactor for a stdio JSON-RPC use case that doesn't need reactive-streams
plumbing; this avoids that dependency entirely.

## Why a catalog instead of tool classes

MCP clients approve servers by name, once. If the tool roster is hardcoded,
adding or changing a tool means shipping new code and getting the client to
notice. If the roster is data — a JSON file read at startup — then changing
what the server offers is a config edit, not a release: no client-side
re-approval, no `.mcp.json` diff to review. One name can front an arbitrary,
swappable set of backends, decided entirely by whoever controls
`tools.json` on the machine actually running the server.

## Why this fits a dev team's DTAP workflow

This shape maps directly onto a concern most teams rolling out AI-assisted
development already have: **the set of tools an AI assistant should be
allowed to touch is different in Dev, Test, and Acceptance — and that
difference is exactly a `tools.json` diff, not a re-plumbing of every
developer's client config.**

Concretely, in the DTA phases of DTAP:

- **Dev**: a developer's local catalog can be wide open — local build/lint
  commands, scratch HTTP calls to a locally-running service, a local LLM
  for cheap/casual tasks. Nobody outside that machine is affected by how
  permissive it is.
- **Test**: the catalog for a shared test environment can be scoped down to
  exactly the commands a CI/QA process needs the assistant to run —
  triggering test suites, querying a test database, hitting a staging API —
  without needing to touch the actual MCP client setup on every machine
  that connects to it.
- **Acceptance**: same idea, tightened further — an acceptance environment's
  catalog can expose read-only or reporting-style tools only, so an AI
  assistant helping with UAT can query state but can't accidentally mutate
  it.

Because every environment's server is approved under the *same stable
name* by whichever MCP client connects to it (see below), a team can stand
up one server per environment, each backed by an environment-appropriate
`tools.json`, and never need developers to re-approve or reconfigure their
client when they move between them or when the catalog for an environment
changes. The catalog file becomes the actual unit of governance — reviewable,
diffable, and owned by whoever owns that environment — instead of scattered
per-developer client configuration nobody can audit centrally.

## Catalog format

Each entry in `tools.json` has a `kind` that picks which invoker serves it:

- **`method`** — target is `fully.qualified.ClassName#methodName`, a public
  static `String methodName(Map<String,Object>)`, called via reflection.
- **`process`** — target is a JSON array of argv elements, each run through
  `{argName}` template substitution, executed via `ProcessBuilder`.
  stdout+stderr are captured as the result.
- **`http`** — target is either a bare URL template (GET, no body) or an
  object `{"url": ..., "method": "POST", "body": {...template...}}` whose
  body's string leaves are template-filled recursively — this is what makes
  it a real gateway rather than a toy: a single `http` entry can proxy an
  MCP tool call straight through to any local or remote HTTP API, JSON
  request body included. `tools.json` ships two examples hitting local
  LM Studio and Ollama chat-completions endpoints.

See `tools.json` in this repo for working examples of all three kinds, and
`CLAUDE.md` for the implementation notes (which class does what).

## Build & run

```bash
mvn package
java -jar target/mcp-service-catalog-1.0-jar-with-dependencies.jar [path-to-tools.json]
```

With no argument, it looks for `tools.json` next to the working directory.
Wire it into an MCP client (e.g. Claude Code's `.mcp.json`) as a `stdio`
server pointed at that command.

## What this actually is, security-wise

Be clear-eyed about this before running it anywhere beyond your own
machine: a catalog entry can run an arbitrary local process or make an
arbitrary HTTP call, controlled entirely by whoever can write `tools.json`.
That is not a new capability — anyone who can already edit a file this
process reads at startup already has equivalent-or-greater local access —
but it is real, and it's the entire point, not an oversight. This is
trusted local configuration, the same category as a shell alias or a
`Makefile`, not something derived from or reachable by an untrusted MCP
client request.

Two things worth knowing if you're deciding whether/how to run this:

1. **`tools.json` is the whole trust boundary.** Whoever can write that
   file controls what the server does. Protect it like you'd protect any
   other file that can execute commands on your behalf (script, cron
   entry, `.bashrc`).
2. **MCP client approval is typically name-keyed, not content-keyed.**
   Most MCP clients (Claude Code included) approve a server once by its
   configured name/command, and don't re-prompt if what's behind that name
   changes later. For this server that's a deliberate feature — swap
   `tools.json` and the approved connection's actual behavior changes with
   it, no re-approval needed. It also means: don't point a client at a
   `tools.json` (or a machine) you don't fully trust, the same way you
   wouldn't source-run a script from someone you don't fully trust.

None of this is specific to this project — it's the standard shape of "a
local process that executes commands and makes network calls on your
behalf," the same category as any local dev tool, build script, or shell
config. Treat it accordingly: fine on a machine and for a user who already
has that level of trust in the process; not something to expose to an
untrusted network or an unvetted `tools.json`.

## CI / Releases

Every push builds the jar (`.github/workflows/build.yml`) and uploads it as
a build artifact. Pushing a tag matching `v*.*.*` (e.g. `v1.0.0`)
additionally builds and publishes a GitHub Release with the fat jar
attached (`.github/workflows/release.yml`).

## Dependencies

Just Jackson (`jackson-core`/`-databind`/`-annotations`), used for JSON-RPC
message parsing and catalog loading. No Spring, no reflection-magic
frameworks beyond the deliberate reflection this project's whole point
requires, no MCP SDK.

## Status

v1.0.0 — initial public release. Evolved from a toy learn-the-wire-protocol
MCP server into this catalog-driven gateway.
