# Agent Client Protocol Web Gateway

## Goal

A web interface to a server running an Agent Client Protocol server

## Background

The Agent Client Protocol was created to provide IDEs (primarily Zed & JetBrains) with rich integrations to CLI agents.
https://agentclientprotocol.com/

## UX

- Users start the ACP Web Gateway on their machine in the context of a project
- When users launch the gateway, they specify which agent from the registry (https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) to use via `--agent <id>` CLI flag
- Alternatively, users can specify a custom agent command not in the registry via `--agent-command <command>` (e.g. `--agent-command "kiro-cli acp"`). The command string is split on whitespace into the executable and its arguments. Since this agent isn't from the registry, there is no metadata (icon, display name, description, version). The gateway uses the executable name as a fallback display name and shows a generic icon. The `--agent` and `--agent-command` flags are mutually exclusive.
- Users can choose to run in **local mode** (single session, default) or **proxy mode** (multi-session, URLs include session UUID)
- In proxy mode the ACP Web Gateway runs somewhere else and the user provides the base URL. Initialization creates a UUID for the session. The user can then interact with that session using the base URL / UUID
- Users perform normal AI code assistant interactions via the web gateway:
  - Text prompts with Markdown rendering (headings, lists, code blocks, tables, links)
  - File attachments via file picker, drag-drop, or paste (images sent as PNG, others as resources)
  - PNG screenshots of the chat via SVG foreignObject capture
  - Permission approvals/denials for agent-requested operations
  - Tool call visibility with collapsible blocks showing status (running/done/failed) and results
  - Diff rendering with color-coded +/- lines
  - Agent thought/reasoning display (separate styled bubbles)
  - Real-time streaming of agent responses with incremental DOM updates
- When a user joins a session they see the history (kept in memory)
- Multiple browser connections can view the same session simultaneously
- Web UI is desktop & mobile friendly with dark theme, smooth scrolling, and auto-collapse of older messages
- Debug mode (`--debug`) enables a Diagnose button and `browser://` virtual file reads for agent self-diagnosis
- Dev mode (`--dev`) enables a Reload button for hot-reload during development

## Architecture

- The ACP Web Gateway spawns the ACP server as a subprocess, communicates via stdio transport
- The gateway contains the compiled Wasm artifacts for interactive web aspects
- **Server-driven HTML**: the server renders all UI fragments using typed DSLs (kotlinx.html + kotlin-css) and sends them over WebSocket as `HtmlUpdate(target, swap, html)` messages. The Wasm client is a thin patcher that applies HTML updates to the DOM via idiomorph.

```
Server (Ktor + kotlinx.html + kotlin-css):
  /styles.css endpoint -> shared appStylesheet()
  Page template -> <link> to /styles.css + idiomorph inline script
  WebSocket -> sends HtmlUpdate(target, swap, html) using shared fragment builders

Shared (commonMain: JVM + WasmJS):
  Styles.kt -> kotlin-css CssBuilder, CSS class name constants, RuleSet compositions
  Fragments.kt -> kotlinx.html fragment builders (message bubbles, tool blocks, permissions, etc.)
  Message.kt -> WsMessage sealed class (HtmlUpdate for server->client, structured types for client->server)

Client (thin Wasm + kotlin-browser + idiomorph):
  WebSocket receive -> parse HtmlUpdate -> idiomorph.morph(target, html) / innerHTML / insertAdjacentHTML
  Form/file/scroll/timer -> kotlin-browser typed DOM + @JsFun bridges
  Screenshot -> SVG foreignObject (hand-rolled, typed)
  Debug -> console capture, browser:// state collection
```

### Session Modes

- **Local mode**: Single session auto-created at startup. Root `/` serves chat page, `/ws` is WebSocket.
- **Proxy mode**: Sessions created on demand. URLs are `/s/{sessionId}` and `/s/{sessionId}/ws`.

### Rendering Pipeline

