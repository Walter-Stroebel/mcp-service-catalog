# MANUAL

The practical reference: deploying a catalog server, writing `tools.json`
entries, using the file service, wiring up a client. See `README.md` for
the pitch (what this is and why), and `CLAUDE.md` for implementation
notes aimed at whoever (human or AI) is editing this codebase itself.

## The idea in one paragraph

Any machine — a spare box, a real team server, or a throwaway
container/VM standing in for one — becomes an MCP server just by dropping
a `tools.json` next to this jar. Whatever that machine happens to have
installed becomes a tool every MCP client on the network can call,
without writing a line of bespoke MCP server code. Each machine is a
standalone, individually-configured island: there is no discovery layer,
by design. A user still has to add each server they want to their own
client's config (e.g. `.mcp.json`) by hand, the same way they'd choose to
trust any other local process.

## Running a server

```bash
mvn package
java -jar target/mcp-service-catalog-1.0-jar-with-dependencies.jar \
    [path-to-tools.json] [file-service-port] [mcp-http-port]
```

All three arguments are optional:

- `tools.json` path — defaults to `tools.json` next to the working
  directory.
- File service port — defaults to `8765`.
- MCP-over-HTTP port — defaults to `8764`.

This starts three things in the same process:

1. **The stdio MCP transport** — reads JSON-RPC from stdin, writes to
   stdout. This is what most MCP clients (Claude Code included) expect
   when they spawn a server as a subprocess (`command`/`args` in
   `.mcp.json`). Blocks the main thread; if stdin is `/dev/null` (e.g.
   under systemd) it hits EOF immediately and this transport is
   effectively inert for that process, which is fine — the other two run
   on their own threads regardless.
2. **The Streamable HTTP MCP transport** (`HttpMcpTransport`) —
   `POST /mcp` on the given port, one JSON-RPC message per request, one
   JSON-RPC response back. This is what lets a machine be a genuine
   standing network service multiple simultaneous clients can point at,
   rather than one subprocess per client session.
3. **The file service** (`FileService`) — see below.

### Two ways to run it: per-session vs. always-on

- **Per-session (stdio only)**: an MCP client spawns
  `ssh some-machine java -jar mcp-service-catalog.jar tools.json` itself,
  gets a private process for the life of that session. No install step
  on the server side beyond having the jar and `tools.json` present. This
  is how the original single-machine (legion ↔ predator) setup worked,
  and still works unchanged.
- **Always-on (systemd-managed)**: install it as a long-lived service so
  the machine is reachable over HTTP by anyone who knows its address,
  independent of any one client's session lifetime. Use a standard
  root-level system unit (`/etc/systemd/system/mcp-service-catalog.service`)
  rather than a `systemctl --user` unit — a user unit is invisible to a
  plain `systemctl status`/`journalctl` and just fragments where service
  state lives on the box for no real benefit on a single-user machine.
  Example unit:

  ```ini
  [Unit]
  Description=Generic MCP service catalog (Streamable HTTP + file service; stdio still available via ssh)
  After=network.target

  [Service]
  Type=simple
  WorkingDirectory=/path/to/mcp-service-catalog
  ExecStart=/usr/bin/java -jar /path/to/mcp-service-catalog.jar /path/to/tools.json 8765 8764
  Restart=on-failure
  RestartSec=5
  StandardInput=null

  [Install]
  WantedBy=multi-user.target
  ```

  ```bash
  sudo systemctl daemon-reload
  sudo systemctl enable --now mcp-service-catalog.service
  ```

  `StandardInput=null` documents the intent explicitly: this instance
  serves HTTP/file traffic, not stdio. The two modes aren't mutually
  exclusive — nothing stops someone from *also* `ssh`-spawning a stdio
  instance against the same `tools.json` while the systemd instance runs;
  they're independent processes each with their own `FileService`
  instance (files uploaded to one are not visible to the other — see
  "File service" below).

## Wiring up a client

For the stdio transport, add an entry to the client's MCP config (e.g.
Claude Code's `.mcp.json`):

```json
{
  "mcpServers": {
    "my-catalog": {
      "command": "ssh",
      "args": ["some-machine", "java", "-jar",
               "/path/to/mcp-service-catalog.jar",
               "/path/to/tools.json"]
    }
  }
}
```

