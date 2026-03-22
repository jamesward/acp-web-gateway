# ACP Web Gateway

A web interface to AI agents via the [Agent Client Protocol](https://agentclientprotocol.com/). See `SPEC.md` for product requirements and future plans.

## Project Structure

Three Gradle modules (Kotlin 2.3, JVM 25):

```
shared/   — Kotlin Multiplatform (JVM + WasmJS). Message types, WebSocket handler, ACP SDK integration. Kilua RPC service interface.
server/   — Ktor 3.x (CIO) web server. Proxy-only relay mode. Manages relay sessions, serves HTML pages + Kilua RPC WebSocket.
web/      — Kotlin/WasmJS Kilua composable UI. Pure Compose-style components, Kilua RPC for typed WebSocket communication.
```

## Key Files

### shared
- `Message.kt` — `WsMessage` sealed class (Prompt, AgentText, AgentThought, ToolCall, Connected, TurnComplete, PermissionRequest/Response, Cancel, Diagnose, BrowserStateRequest/Response, AvailableCommands, ChangeAgent, UserMessage, Error) and supporting types (`ToolCallDisplay`, `FileAttachment`, `CommandInfo`, `ChatEntry`). All browser<->server communication uses these serialized as JSON over Kilua RPC or raw WebSocket.
- `IChatService.kt` — Kilua RPC `@RpcService` interface defining the `chat` channel method.
- `WebSocketHandler.kt` — Core `handleChatChannels(input, output, session, manager, ...)` function processing ACP events into WsMessages. Thin `handleChatWebSocket()` wrapper for CLI/simulation use.

### server
- `Server.kt` — Ktor application setup, routing, `main()`. Proxy-only mode. CLI args: `--port`, `--debug`, `--dev`. Relay WebSocket routes, Kilua RPC routes, `GET /api/sessions/count` for CLI reference counting. Runs on port 8080.
- `ChatServiceImpl.kt` — Kilua RPC implementation of `IChatService`. Relay-only: bridges RPC channels to relay sessions. Sends "Waiting for CLI connection" when no relay session exists.
- `Pages.kt` — Server-side HTML page templates using kotlinx.html (`chatPage`, `landingPage`). Minimal shell with `<div id="root">` mount point.
- `Registry.kt` — Fetches ACP agent registry, resolves agent distribution (npx/uvx/binary) to a `ProcessCommand`.

### web
- `App.kt` — Kilua composable UI. Pure Compose-style components using `mutableStateOf` for reactive state. Kilua RPC `getService<IChatService>().chat {}` for typed WS communication. `@JsFun` bridges for console capture, DOM state collection, scroll management, and file download. Handles `BrowserStateRequest` messages for server-side browser debugging. Kilua form `textArea` with two-way value binding.

## Build & Run

This project can be used to work on itself. In which case, running build tasks can cause the server to get into a bad state (class files changing). Before you run a build, save what you know because a server restart will be required and this will lose the current task session.

```bash
# Compile everything (server + wasm)
./gradlew build

# Run server in proxy-only mode (no agent — relay only)
./gradlew :server:run

# Run dev server with in-process agent (test classpath)
./gradlew :server:runDev --args="--agent claude-code --debug"

# Run dev server in restart loop (for triggerable reloads)
./gradlew :server:devRun -Pargs="--agent github-copilot-cli --debug"

# Run unit tests (fast, no agent needed)
./gradlew test

# Run browser integration tests (Playwright in Docker, no real agent needed)
./gradlew :server:browserTest

# Run integration tests (spawns real ACP agent, slow)
# Defaults to claude-acp. Use -Dtest.acp.agent=github-copilot-cli to avoid claude-in-claude restriction.
./gradlew :server:integrationTest -Dtest.acp.agent=github-copilot-cli

# Build CLI native image (requires GraalVM)
./gradlew :cli:nativeCompile

# Compile checks only (fast)
./gradlew compileKotlinJvm :shared:compileKotlinWasmJs :web:compileKotlinWasmJs :server:compileKotlin
```

The server's `processResources` task automatically runs `:web:wasmJsBrowserDevelopmentWebpack` and copies WASM output to `server/build/resources/main/static/`.

**Port conflicts**: The user often runs the gateway server locally on port 8080 while working on this project. If you need to start the server for testing, use a different port via `--port` flag or `PORT` env var: `PORT=8081 ./gradlew :server:run --args="--agent claude-code"`. For compile-only verification (preferred), use the compile commands above instead.

## Architecture Notes

- **Proxy mode** (production): Server is relay-only. Landing page at `/`. Sessions created on demand via CLI connections at `/s/{sessionId}`. No in-process agent.
- **Dev mode** (test scope, `DevServer.kt`): In-process agent. Chat page at `/`. `AgentHolder` class manages agent lifecycle. Used via `./gradlew :server:runDev`.
- **ACP communication**: CLI spawns agent as subprocess, communicates via stdio using the ACP Kotlin SDK's `StdioTransport` → `Protocol` → `Client` → `ClientSession`. CLI relays messages to the proxy server over WebSocket.
- **Browser communication**: Browser connects via Kilua RPC (typed WebSocket channels). Server sends structured `WsMessage` types (AgentText, AgentThought, ToolCall, etc.). Client renders UI using Kilua composables with reactive state.
- **Permissions**: Agent requests permissions via `ClientSessionOperations.requestPermissions()` which suspends on a `CompletableDeferred`. Server sends `PermissionRequest` message. Client renders permission dialog as a Kilua composable and sends `PermissionResponse` back via RPC channel.
- **Debug mode**: `--debug` flag sets `data-debug="true"` on `<body>`, enables Screenshot checkbox, Download Log button, and Diagnose button.
- **Browser debugging**: When working on the gateway via itself (agent is claude-code), the ACP agent can investigate client-side state and errors. Three mechanisms are available:
  1. **`browser://` virtual files** — Read browser state via the `Read` tool (routed through ACP's `fsReadTextFile` → `GatewayClientOperations` → `BrowserStateRequest` WsMessage → client-side JS collection → `BrowserStateResponse`):
     - `browser://console` — Last 50 console log entries (log/warn/error with timestamps). Client installs console interceptors at startup via `installConsoleCapture()`.
     - `browser://dom` — DOM state summary: message count, viewport size, permission dialog visibility, page title, body classes, URL.
     - `browser://all` — Both console logs and DOM state combined as JSON.
  2. **Screenshot checkbox** — Visible in debug mode. User checks "Screenshot" next to Send. Captures a PNG screenshot via `html2canvas` (renders DOM to canvas, extracts base64 PNG). Sent as `ContentBlock.Image` to the agent.
  3. **Diagnose button** — Visible in debug mode while the agent is working. Cancels the current task, calls `buildDiagnosticContext()` which collects browser state (console + DOM), session state (elapsed time, active tool calls, pending permissions, recent history), and re-sends everything as a diagnostic prompt.

  **How `browser://` reads work end-to-end**:
  1. Agent calls `fsReadTextFile("browser://console")` via ACP protocol
  2. `GatewayClientOperations.fsReadTextFile()` intercepts the `browser://` prefix
  3. Creates a `CompletableDeferred` and sends `BrowserStateRequestInternal` to a channel
  4. `GatewaySession.startEventForwarding()` picks it up, sends `WsMessage.BrowserStateRequest` to the first connected client
  5. Client's `onMessage` handler calls `collectBrowserState()` which invokes JS functions (`getConsoleLogs()`, `getDomState()`)
  6. Client sends back `WsMessage.BrowserStateResponse` with collected JSON
  7. Server completes the deferred, `fsReadTextFile` returns the state as file content
  8. Times out after 10 seconds if the browser doesn't respond

  **When to use which**: If you suspect a client-side issue, read `browser://console` directly to check for JS errors. For visual issues, ask the user to check the Screenshot box. For stuck-task issues, the user can click Diagnose.

  **IMPORTANT — Always debug UI issues before guessing at fixes**: When any UI behavior is wrong (elements not appearing, wrong content, layout issues, interactions broken), you MUST investigate client-side state before making code changes:
  1. **First**: Read `browser://console` to check for JavaScript errors. JS errors in the WASM client can silently break rendering.
  2. **Second**: Read `browser://dom` to check DOM state (message count, permission dialog visibility, etc.).
  3. **Third**: Ask the user to send a Screenshot if the issue is visual (layout, styling, missing elements).
  4. **If `browser://` reads fail or time out**: The reads only work when the agent's file reads are routed through ACP's `fsReadTextFile`. If they return "File does not exist" or time out, ask the user to open browser DevTools (F12) and paste console errors directly.
  5. **If a permission dialog blocks the read**: The reads may trigger an ACP permission prompt. If the browser is unresponsive, this creates a deadlock. Ask the user to refresh and share console output manually.

  **Do NOT** skip debugging and jump to speculative code fixes for UI issues. Verify which layer has the bug (server-side message flow vs client-side Kilua composable rendering) before changing code.

## Testing

### Workflow for UI bugs

When unexpected UI behavior is reported, follow this process strictly:

1. **Reproduce** — Understand the exact symptom. Use `browser://console`, `browser://dom`, screenshots, or ask the user to paste DevTools console output.
2. **Write a failing test** — Write a test that reproduces the bug. Choose the lightest test layer that can capture the issue:
   - Fragment output wrong? → FragmentsTest (shared module, milliseconds).
   - Wrong HtmlUpdate sequence/targets/swap modes? → RenderingFlowTest (server module, seconds).
   - Client-side morph/DOM behavior wrong? → BrowserIntegrationTest (Docker/Playwright, slower).
3. **Fix the bug** — Make the minimal code change to fix the issue.
4. **Validate** — Run the test to confirm it passes. Run `./gradlew test` to ensure no regressions.

Do NOT fix UI bugs without a test. If you can't write a test first, explain why and get confirmation before proceeding.

### Test layers (fastest to slowest)

1. **Rendering flow tests** (`server/src/test/.../RenderingFlowTest.kt`) — Verify the WebSocket handler sends the correct sequence of `WsMessage` types for prompt round-trips. Uses `ControllableFakeClientSession` to simulate ACP events and `handleChatChannels` with in-memory channels. Run with `./gradlew :server:test --tests "*.RenderingFlowTest"`.
2. **Server tests** (`ServerTest.kt`) — HTTP endpoints, channel-based chat handler, file attachment handling. Run with `./gradlew :server:test`.
3. **Browser integration tests** (`BrowserIntegrationTest.kt`) — Full E2E with Playwright in Docker. Tests page load, WebSocket connection. Run with `./gradlew :server:browserTest`.
4. **Agent integration tests** (`AgentIntegrationTest.kt`) — Real ACP agent, slow. Run with `./gradlew :server:integrationTest -Dtest.acp.agent=github-copilot-cli`.

### When to add tests for UI changes
- **Changed WebSocketHandler.kt message logic** → Add/update rendering flow tests verifying WsMessage sequence.
- **Changed App.kt (client-side composables)** → Add/update browser integration tests.
- **Changed message types (Message.kt)** → Update shared tests and rendering flow tests.

## Tech Gotchas

### kotlinx.html (server module only)
- Used in `Pages.kt` for server-side HTML page templates.
- First positional arg varies by tag: `div("classes")` works, but `form("...")` is `action`, `textArea("...")` is `wrap`.
- **Always use the named `classes = "..."` parameter** to be safe.

### Kilua (web module)
- Uses Kilua composable functions for all UI rendering: `div`, `span`, `button`, `details`, `summary`, `header`, `h3`, `p`, `pre`, `label`, `rawHtml`, etc.
- Form controls: `textArea(value, rows, placeholder, disabled)` with `onInput { promptText = this.value }` for two-way binding.
- Events: `onClick`, `onInput`, `onKeydown`, `onEvent<EventType>("eventname")` — all composable.
- Kilua RPC: `getService<IChatService>().chat { sendChannel, receiveChannel -> ... }` for typed WebSocket channels.
- `setRpcUrlPrefix(prefix)` must be called before connecting in proxy mode to route to the correct session path.
- `@JsFun` bridges for: relay WS, `setRpcUrlPrefix`, console capture (`installConsoleCapture`, `getConsoleLogs`), DOM state (`getDomState`), scroll management (`isMessagesAtBottom`, `scrollMessagesToBottom`, `installScrollListener`, `readScrollAtBottom`), and file download (`downloadTextFile`).

### Kotlin/Wasm (web module)
- Requires `@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)`.
- WASM webpack dev output path: `build/kotlin-webpack/wasmJs/developmentExecutable/` (NOT `build/dist/...`).
- `web.dom.document` from kotlin-browser used only for reading body data attributes at startup.

### Ktor 3.x
- Use `WebSocketServerSession` (not `DefaultWebSocketServerSession`).
- Use `embeddedServer(CIO, port = 8080)` pattern.

### ACP SDK
- Registry JSON is `{"version":"...", "agents":[...]}` — not a bare array.
- `NpxDistribution`/`UvxDistribution` use `@SerialName("package") val packageName`.

## Dependencies (key versions)

- Kotlin: 2.3.10
- Ktor: 3.4.1
- ACP SDK: 0.16.5
- Kilua: 0.0.32
- Kilua RPC: 0.0.42
- kotlinx-serialization-json: 1.10.0
- kotlinx-html: 0.12.0 (server only, for page templates)
- commonmark: 0.27.1
