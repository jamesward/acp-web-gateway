# ACP Web Gateway

A web interface to AI agents via the [Agent Client Protocol](https://agentclientprotocol.com/). See `SPEC.md` for product requirements and future plans.

## Project Structure

Three Gradle modules (Kotlin 2.3, JVM 21):

```
shared/   — Kotlin Multiplatform (JVM + WasmJS). Serializable message types shared between server and browser.
server/   — Ktor 3.x (CIO) web server. Spawns the ACP agent process, manages sessions, serves HTML + WebSocket.
web/      — Kotlin/WasmJS browser app. Handles chat UI, WebSocket client, permission dialogs.
```

## Key Files

### shared
- `Message.kt` — `WsMessage` sealed class (Prompt, AgentText, ToolCall, PermissionRequest/Response, Cancel, Diagnose, etc.) and `ChatEntry`. All browser<->server communication uses these serialized as JSON over WebSocket.

### server
- `Server.kt` — Ktor application setup, routing, `main()`. CLI args: `--agent <id>`, `--mode local|proxy`, `--debug`. Runs on port 8080.
- `AgentProcessManager.kt` — Spawns the ACP agent subprocess via stdio transport. Manages `GatewaySession` instances (prompt mutex, history, tool call tracking).
- `WebSocketHandler.kt` — `handleChatWebSocket()` bridges browser WebSocket to ACP session. Processes `WsMessage` from browser, streams ACP events back.
- `GatewayClientOperations.kt` — Implements `ClientSessionOperations` (ACP SDK). Handles file I/O, terminal operations, and permission request flow via `CompletableDeferred`.
- `Pages.kt` — Server-side HTML templates using kotlinx.html (`chatPage`, `landingPage`).
- `Registry.kt` — Fetches ACP agent registry, resolves agent distribution (npx/uvx/binary) to a `ProcessCommand`.

### web
- `App.kt` — Entire browser-side app in one file. Kotlin/Wasm with `@JsFun` JS interop. Manages WebSocket connection, DOM manipulation, form handling, tool call display, permission dialogs.

## Build & Run

```bash
# Compile everything (server + wasm)
./gradlew build

# Run server (compiles wasm, copies to server resources automatically)
./gradlew :server:run --args="--agent claude-code --debug"

# Run unit tests (fast, no agent needed)
./gradlew test

# Run integration tests (spawns real ACP agent, slow)
./gradlew :server:integrationTest

# Compile checks only (fast)
./gradlew compileKotlinJvm :shared:compileKotlinWasmJs :web:compileKotlinWasmJs :server:compileKotlin
```

The server's `processResources` task automatically runs `:web:wasmJsBrowserDevelopmentWebpack` and copies WASM output to `server/build/resources/main/static/`.

**Port conflicts**: The user often runs the gateway server locally on port 8080 while working on this project. If you need to start the server for testing, use a different port via `--port` flag or `PORT` env var: `PORT=8081 ./gradlew :server:run --args="--agent claude-code"`. For compile-only verification (preferred), use the compile commands above instead.

## Architecture Notes

- **Local mode**: Single session auto-created at startup. Root `/` serves chat page, `/ws` is WebSocket.
- **Proxy mode**: Sessions created on demand. URLs are `/s/{sessionId}` and `/s/{sessionId}/ws`.
- **ACP communication**: Server spawns agent as subprocess, communicates via stdio using the ACP Kotlin SDK's `StdioTransport` → `Protocol` → `Client` → `ClientSession`.
- **Browser communication**: Browser connects via WebSocket. Server bridges ACP events to `WsMessage` JSON frames.
- **Permissions**: Agent requests permissions via `ClientSessionOperations.requestPermissions()` which suspends on a `CompletableDeferred`. The server sends a `PermissionRequest` to the browser, which shows a dialog. User response completes the deferred.
- **Debug mode**: `--debug` flag sets `data-debug="true"` on `<body>`, enables the Diagnose button for stuck-task diagnostics.

## Tech Gotchas

### kotlinx.html
- First positional arg varies by tag: `div("classes")` works, but `form("...")` is `action`, `textArea("...")` is `wrap`.
- **Always use the named `classes = "..."` parameter** to be safe.

### Kotlin/Wasm (web module)
- `org.w3c.dom` types are NOT available in Kotlin/Wasm.
- All DOM access uses `@JsFun("...") private external fun` declarations.
- Callbacks from JS to Wasm use `wrapStringCallback`/`wrapVoidCallback` patterns.
- Requires `@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)`.
- WASM webpack dev output path: `build/kotlin-webpack/wasmJs/developmentExecutable/` (NOT `build/dist/...`).

### Tailwind CSS v4 (browser JIT)
- Uses `@tailwindcss/browser` webjar for runtime JIT compilation — no build step.
- Activation: `<style type="text/tailwindcss"></style>` (empty tag is sufficient).
- Do NOT put `@import "tailwindcss"` in the style tag — it causes a 404 fetch.

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
- kotlinx-serialization-json: 1.10.0
- Tailwind CSS browser: 4.2.1
- commonmark: 0.27.1
