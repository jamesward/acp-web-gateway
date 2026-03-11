# Agent Client Protocol Web Gateway

## Goal

A web interface to a server running an Agent Client Protocol server

## Background

The Agent Client Protocol was created to provide IDEs (primarily Zed & JetBrains) with rich integrations to CLI agents.
https://agentclientprotocol.com/

## UX

- Users start the ACP Web Gateway on their machine in the context of a project
- When users launch the gateway, they specify which agent from the registry (https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) to use via `--agent <id>` CLI flag
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

## acp2web CLI

Key Architecture: The CLI is similar to the web gateway server, it runs the agent, holds the state, but does not serve UI. The web gateway in this `proxy` mode is just a web server passthrough and web UI server which enables remote access to the agent. In this mode there are two websocket connections: acp2web <-> remote web gateway <-> user

- Users will start an `acp2web` CLI on their machine in their project directory
- The `acp2web` program initiates a connection to a remote ACP Web Gateway server
- The `acp2web` CLI will create a websocket connection to the remote gateway, which will in-turn proxy that to through to the user's browser
- Like the web gateway server, in local mode the CLI will create the agent session (potentially not until the user selects one in the web UI, if one wasn't specified)
- The `acp2web` program will remain running until the user ctrl-c exits it or terminates it some other way
- The command will have optional parameters for `--agent` and `--gateway`
- If `--agent` is not specified, the user will select their agent when they open the web gateway
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

## Future

- Global config file for default agent selection
- Persistent session storage (beyond in-memory)
- Code syntax highlighting in rendered blocks
- Multi-agent support (switch agents within a session)
