# Agent Client Protocol Web Gateway

## Goal

A web interface to AI agents via the [Agent Client Protocol](https://agentclientprotocol.com/).

## Background

The Agent Client Protocol was created to provide IDEs (primarily Zed & JetBrains) with rich integrations to CLI agents.
https://agentclientprotocol.com/

## UX

- Users start the ACP Web Gateway on their machine in the context of a project
- When users launch the gateway, they specify which agent from the registry (https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) to use via `--agent <id>` CLI flag
- Alternatively, users can specify a custom agent command not in the registry via `--agent-command <command>` (e.g. `--agent-command "kiro-cli acp"`). The command string is split on whitespace into the executable and its arguments. Since this agent isn't from the registry, there is no metadata (icon, display name, description, version). The gateway uses the executable name as a fallback display name and shows a generic icon. The `--agent` and `--agent-command` flags are mutually exclusive.
- Users can choose to run in **local mode** (single session, default) or **proxy mode** (multi-session, `--proxy` flag)
- In proxy mode the ACP Web Gateway runs somewhere else and the user provides the base URL. Initialization creates a UUID for the session. The user can then interact with that session using the base URL / UUID
- Users perform normal AI code assistant interactions via the web gateway:
  - Text prompts with Markdown rendering (headings, lists, code blocks, tables, links)
  - File attachments via file picker, drag-drop, or paste (images sent as PNG, text files inlined into prompt, binary files as resources)
  - PNG screenshots of the chat via html2canvas
  - Permission approvals/denials for agent-requested operations
  - Tool call visibility with collapsible blocks showing status (running/done/failed) and results
  - Diff rendering with color-coded +/- lines (via java-diff-utils)
  - Agent thought/reasoning display (separate styled bubbles)
  - Real-time streaming of agent responses with incremental delta updates
  - Slash command autocomplete from agent-provided and internal command lists
  - Agent switching via dropdown (in proxy mode, from registry)
- When a user joins a session they see the history (kept in memory)
- Multiple browser connections can view the same session simultaneously
- Web UI is desktop & mobile friendly with dark theme, smooth scrolling, and auto-collapse of older messages
- Debug mode (`--debug`) enables a Diagnose button, Screenshot checkbox, Download Log button, and `browser://` virtual file reads for agent self-diagnosis
- Dev mode (`--dev`) enables a Reload button for hot-reload during development

## Architecture

- The ACP Web Gateway spawns the ACP agent as a subprocess, communicates via stdio transport using the ACP Kotlin SDK
- The gateway contains the compiled Wasm artifacts for the browser client
- **Client-side rendering**: the server sends structured JSON messages (`WsMessage` types) over Kilua RPC WebSocket channels. The Kotlin/Wasm client renders all UI using Kilua composables with reactive state management.

```
Server (Ktor 3.x CIO):
  Pages.kt -> minimal HTML shell (<div id="root"> + <script> for Wasm bundle)
  Kilua RPC -> typed WebSocket channels sending WsMessage types
  WebSocketHandler.kt -> processes ACP events into WsMessage stream

Shared (commonMain: JVM + WasmJS):
  Message.kt -> WsMessage sealed class (AgentText, ToolCall, PermissionRequest, etc.)
  IChatService.kt -> Kilua RPC @RpcService interface
  WebSocketHandler.kt -> core handleChatChannels() function
  AgentProcessManager.kt -> agent subprocess lifecycle, GatewaySession state
  GatewayClientOperations.kt -> ACP ClientSessionOperations implementation
  Registry.kt -> agent registry fetch and command resolution

Client (Kotlin/WasmJS + Kilua composables):
  App.kt -> full Kilua Application with composable UI
  Styles.kt -> Kilua globalStyle() CSS definitions
  Kilua RPC -> typed WebSocket communication with server
  @JsFun bridges -> console capture, DOM state, scroll management, html2canvas, file I/O
```

### Session Modes

- **Local mode** (default): Single session auto-created at startup. Root `/` serves chat page. Kilua RPC handles WebSocket.
- **Proxy mode** (`--proxy`): Sessions created on demand via CLI connections. URLs are `/s/{sessionId}`. Three WebSocket routes:
  - `/s/{sessionId}/agent` — CLI backend relay WebSocket
  - `/s/{sessionId}/ws` — Browser raw WebSocket (relay)
  - `/s/{sessionId}` — Kilua RPC endpoint

### Rendering Pipeline

1. Agent emits events (text chunks, tool calls, thoughts) via ACP stdio protocol
2. Server processes events in `handleChatChannels()`, accumulates state, generates diffs (java-diff-utils)
3. Server sends structured `WsMessage` types (AgentText deltas, ToolCall status, AgentThought deltas) via Kilua RPC channels
4. Client receives messages and renders them using Kilua composables with `mutableStateOf()` reactive state
5. Auto-collapse triggers on older message blocks to save vertical space (`collapseBeforeIndex`)

### Permission Flow

1. Agent requests permissions via ACP `ClientSessionOperations.requestPermissions()`
2. `GatewayClientOperations` suspends on a `CompletableDeferred`, sends pending permission to a channel
3. `GatewaySession.startEventForwarding()` picks up the pending permission, broadcasts `WsMessage.PermissionRequest`
4. Client renders permission dialog as a Kilua composable with allow/deny buttons
5. User clicks a button, client sends `WsMessage.PermissionResponse` back via RPC channel
6. Server resolves `CompletableDeferred`, agent continues

### Reconnect Handling

Both local and proxy modes support automatic reconnection with no user action required.

#### Browser ↔ Server (Kilua RPC / WebSocket)

1. **Browser connects** via Kilua RPC WebSocket
2. **On disconnect**, the browser retries with exponential backoff (1s, 2s, 4s, ... capped at 30s). The UI shows a "Reconnecting..." overlay.
3. **On reconnect**, the browser sends a `ResumeFrom` message with `lastSeq` (the last sequence number it received). The server checks the turn buffer for messages since that sequence.
4. **Delta resume**: If the turn buffer has messages after `lastSeq`, only those are replayed (efficient catch-up within the current turn).
5. **Full replay**: If `lastSeq` is too old or zero, the server replays complete chat history from the session store, then current turn state (in-progress thoughts, text, tool calls).
6. **Pending permission dialogs** are re-sent on reconnect — the server tracks `activePermission` per session.
7. **Multiple browsers** can connect to the same session. Each tracks its own `lastSeq`. Disconnection of one browser doesn't affect others.

#### CLI ↔ Server (Proxy/Relay Mode)

**[PARTIALLY IMPLEMENTED]** The CLI connects to `wss://<gateway>/s/{sessionId}/agent` and relays messages between the local agent and the remote server. The server caches messages for browser clients. Basic reconnection works (CLI can reconnect and re-register), but full sequence-based delta catch-up for the CLI relay link is not yet implemented:

- **Implemented**: CLI connects and relays, server caches messages for new browser connections, agent switching mid-session, `switchInProgress` flag for CLI reconnect during agent changes.
- **Not yet implemented**: CLI-side `lastSeq` tracking, CLI-side exponential backoff retry loop, server-to-CLI message replay on reconnect.

#### Message Ordering Guarantees

- Messages within a single turn are strictly ordered by sequence number (monotonic `AtomicLong` counter in `GatewaySession`).
- On browser reconnect, the turn buffer provides exactly-once delivery for messages within the current turn. Completed turns are in the chat history and replayed from the session store, not the turn buffer.
- If `lastSeq` is older than the turn buffer's earliest entry (e.g. very long disconnection spanning multiple turns), the server falls back to sending full history from the session store followed by the current turn buffer.

### Client (Web Module)

The browser client is a Kotlin/WasmJS application built with the Kilua framework (Compose Runtime-based). It renders all UI client-side using composable functions with reactive state, communicating with the server via Kilua RPC typed WebSocket channels.

#### Application Structure

- `App.kt` — Single `Application` subclass containing all state, message handling, composable UI, and actions.
- `Styles.kt` — All CSS defined via Kilua `globalStyle()` composables, organized by UI section (header, messages, tools, permissions, input bar, markdown, etc.). Dark theme with GitHub-inspired color palette.
- Entry point: `startApplication(::App)` boots the Kilua framework, which mounts the composable tree into `<div id="root">`.

#### State Management

All UI state is held as `mutableStateOf()` properties on the `App` class. Key state groups:

- **Connection**: `connected`, `agentName`, `agentVersion`, `cwd`, `agentWorking`, `lastSeq` (for reconnect delta resume), `reconnectDelay` (exponential backoff 1s–30s).
- **Conversation**: `messages` (list of sealed `ChatMessage` variants: `User`, `Assistant`, `Thought`, `ToolBlock`, `Error`), plus in-progress accumulators `currentResponse`, `currentThought` (Triple of id, accumulated markdown, usage), and `currentToolCalls` (list of `ToolCallState`).
- **Permissions**: `permissionRequest` holds the current `PermissionRequest` message; cleared on response.
- **Agent selector**: `availableAgents`, `currentAgentId`, `showAgentSelector`, `switchingAgent` (shows a spinner modal during switch).
- **File attachments**: `pendingFiles` list, populated via file picker, drag-drop, or paste.
- **Debug/dev**: `debugMode` / `devMode` (read from `<body>` data attributes at startup), `screenshotEnabled`, `reloading`.
- **Autocomplete**: `availableCommands` (from server), `autocompleteFiltered`, `autocompleteSelectedIndex`.
- **Scroll**: `atBottom` (tracked via JS scroll listener polling), `collapseBeforeIndex` (older messages render collapsed).
- **Timer**: `elapsedSeconds` with a coroutine-based timer job shown on thought bubbles during agent work.

#### Composable UI Components

- **Header** — Agent icon (from registry), agent name, working directory, agent switch button (when multiple agents available), reload button (dev mode).
- **Agent selector overlay** — Modal listing available agents with icons, names, descriptions, and "current" badge. Clicking an agent sends `ChangeAgent` and shows a switching spinner modal.
- **Messages container** (`#messages`) — Scrollable list of `ChatMessage` variants:
  - `User` — Blue bubble with text and file attachment tags.
  - `Assistant` — Collapsible `<details>` card with markdown body rendered via `parseMarkdown()` (Kilua's marked wrapper). Shows usage stats in summary.
  - `Thought` — Collapsible card with yellow left border, italic text, elapsed timer shown during active thinking.
  - `ToolBlock` — Collapsible card summarizing tool calls (count, done/failed/running stats, active tool name). Each tool row shows status icon (checkmark/cross/circle), tool name, location, and expandable content rendered as markdown.
  - `Error` — Red-bordered message block.
- **In-progress turn** — Current thought, tool calls, and response render below completed messages with live streaming updates.
- **Scroll-to-bottom button** — Floating circular button appears when user scrolls up, auto-scroll on new content when at bottom.
- **Input bar** — File preview chips (removable), slash command autocomplete popup (arrow key / Tab / Escape navigation), textarea with Enter-to-send (Shift+Enter for newline), attach button (file picker), Send/Cancel/Diagnose buttons, Screenshot checkbox and Download Log button (debug mode).
- **Permission dialog** — Fixed overlay with title, description, and dynamically rendered option buttons from the agent's permission request.

#### JS Bridge Layer (`@JsFun`)

The Kotlin/Wasm client uses `@JsFun` annotated external functions to bridge to browser APIs:

- `setRpcUrlPrefix(prefix)` — Sets `globalThis.rpc_url_prefix` for Kilua RPC WebSocket routing (needed in proxy mode for session-scoped paths).
- `installConsoleCapture()` / `getConsoleLogs()` — Intercepts `console.log/warn/error`, buffers last 50 entries with timestamps and levels as JSON.
- `getDomState()` — Collects DOM summary: message count, viewport size, permission dialog visibility, page title, body classes, URL.
- `isMessagesAtBottom()` / `scrollMessagesToBottom()` / `installScrollListener()` / `readScrollAtBottom()` — Scroll position tracking and management for the messages container.
- `downloadTextFile(filename, content)` — Triggers a client-side file download via Blob URL.
- `pickFiles(multiple)` — Opens a native file picker dialog, returns a `Promise<FileList>`.

External npm module: `html2canvas` imported via `@JsModule` for PNG screenshot capture.

#### Message Protocol (Client Side)

The client connects via `getService<IChatService>().chat { sendChannel, receiveChannel -> ... }`. On connect it sends `ResumeFrom(lastSeq)` if reconnecting. Incoming `WsMessage` types are dispatched in `onMessage()`:

- `Connected` — Sets agent info, resets state on fresh connect (not delta resume).
- `AgentText` / `AgentThought` — Delta chunks accumulated by `msgId`/`thoughtId` into `currentResponse`/`currentThought`. History replays (ids starting with `history-`) replace rather than append.
- `ToolCall` — Upserts into `currentToolCalls` list by `toolCallId`.
- `TurnComplete` — Flushes in-progress accumulators into `messages` list, stops timer.
- `PermissionRequest` / `PermissionResponse` — Shows/hides permission dialog.
- `AvailableAgents` — Populates agent selector; auto-shows if no agent selected.
- `AvailableCommands` — Updates slash command autocomplete list.
- `BrowserStateRequest` — Collects browser state and sends `BrowserStateResponse` back (for debug mode `browser://` reads).
- `UserMessage` — Adds user message to history (from server replay).
- `Error` — Adds error message to conversation.

#### File Attachment Handling

Files can be attached via three input methods:
- **File picker** — `pickFiles()` JS bridge opens native dialog, returns `FileList`.
- **Drag and drop** — `setDropTarget` on textarea intercepts drop events.
- **Paste** — `ClipboardEvent` handler on textarea extracts pasted files.

All paths read files via `readFile()` which converts to base64 using `kotlin.io.encoding.Base64`. Files are sent as `FileAttachment` objects in the `Prompt` message. The server handles routing: images as `ContentBlock.Image`, text files inlined into the prompt, binary files as `ContentBlock.Resource`.

#### Chat Log Download

The Download Log button (debug mode) serializes the full conversation to a markdown file:
- User messages with file names
- Assistant responses with usage stats
- Thought blocks with usage stats
- Tool call blocks with status icons and content
- In-progress turn appended after a separator

Downloaded as `chat-log.md` via the `downloadTextFile` JS bridge.

### Browser Debugging (Debug Mode)

- `browser://console` — Last 50 console log entries (log/warn/error with timestamps)
- `browser://dom` — DOM state summary (message count, viewport size, permission dialog visibility, page title, body classes, URL)
- `browser://all` — Both combined as JSON
- Screenshot checkbox — Captures PNG via html2canvas (renders DOM to canvas, extracts base64 PNG)
- Download Log button — Downloads full chat log as text file
- Diagnose button — Cancels current task, collects browser state + session state (elapsed time, active tool calls, pending permissions, recent history), re-sends as diagnostic prompt

## Technologies

- Kotlin ACP Client (https://github.com/agentclientprotocol/kotlin-sdk)
- Ktor 3.x Server (CIO engine)
- Kotlin JVM 25 runtime
- Kilua (Compose Runtime-based Kotlin/Wasm framework for client UI)
- Kilua RPC (typed WebSocket channels between server and client)
- kotlinx.html (server-side page templates only, in Pages.kt)
- kotlinx-serialization-json (WsMessage serialization over WebSocket)
- html2canvas (client-side PNG screenshots via npm module)
- java-diff-utils (unified diff rendering for tool call content)
- Kotlin Power Assert for tests
- Playwright (E2E browser tests via Testcontainers in Docker)
- Gradle build with kts definitions
- Clikt (CLI argument parsing for acp2web)
- GraalVM Native Image (CLI native binary compilation)
- Jib (OCI container image building, no Dockerfile)

## Distribution

- GitHub Actions for automated releases (tag-triggered)
- Docker container on ghcr.io (base image: eclipse-temurin:25-jre + Node.js + uv)
- CLI binaries for macOS (arm64), Linux (amd64), Windows (amd64) as GraalVM native images (~58MB, no JVM required)

### Running via Docker

The published container `ghcr.io/jamesward/acp-web-gateway` defaults to **local mode**. The user can pass `--proxy` to start in proxy/relay mode instead.

```bash
# Local mode (default) — mount project directory, specify agent
docker run -it --rm \
  -p 8080:8080 \
  -v "$(pwd):/project" \
  ghcr.io/jamesward/acp-web-gateway \
  --agent <agent-id>

# Proxy mode — for use with acp2web CLI
docker run -it --rm \
  -p 8080:8080 \
  ghcr.io/jamesward/acp-web-gateway \
  --proxy
```

- `-v "$(pwd):/project"` — Mounts the current directory into `/project` read/write. The server uses `/project` as the agent's working directory.
- `-p 8080:8080` — Exposes the gateway on `http://localhost:8080`.
- `--agent <agent-id>` — Specifies which registry agent to use.
- `--proxy` — Starts in proxy mode for use with the acp2web CLI.
- The container includes Node.js (for npx-based agents) and uv (for uvx-based agents) via a custom base image built from `server/base-image/Dockerfile`.
- Add `--debug` for debug mode.

Custom agent command via `--agent-command`:

```bash
docker run -it --rm \
  -p 8080:8080 \
  -v "$(pwd):/project" \
  ghcr.io/jamesward/acp-web-gateway \
  --agent-command "kiro-cli acp"
```

## acp2web CLI

The CLI (`cli/` module) runs the ACP agent locally and connects to a remote ACP Web Gateway server running in proxy mode. The gateway serves as a web UI passthrough. In this mode there are two WebSocket connections: acp2web ↔ remote web gateway ↔ user's browser.

- Users start `acp2web` in their project directory
- The CLI creates a UUID session ID and connects to the remote gateway at `/s/{sessionId}/agent`
- The gateway URL is printed to the console for the user to open in their browser
- The CLI spawns the ACP agent subprocess locally and relays messages between the agent and the remote gateway
- The CLI supports `--agent <id>`, `--agent-command <command>` (mutually exclusive), and `--gateway <url>` (defaults to `https://www.acp2web.com`)
- If `--agent` is not specified, the user selects their agent in the browser UI; the CLI receives a `ChangeAgent` message and starts the chosen agent
- Agent switching mid-session is supported via `ChangeAgent` messages
- The CLI uses Clikt for argument parsing and Ktor WebSocket client for the relay connection
- Built as a GraalVM native image — single static binary per platform, no JVM required, instant startup
- Native image reachability metadata for Ktor CIO, Logback, and kotlinx-serialization in `cli/src/main/resources/META-INF/native-image/`
- The `org.graalvm.buildtools.native` Gradle plugin provides the `nativeCompile` task

**[NOT YET IMPLEMENTED]**:
- Exponential backoff reconnect on relay WebSocket disconnect
- Sequence-based delta catch-up on relay reconnect

## Future

- CLI relay reconnect with exponential backoff and sequence-based catch-up
- Global config file for default agent selection
- Persistent session storage (beyond in-memory)
- Code syntax highlighting in rendered blocks
- Audio recording support (MediaRecorder API)
- Additional functionality
  - Skills Directory with SkillsJars
  - MCP servers
- Additonal ACP
  - file references
- Autopilot
  - /autopilot prompt
  - Have the agent use it's own UI to improve itself, finding more improvements along the way
- Build isolation
  - Running `./gradlew compileKotlin` while the server is running causes `NoClassDefFoundError` because the `run` task's classpath points directly to `build/classes/` directories, which get overwritten by compilation
  - Option A: Use `./gradlew :server:runShadow` (already available via Ktor plugin, zero changes, but slower startup due to fat jar build)
  - Option B: Add a custom `runJar` task that depends on the `jar` task and runs from the built jar + dependency jars instead of loose class files (fast, isolated from recompilation)
- <tool_use_error>Cancelled: parallel tool call WebFetch errored</tool_use_error>
- The file diff renderer is or was on the server side. we need to move it to the client side.