For the Streamable HTTP transport, point the client at
`http://some-machine:8764/mcp` per whatever HTTP-MCP config shape that
client supports.

Approval is name-keyed in most clients (Claude Code included): approve
`my-catalog` once, and it stays approved even if `tools.json` on the
server changes later. That's deliberate — see README's security section.

## Writing `tools.json` entries

Each entry needs `name`, `description`, `inputSchema` (standard JSON
Schema), `kind`, and `target` (shape depends on `kind`).

### `kind: "method"`

`target` is `"fully.qualified.ClassName#methodName"` — a public static
`String methodName(Map<String,Object> arguments)`, called via
reflection.

```json
{
  "name": "add",
  "description": "Adds two integers and returns the sum.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "a": { "type": "integer" },
      "b": { "type": "integer" }
    },
    "required": ["a", "b"]
  },
  "kind": "method",
  "target": "nl.infcomtec.mcpservicecatalog.DemoBackend#add"
}
```

Use this for logic that doesn't fit cleanly as a shell command or an
HTTP call — anything you'd otherwise write as a small Java method.

### `kind: "process"`

`target` is a JSON array of argv elements, each run through `{argName}`
template substitution, executed via `ProcessBuilder`. stdout+stderr are
captured and returned as the result.

```json
{
  "name": "list_dir",
  "description": "Lists files in a directory.",
  "inputSchema": {
    "type": "object",
    "properties": { "dir": { "type": "string" } },
    "required": ["dir"]
  },
  "kind": "process",
  "target": ["ls", "-la", "{dir}"]
}
```

Two special arguments `ProcessInvoker` recognizes beyond plain
templating — see "File service" below for what they connect to:

- **`file_id`** — if present, resolved to a real on-disk path exposed to
  the template as `{file_path}`.
- **`producesFile: true`** on the catalog entry (not an argument — a
  field on the entry itself) — allocates a fresh output path exposed as
  `{output_path}`; whatever the process writes there is registered after
  it exits, and the resulting `file_id` is appended to the tool's
  returned text as `file_id: <uuid>`. Pair with an `output_ext` argument
  if the command needs a file extension to infer format.

```json
{
  "name": "convert_image",
  "description": "Runs ImageMagick convert on a previously uploaded file.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "file_id": { "type": "string" },
      "convert_args": { "type": "string" },
      "output_ext": { "type": "string" }
    },
    "required": ["file_id", "convert_args", "output_ext"]
  },
  "kind": "process",
  "producesFile": true,
  "target": ["sh", "-c", "convert '{file_path}' {convert_args} '{output_path}'"]
}
```

### `kind: "http"`

`target` is either a bare URL template string (GET, no body) or an
object `{"url": ..., "method": "POST", "body": {...template...}}` whose
body's string leaves are template-filled recursively — needed for JSON
request bodies like chat-completions calls.

```json
{
  "name": "ask_gemma",
  "description": "Sends a prompt to a local LLM and returns its raw JSON response.",
  "inputSchema": {
    "type": "object",
    "properties": { "prompt": { "type": "string" } },
    "required": ["prompt"]
  },
  "kind": "http",
  "target": {
    "url": "http://localhost:1234/v1/chat/completions",
    "method": "POST",
    "body": {
      "model": "google/gemma-4-e4b",
      "messages": [{ "role": "user", "content": "{prompt}" }]
    }
  }
}
```

**Local LLM backend choice.** LM Studio and Ollama both work as `http`
targets (as above), but each is an app-managed black box with its own
model store, its own idea of when to load/unload weights, and its own
CLI/GUI layer between you and the actual inference server. Building
[llama.cpp](https://github.com/ggml-org/llama.cpp) directly and running
`llama-server` as a plain systemd unit is a leaner alternative worth
listing as an option: no vendor app in the loop, the model is pinned
loaded (no per-request cold-start), and the OpenAI-compatible
`/v1/chat/completions` endpoint it exposes is a drop-in replacement for
either — same `kind: "http"` shape as above, just point `url` at
`llama-server`'s port instead of LM Studio's/Ollama's. Gemma-4's
chat template has a baked-in chain-of-thought toggle: pass
`--reasoning off` on `llama-server`'s command line (not
`--reasoning-budget 0`, which relabels the output field without actually
suppressing the reasoning tokens) to get direct answers instead of a
verbose `<|channel>thought...` preamble on every call. Note VRAM is
still a hard constraint either way — a small GPU may not fit two models
loaded simultaneously regardless of which backend serves them.

