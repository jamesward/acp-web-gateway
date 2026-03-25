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
- The server runs in **proxy mode** only (multi-session relay). For development, a **dev mode** server with in-process agent is available in test scope.
- The CLI starts a local Docker container running the proxy server by default, or connects to a remote server with `--remote`.
- Sessions are created on demand via CLI connections. Each CLI instance creates a UUID session, connects to the server, and prints the session URL for the browser.
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
- Debug mode (`--debug`) enables Screenshot checkbox and Download Log button
- Dev mode (`--dev`) enables a Reload button for hot-reload during development

## Architecture

- The ACP agent runs locally via the `acp2web` CLI, which spawns it as a subprocess and communicates via stdio transport using the ACP Kotlin SDK. The CLI relays messages to the proxy server over WebSocket.
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

- **Proxy mode** (production): The server is relay-only. Sessions created on demand via CLI connections. Landing page at `/`. Session URLs are `/s/{sessionId}`. Three WebSocket routes per session:
  - `/s/{sessionId}/agent` — CLI backend relay WebSocket
  - `/s/{sessionId}/ws` — Browser raw WebSocket (relay)
  - `/s/{sessionId}` — Kilua RPC endpoint
- **Dev mode** (test scope): Single session with in-process agent. Root `/` serves chat page. Kilua RPC at root level. Used for development via `./gradlew :server:runDev`.

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
2. **On disconnect**, the browser retries with exponential backoff (1s, 2s, 4s, ... capped at 30s). The header title changes to "Reconnecting…" while disconnected.
3. **On reconnect**, the browser sends a `ResumeFrom` message with `lastSeq` (the last sequence number it received). The server checks the turn buffer for messages since that sequence.
4. **Delta resume**: If the turn buffer has messages after `lastSeq`, only those are replayed (efficient catch-up within the current turn).
5. **Full replay**: If `lastSeq` is too old or zero, the server replays complete chat history from the session store, then current turn state (in-progress thoughts, text, tool calls).
6. **Pending permission dialogs** are re-sent on reconnect — the server tracks `activePermission` per session.
7. **Multiple browsers** can connect to the same session. Each tracks its own `lastSeq`. Disconnection of one browser doesn't affect others.

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
- **Input bar** — File preview chips (removable), slash command autocomplete popup (arrow key / Tab / Escape navigation), textarea with Enter-to-send (Shift+Enter for newline), attach button (file picker), Send/Cancel buttons, Screenshot checkbox and Download Log button (debug mode).
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

- Screenshot checkbox — Captures PNG via html2canvas (renders DOM to canvas, extracts base64 PNG)
- Download Log button — Downloads full chat log as text file

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

- Docker container on ghcr.io (base image: `eclipse-temurin:25-jre`, no Node.js/uv — agents run on the CLI side)
- CLI binaries for macOS (arm64), Linux (amd64), Windows (amd64) as GraalVM native images (~58MB, no JVM required)

### CI/CD

- **Every push to `main`**: runs tests.
- **Tagged releases** (`v*`): runs tests, builds CLI native binaries for all platforms, builds and pushes Docker image to ghcr.io, creates GitHub Release with binaries attached.
- **Heroku** (acp2web.com): deploys `main` automatically via Procfile.

### Running via Docker

The published container `ghcr.io/jamesward/acp-web-gateway` runs in proxy-only mode. The CLI starts it automatically via Docker by default.

```bash
# Manual Docker start (proxy-only mode)
docker run -d --rm \
  -p 8080:8080 \
  ghcr.io/jamesward/acp-web-gateway
```

- `-p 8080:8080` — Exposes the gateway on `http://localhost:8080`.
- Add `--debug` for debug mode.
- The container is a plain JRE image — agents run locally via the CLI, not inside the container.

## Versioning

Version is derived from git tags via the `com.palantir.git-version` Gradle plugin. Docker images are tagged on release.

## acp2web CLI

The CLI (`cli/` module) runs the ACP agent locally and connects to a gateway server. By default, it starts a local Docker container running the proxy server. The `--remote` flag connects to a remote server instead.

### Default mode (Docker)

1. CLI checks `~/.acp2web/server.json` for an existing Docker container
2. If a running container is found (health check passes), reuses it
3. Otherwise, starts `docker run -d --rm -p <random-port>:8080 ghcr.io/jamesward/acp-web-gateway --shutdown-on-idle`
4. Creates a UUID session ID and connects to `ws://localhost:<port>/s/{sessionId}/agent`
5. Prints the browser URL for the user to open
6. Spawns the ACP agent locally and relays messages