1. Agent emits events (text chunks, tool calls, thoughts) via ACP stdio protocol
2. Server accumulates content, renders Markdown (commonmark), builds HTML fragments (Fragments.kt)
3. Server sends `HtmlUpdate` messages with swap mode (Morph, BeforeEnd, InnerHTML, Show, Hide)
4. Client applies updates via idiomorph DOM diffing, preserving scroll position and form state
5. Auto-collapse triggers on older message blocks to save vertical space

### Permission Flow

1. Agent requests permissions via ACP `ClientSessionOperations.requestPermissions()`
2. Server pre-renders permission dialog HTML, broadcasts Show + HtmlUpdate
3. Client uses event delegation on `data-tool-call-id`/`data-option-id` attributes
4. User clicks allow/deny, client sends `PermissionResponse` back via WebSocket
5. Server resolves `CompletableDeferred`, agent continues

### Reconnect Handling (Proxy/Relay Mode)

In proxy mode the system has two WebSocket connections: **CLI ↔ Server** and **Server ↔ Browser**. Either connection can drop (network interruption, server restart, laptop sleep, etc.). Both must reconnect cleanly without losing session state.

#### Design Principles

- **Session state lives in the CLI** — the CLI holds the agent process and all session state (history, tool calls, pending permissions). The server in proxy mode is stateless relay. This means reconnection is always safe: the CLI re-establishes its relay WebSocket and the server picks up where it left off.
- **Sequence-based catch-up** — each outbound message has a monotonic sequence number. On reconnect, the client sends its last-seen sequence number. The server (or CLI, for the relay link) replays any messages after that sequence from its turn buffer, avoiding duplicate or missed messages.
- **No user action required** — reconnection is automatic with exponential backoff. The UI shows a transient "Reconnecting..." indicator but requires no user intervention.

#### CLI ↔ Server (Relay WebSocket)

1. **CLI connects** to `wss://<gateway>/s/{sessionId}/ws/relay` with the session UUID it generated at startup.
2. **On disconnect**, the CLI retries with exponential backoff (1s, 2s, 4s, ... capped at 30s). The agent subprocess continues running — prompts in progress keep executing.
3. **On reconnect**, the CLI sends a `Reconnect` message with `lastSeq` (the last sequence number it forwarded to the server). The server knows this is a resumption, not a new session.
4. **Server replays** any buffered messages from browser clients that arrived after `lastSeq` (e.g. a `PermissionResponse` the user clicked while the CLI was disconnected).
5. **CLI replays** any agent messages produced while disconnected (buffered in the turn buffer) back to the server for forwarding to connected browsers.
6. **If the server restarts**, the CLI detects the broken connection and reconnects. Since the server is stateless in proxy mode, no server-side state is lost — the CLI re-registers its session.

#### Server ↔ Browser (Kilua RPC / WebSocket)

1. **Browser connects** via Kilua RPC WebSocket to `/s/{sessionId}/ws`.
2. **On disconnect**, the browser retries with exponential backoff (1s, 2s, 4s, ... capped at 30s). The UI shows a "Reconnecting..." overlay.
3. **On reconnect**, the browser sends a `Reconnect` message with `lastSeq`. The server replays messages from the turn buffer since that sequence number.
4. **Pending permission dialogs** are re-sent on reconnect — the server tracks `activePermission` per session and includes it in the reconnect replay.
5. **If the agent is mid-turn**, the reconnecting browser receives the current accumulated state (streamed text so far, active tool calls) so the UI catches up to the current position.
6. **Multiple browsers** can connect to the same session. Each tracks its own `lastSeq`. Disconnection of one browser doesn't affect others.

#### Message Ordering Guarantees

- Messages within a single turn are strictly ordered by sequence number.
- On reconnect, the turn buffer provides exactly-once delivery for messages within the current turn. Completed turns are in the chat history and replayed from the session store, not the turn buffer.
- If `lastSeq` is older than the turn buffer's earliest entry (e.g. very long disconnection spanning multiple turns), the server falls back to sending full history from the session store followed by the current turn buffer.