**Keep string arguments small.** A model generating a large string as
tool-call input (a multi-KB blob) can stall its own response stream —
see `docs/archive/2026-08-13-look-at-image-hang.md` for the full
investigation. This is a client/model-side limitation, not something
this server can work around by itself. For anything beyond a few KB of
data (images, files, large text), use the file service instead of an
inline string argument.

### `kind: "launch"`

`target` is a JSON array of argv elements (same templating as
`process`), started via `ProcessBuilder` and left running — the call
returns immediately with the launched PID, without waiting for the
process to exit or capturing its output. For detached, long-running
effects (opening a GUI window) rather than a captured result.

```json
{
  "name": "view_image",
  "description": "Opens an image file in a viewer on this machine's desktop.",
  "inputSchema": {
    "type": "object",
    "properties": { "file": { "type": "string" } },
    "required": ["file"]
  },
  "kind": "launch",
  "target": ["/path/to/viewer.sh", "{file}"]
}
```

## File service

A plain-HTTP file up/download service (`FileService`), own port
(default 8765), running alongside the MCP transports — not part of MCP
itself. Exists because MCP tool-call arguments and results are JSON, and
inlining any real amount of binary data into JSON (as base64) is exactly
the pattern that causes the client/model-side stream stall mentioned
above, plus several independent, unrelated bugs found across the wider
MCP ecosystem (see the archived investigation).

```
PUT  /files        body = raw bytes,  response = {"file_id":"..."}
GET  /files/{id}   response = raw bytes of a previously uploaded file
```

The pattern mirrors Anthropic's own Files API for the Messages API:
upload once, get a short opaque handle, pass the handle around instead
of the bytes. A model only ever has to emit a short `file_id` string as
tool-call input — never a multi-KB blob.

Typical flow for a `process`-kind tool that needs file input/output:

1. Client `PUT`s bytes to `http://server:8765/files`, gets back
   `file_id`.
2. Client calls the MCP tool with `{"file_id": "..."}` (plus whatever
   else the tool needs).
3. `ProcessInvoker` resolves `file_id` to a real path, exposed to the
   command template as `{file_path}`.
4. If the tool is `producesFile: true`, its output gets registered under
   a new `file_id`, returned in the tool's result text.
5. Client `GET`s `http://server:8765/files/{new_file_id}` to download
   the result.

**Storage is process-lifetime, in-memory-tracked, no persistence.**
Files are written to a temp directory (`Files.createTempDirectory`) and
tracked in a `ConcurrentHashMap<String, Path>`; nothing survives a
restart, there's no TTL sweep, and there's no size cap. This matches the
project's general "trusted local config, keep it simple" stance — fine
for personal/team-scale use, not something to expose without further
hardening if usage patterns change.

**Known gap: old temp directories aren't cleaned up on restart.** Each
process start allocates a *new* temp directory; a *previous* run's
directory (and everything uploaded to it) is simply abandoned on disk,
not deleted — `FileService` has no way to know about or clean up a
directory from a process that's already gone. Confirmed in practice: a
heavy stress-test session (see
`docs/case-studies/2026-08-13-voynich-vision-stress-test.md`) left
4.5GB behind after a `systemctl restart`, requiring a manual `rm -rf` of
the old directory. Fine to ignore for occasional personal use; worth a
periodic manual sweep (`ls -la $TMPDIR/mcp-catalog-files-*`, delete
anything not matching the current process) or a real fix (delete-on-
startup, a systemd `ExecStartPre`, or an actual TTL) if this sees
heavier or longer-running use.

**No authentication beyond LAN-level trust.** The file service (like the
HTTP MCP transport) binds `0.0.0.0` — anyone who can reach that port can
upload/download any tracked `file_id`. This assumes the LAN itself is
the trust boundary, the same assumption already made for e.g. a local
LM Studio/Ollama backend on the same host. Don't expose these ports
beyond a network you actually trust.

## Security stance

See README's "What this actually is, security-wise" section — nothing
added by the file service or HTTP transport changes that stance, it just
extends it to two more ports on the same trust boundary. `tools.json` is
still the entire trust boundary for what a server will *do*; the file
service and HTTP transport are additional *reachability* surface on an
already-trusted machine, not new privilege.