The server uses `--shutdown-on-idle` to automatically exit when no sessions are active (grace period of 30s). Docker's `--rm` flag ensures the container is cleaned up.

### Remote mode (`--remote`)

- `acp2web --agent foo` → Docker mode (default)
- `acp2web --remote --agent foo` → connect to `https://www.acp2web.com`
- `acp2web --remote https://my-server.com --agent foo` → connect to specified URL

### Common behavior

- Users start `acp2web` in their project directory
- The CLI creates a UUID session ID and connects to the gateway at `/s/{sessionId}/agent`
- The gateway URL is printed to the console for the user to open in their browser
- The CLI spawns the ACP agent subprocess locally and relays messages between the agent and the gateway
- The CLI supports `--agent <id>` and `--agent-command <command>` (mutually exclusive)
- If `--agent` is not specified, the user selects their agent in the browser UI; the CLI receives a `ChangeAgent` message and starts the chosen agent
- Agent switching mid-session is supported via `ChangeAgent` messages
- The CLI uses Clikt for argument parsing and Ktor WebSocket client for the relay connection
- Built as a GraalVM native image — single static binary per platform, no JVM required, instant startup
- Native image reachability metadata for Ktor CIO, Logback, and kotlinx-serialization in `cli/src/main/resources/META-INF/native-image/`
- The `org.graalvm.buildtools.native` Gradle plugin provides the `nativeCompile` task

## MCP Server

The gateway exposes an MCP (Model Context Protocol) Streamable HTTP endpoint that provides a task-based API for interacting with ACP agents. This allows MCP clients to send prompts and monitor agent responses programmatically.

**Note:** ACP tasks are not yet natively supported in the ACP protocol. The gateway simulates tasks by mapping each prompt round-trip to a tracked task object.

### Endpoints

- **Dev mode:** `/mcp`
- **Relay mode:** `/s/{sessionId}/mcp`

### MCP Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `list_acp_tasks` | (none) | List all tasks in the session |
| `create_acp_task` | `prompt: String` | Send a prompt to the agent, returns a task ID. Poll `get_acp_task` for updates. |
| `get_acp_task` | `taskId: String` | Get task state: response text, thinking, tool calls, pending permissions, errors |
| `cancel_acp_task` | `taskId: String` | Cancel a running task |

### Task States

`Created` → `Working` → `Completed` / `Failed` / `Cancelled`

### Module

The MCP server lives in the `mcp-server` Gradle module (`com.jamesward.acpgateway.mcp`). It depends on `shared` and the MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk:0.9.0`). The `server` module depends on `mcp-server` and mounts the Ktor routes.

### UI

The web client shows an MCP icon in the header. Clicking it displays the MCP endpoint URL for the current session.

## Future

- Versioning scheme: `<protocol>.<date>.<seq>` with `Version.kt` generated at build time, protocol version constant in `shared/build.gradle.kts`, Docker image tagged with both `<full-version>` and `<protocol-version>`
- CLI↔Server protocol check: CLI sends `&protocol=<protocol-version>` query parameter, server validates and closes on mismatch, `/health` returns JSON with version/protocol
- CLI self-update: `acp2web --update` checks GitHub Releases and downloads appropriate platform binary
- Browser debugging: `browser://` virtual file reads (`browser://console`, `browser://dom`, `browser://all`), `BrowserStateRequest`/`BrowserStateResponse` message types, Diagnose button (collects browser+session state, re-sends as diagnostic prompt)
- CI: Docker image build/push on every push to `main` (currently only on tagged releases)
- CLI relay reconnect with exponential backoff and sequence-based catch-up
  - Exponential backoff reconnect on relay WebSocket disconnect
  - Sequence-based delta catch-up on relay reconnect
- Global config file for default agent selection
- Audio recording support (MediaRecorder API)
- Additional functionality
  - Skills Directory with SkillsJars
  - MCP server enhancements: permission response tool, native ACP task support when available
- Autopilot (partially implemented: `/autopilot` command in dev/test scope takes a screenshot of its own UI and asks the agent to evaluate it)
  - Have the agent use its own UI to improve itself iteratively, finding more improvements along the way
- Build isolation
  - Running `./gradlew compileKotlin` while the server is running causes `NoClassDefFoundError` because the `run` task's classpath points directly to `build/classes/` directories, which get overwritten by compilation
  - Option A: Use `./gradlew :server:runShadow` (already available via Ktor plugin, zero changes, but slower startup due to fat jar build)
  - Option B: Add a custom `runJar` task that depends on the `jar` task and runs from the built jar + dependency jars instead of loose class files (fast, isolated from recompilation)
- Kotlin/Native for CLI once Kotlin ACP has native targets
- diff rendering in tool call isn't nice