### Browser Debugging (Debug Mode)

- `browser://console` — Last 50 console log entries (log/warn/error with timestamps)
- `browser://dom` — DOM state summary (message count, WebSocket state, viewport size, etc.)
- `browser://all` — Both combined
- Screenshot checkbox — Captures real PNG via SVG foreignObject
- Diagnose button — Cancels current task, collects browser state, re-sends as diagnostic prompt

## Technologies

- Kotlin ACP Client (https://github.com/agentclientprotocol/kotlin-sdk)
- Ktor Server (CIO engine)
- Kotlin JVM runtime (JVM 21)
- kotlin-css (typed CSS DSL, shared between server & client)
- kotlinx.html (typed HTML DSL, shared between server & client)
- kotlinx-browser (typed DOM access for Kotlin/WasmJS)
- idiomorph (lightweight DOM differ for applying HTML updates, ~3KB inlined)
- Kotlin WASM for browser logic (thin client)
- WebSockets (JSON-serialized WsMessage types)
- commonmark (server-side Markdown → HTML)
- java-diff-utils (unified diff rendering)
- Kotlin Power Assert for tests
- Playwright (E2E browser tests in Docker)
- Gradle build with kts definitions
- SVG foreignObject for browser screenshots (hand-rolled, no external libraries)

## Distribution

- GitHub Actions for automated releases
- Docker container on ghcr.io
- Binaries via Kotlin Native for Linux, Mac, Windows
- Jar on Maven Central

### Running via Docker

The published container `ghcr.io/jamesward/acp-web-gateway` can run locally with a project directory mounted read/write. The agent inside the container operates on the mounted directory as its working directory.

```bash
docker run -it --rm \
  -p 8080:8080 \
  -v "$(pwd):/project" \
  ghcr.io/jamesward/acp-web-gateway \
  --agent <agent-id>
```

- `-v "$(pwd):/project"` — Mounts the current directory into `/project` read/write. The server uses `/project` as the agent's working directory.
- `-p 8080:8080` — Exposes the gateway on `http://localhost:8080`.
- `--agent <agent-id>` — Specifies which registry agent to use. Can also use `--agent-command "command args"` for custom agents (the command must be available inside the container).
- The container must include the agent runtime (e.g. Node.js for npx-based agents, Python for uvx-based agents). The base image should bundle common runtimes or users can extend the image.
- Add `--debug` for debug mode (Diagnose button, `browser://` reads).

Example with a custom agent command:

```bash
docker run -it --rm \
  -p 8080:8080 \
  -v "$(pwd):/project" \
  ghcr.io/jamesward/acp-web-gateway \
  --agent-command "kiro-cli acp"
```

## acp2web CLI

Key Architecture: The CLI is similar to the web gateway server, it runs the agent, holds the state, but does not serve UI. The web gateway in this `proxy` mode is just a web server passthrough and web UI server which enables remote access to the agent. In this mode there are two websocket connections: acp2web <-> remote web gateway <-> user

- Users will start an `acp2web` CLI on their machine in their project directory
- The `acp2web` program initiates a connection to a remote ACP Web Gateway server
- The `acp2web` CLI will create a websocket connection to the remote gateway, which will in-turn proxy that to through to the user's browser
- Like the web gateway server, in local mode the CLI will create the agent session (potentially not until the user selects one in the web UI, if one wasn't specified)
- The `acp2web` program will remain running until the user ctrl-c exits it or terminates it some other way
- The command will have optional parameters for `--agent`, `--agent-command`, and `--gateway`
- `--agent-command` allows specifying a custom command not in the registry (e.g. `--agent-command "kiro-cli acp"`), same behavior as the server flag — no registry metadata available, executable name used as fallback display name
- `--agent` and `--agent-command` are mutually exclusive
- If neither `--agent` nor `--agent-command` is specified, the user will select their agent when they open the web gateway
- If `--gateway` is not specified, a default will be used (`http://localhost:8080` for now)
- The ACP Web Gateway must be running in proxy mode
- The `acp2web` program will create a UUID session id and use that to connect to the remote gateway

- The URL to open the web gateway will be outputted to the console (i.e. https://localhost:8080/s/12345678-1234-1234-1234-1234567890ab)
- The user can then open the URL in their browser to interact with the remote gateway

- For now the `acp2web` program will be a Jar
- In the future, if the dependencies support Kotlin/Native we will use that
- In the meantime we may explore creating binaries with GraalVM Native Image

- We will need a new Kotlin subproject named `cli`
- This project will need to reuse shared code and we will need to refactor the necessary code to enable this
- The CLI won't contain a server. It instead becomes a client to the remote gateway.
- Use https://ajalt.github.io/clikt/ for the CLI

## Kilua Wasm Migration (Client-Side)

Migrate the `web` module from hand-rolled `kotlin-browser` + `@JsFun` interop to [Kilua](https://kilua.dev/) (v0.0.32), a Compose Runtime-based Kotlin/Wasm framework. This eliminates ~25 `@JsFun` declarations and replaces manual DOM/event wiring with typed composable APIs.

### Motivation

The current `web/App.kt` (~1135 lines) uses ~40 `@JsFun` declarations for basic DOM operations (getElementById, classList, innerHTML, event binding, timers, callbacks). Kotlin/Wasm requires `@JsFun` bridges because Wasm lambdas aren't JS functions, and `kotlin-browser` uses branded string types (`ElementId`, `ClassName`, `HtmlSource`) that can't be easily constructed from Wasm. This is error-prone and verbose. Kilua wraps all of this in typed Kotlin APIs.

### What Kilua Provides

- **No `@JsFun` for DOM ops** — element access, classList, innerHTML, insertAdjacentHTML all wrapped
- **Composable event handlers** — `onClick { }`, `onKeyDown { }`, `onChange { }`, `onInput { }` etc.
- **`rawHtml()` / `rawHtmlBlock()`** — insert server-rendered HTML into DOM, updates reactively on state change
- **Typed DOM access** — `component.element` gives the underlying `HTMLElement` directly
- **State management** — `mutableStateOf()` drives recomposition; replaces manual show/hide/enable/disable logic
- **Coroutine integration** — `LaunchedEffect`, `onClickLaunch { }` for async operations; replaces `setTimeout`/`setInterval` `@JsFun` wrappers

### Architecture Constraint

Our architecture is **server-driven**: the server renders all HTML fragments and sends `HtmlUpdate` messages. Kilua is designed for client-side component trees. The migration uses Kilua for the **application shell** (form, buttons, modals, state) while the **message stream** remains imperative (server HTML applied via idiomorph).

### Dependencies

```toml
# gradle/libs.versions.toml additions
[versions]
kilua = "0.0.32"
compose = "1.11.0-alpha02"

[libraries]
kilua = { module = "dev.kilua:kilua", version.ref = "kilua" }

[plugins]
compose = { id = "org.jetbrains.compose", version.ref = "compose" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kilua = { id = "dev.kilua", version.ref = "kilua" }
```

### Migration Phases

#### Phase 1: Project Setup

- Add Kilua + Compose dependencies to `web/build.gradle.kts`
- Add Compose compiler plugin and Kilua Gradle plugin
- Keep `shared` module unchanged (server still renders HTML fragments)
- Validate build compiles and WASM output works

#### Phase 2: Application Shell (~25 `@JsFun` eliminated)

Rewrite `App.kt` as a Kilua `Application`:

```kotlin
class App : Application() {
    override fun start() {
        root("root") {
            // Compose-managed shell
        }
    }
}
```

What moves to Kilua composables:

| Current (`@JsFun` + imperative) | Kilua replacement |
|---|---|
| `getEl`, `addCls`, `rmCls`, `hasCls`, `setCls` | `component.element`, Kilua class management |
| `setHtml`, `insertHtml` (shell elements) | `rawHtml()` / `rawHtmlBlock()` with state |
| `onSubmit`, `onKeyDown`, `onClick`, `onChange`, `onInput`, `onScroll` | Composable event handlers |
| `setTimeout`, `jsSetInterval`, `jsClearInterval`, `dateNow` | `LaunchedEffect` + `delay()`, `Clock.System.now()` |
| `wrapStringCallback`, `wrapTwoStringCallback`, `wrapDropCallback` | Direct lambda use in Kilua event system |
| `agentWorking`, `reloading`, `switchingAgent` (global vars) | `mutableStateOf()` driving recomposition |
| Manual show/hide via `addCls(el, "hidden")` | Compose `if (visible) { ... }` conditionals |
| `setupForm()` imperative init | Composable `form { }` with event handlers |
| `setInputEnabled()` manual DOM mutation | `disabled = !enabled` via Compose state |
| Status timer (`jsSetInterval` + manual DOM update) | `LaunchedEffect` coroutine with `delay(1000)` |
| Autocomplete dropdown (manual HTML building) | Compose state-driven list rendering |

#### Phase 3: Message Stream (keep idiomorph, ~15 `@JsFun` remain)

The messages container still receives server-rendered HTML via `HtmlUpdate`. Idiomorph applies diffs. This stays imperative — Kilua's `rawHtml()` uses `innerHTML` (no morphing). Access the messages element via Kilua's `element` property and call idiomorph directly.

`@JsFun` declarations that **remain** (no Kilua equivalent):

- `morphElement` — idiomorph interop
- `autoCollapseOlderBlocks` / `setupCollapseClickHandler` — complex DOM traversal
- `captureScreenshot` — SVG foreignObject screenshot
- `installConsoleCapture` / `getConsoleLogs` / `getDomState` / `inspectElements` — debug tooling
- `startAudioRecording` / `stopAudioRecording` — MediaRecorder API
- `readFileAt` / `readDtFileAt` — FileReader API for attachments
- `onDrop` / `onPasteFiles` — drag-drop/paste with DataTransfer
- `postRequest` / `pollUntilHealthy` / `postJsonRequest` — fetch API

#### Phase 4: WebSocket

Two options:
1. **Direct `web.sockets.WebSocket`** with Kilua's typed event system (no `@JsFun` wrappers needed)
2. **kilua-rpc `Socket`** class — coroutine-based `connect()`, `send()`, `receive()` with auto-retry

WebSocket connection state becomes `mutableStateOf()`, driving UI updates on connect/disconnect.

#### Phase 5: Server Template Update

- Update `Pages.kt` — Kilua needs a `<div id="root">` mount point
- Kilua generates its own JS bootstrap (replaces current WASM script tags)
- Keep idiomorph inlined (still needed for message stream)
- Update browser integration tests for new DOM structure

### Risks

1. **Compose Multiplatform alpha** — Kilua requires `compose 1.11.0-alpha02`. Pre-release dependency.
2. **Idiomorph + Compose DOM conflict** — Compose runtime tracks DOM nodes. Idiomorph mutates DOM behind Compose's back. The messages container must be a "Compose escape hatch" where Kilua hands off to imperative code. Needs spike validation.
3. **Bundle size** — Compose runtime adds weight to the WASM binary. Current client is very thin (~50KB).
4. **Build toolchain** — Adds Compose compiler plugin, Kilua Gradle plugin. May need vite-kotlin plugin depending on dev workflow.

### Validation Approach

Start with a spike branch:
1. Set up Kilua in `web` module
2. Mount a minimal composable that includes a `rawHtmlBlock` for the messages container
3. Verify idiomorph still works within the Kilua-managed tree
4. If DOM ownership conflict is manageable, proceed with full migration
5. If not, consider lighter alternatives (extract `@JsFun` helpers into utility, wait for kotlin-browser Wasm improvements)

## Future

- Global config file for default agent selection
- Persistent session storage (beyond in-memory)
- Code syntax highlighting in rendered blocks
