# ACP Web Gateway

A web interface to AI agents via the [Agent Client Protocol](https://agentclientprotocol.com/). See `SPEC.md` for product requirements and future plans.

## Project Structure

Three Gradle modules (Kotlin 2.3, JVM 21):

```
shared/   — Kotlin Multiplatform (JVM + WasmJS). Message types, CSS definitions (kotlin-css), HTML fragment builders (kotlinx.html).
server/   — Ktor 3.x (CIO) web server. Spawns the ACP agent process, manages sessions, serves HTML + WebSocket. Renders all UI fragments server-side.
web/      — Kotlin/WasmJS thin browser client. Receives HtmlUpdate messages, applies to DOM via idiomorph. Handles form, files, scroll, screenshot.
```

## Key Files

### shared
- `Message.kt` — `WsMessage` sealed class (Prompt, HtmlUpdate, Connected, TurnComplete, PermissionResponse, Cancel, Diagnose, BrowserStateRequest/Response, etc.) and supporting types. All browser<->server communication uses these serialized as JSON over WebSocket.
- `Styles.kt` — All CSS definitions using kotlin-css CssBuilder. `object Css` holds class name constants. `appStylesheet()` generates the full CSS string served at `/styles.css`.
- `Fragments.kt` — HTML fragment builders using kotlinx.html `createHTML()`. Functions: `userMessageHtml`, `assistantMessageHtml`, `assistantRenderedHtml`, `thoughtMessageHtml`, `errorMessageHtml`, `toolBlockHtml`, `permissionContentHtml`, `statusTimerHtml`, `filePreviewHtml`.

### server
- `Server.kt` — Ktor application setup, routing, `main()`. CLI args: `--agent <id>`, `--mode local|proxy`, `--debug`. Serves `/styles.css` from shared `appStylesheet()`. Runs on port 8080.
- `AgentProcessManager.kt` — Spawns the ACP agent subprocess via stdio transport. Manages `GatewaySession` instances (prompt mutex, history, tool call tracking). Screenshots sent as `ContentBlock.Image` (PNG).
- `WebSocketHandler.kt` — `handleChatWebSocket()` bridges browser WebSocket to ACP session. Renders all UI as HTML fragments using shared builders, sends `HtmlUpdate` messages. Server maintains tool block state per connection.
- `GatewayClientOperations.kt` — Implements `ClientSessionOperations` (ACP SDK). Handles file I/O, terminal operations, and permission request flow via `CompletableDeferred`.
- `Pages.kt` — Server-side HTML page templates using kotlinx.html (`chatPage`, `landingPage`). Inlines idiomorph (~3KB) for DOM morphing.
- `Registry.kt` — Fetches ACP agent registry, resolves agent distribution (npx/uvx/binary) to a `ProcessCommand`.

### web
- `App.kt` — Thin browser client using kotlin-browser typed DOM APIs + `@JsFun` bridges. Receives `HtmlUpdate` messages and applies them to the DOM via idiomorph (morph), innerHTML, or insertAdjacentHTML (beforeend). Handles form submission, file attachments, scroll management, status timer, permission response (event delegation), and SVG foreignObject PNG screenshots.

## Build & Run

This project can be used to work on itself. In which case, running build tasks can cause the server to get into a bad state (class files changing). Before you run a build, save what you know because a server restart will be required and this will lose the current task session.

```bash
# Compile everything (server + wasm)
./gradlew build

# Run server (compiles wasm, copies to server resources automatically)
./gradlew :server:run --args="--agent claude-code --debug"

# Run server in dev mode (for triggerable reloads)
./gradlew :server:devRun -Pargs="--agent github-copilot-cli --debug"

# Run unit tests (fast, no agent needed)
./gradlew test

# Run browser integration tests (Playwright in Docker, no real agent needed)
./gradlew :server:browserTest

# Run integration tests (spawns real ACP agent, slow)
# Defaults to claude-acp. Use -Dtest.acp.agent=github-copilot-cli to avoid claude-in-claude restriction.
./gradlew :server:integrationTest -Dtest.acp.agent=github-copilot-cli

# Compile checks only (fast)
./gradlew compileKotlinJvm :shared:compileKotlinWasmJs :web:compileKotlinWasmJs :server:compileKotlin
```

The server's `processResources` task automatically runs `:web:wasmJsBrowserDevelopmentWebpack` and copies WASM output to `server/build/resources/main/static/`.

**Port conflicts**: The user often runs the gateway server locally on port 8080 while working on this project. If you need to start the server for testing, use a different port via `--port` flag or `PORT` env var: `PORT=8081 ./gradlew :server:run --args="--agent claude-code"`. For compile-only verification (preferred), use the compile commands above instead.

## Architecture Notes

- **Local mode**: Single session auto-created at startup. Root `/` serves chat page, `/ws` is WebSocket.
- **Proxy mode**: Sessions created on demand. URLs are `/s/{sessionId}` and `/s/{sessionId}/ws`.
- **ACP communication**: Server spawns agent as subprocess, communicates via stdio using the ACP Kotlin SDK's `StdioTransport` → `Protocol` → `Client` → `ClientSession`.
- **Browser communication**: Browser connects via WebSocket. Server renders all UI as HTML fragments using shared builders (Fragments.kt), sends `HtmlUpdate(target, swap, html)` messages. Client applies updates via idiomorph (morph), innerHTML, or insertAdjacentHTML (beforeend).
- **Permissions**: Agent requests permissions via `ClientSessionOperations.requestPermissions()` which suspends on a `CompletableDeferred`. Server sends pre-rendered permission dialog HTML via `HtmlUpdate`. Client uses event delegation on `data-tool-call-id`/`data-option-id` attributes to send `PermissionResponse` back.
- **Debug mode**: `--debug` flag sets `data-debug="true"` on `<body>`, enables the Diagnose button for stuck-task diagnostics.
- **Browser debugging**: When working on the gateway via itself (agent is claude-code), the ACP agent can investigate client-side state and errors. Three mechanisms are available:
  1. **`browser://` virtual files** — Read browser state via the `Read` tool (routed through `fsReadTextFile`):
     - `browser://console` — Last 50 console log entries (log/warn/error with timestamps)
     - `browser://dom` — DOM state summary (message count, WebSocket state, viewport size, permission dialog visibility, etc.)
     - `browser://all` — Both console logs and DOM state combined
  2. **Screenshot checkbox** — User checks "Screenshot" next to Send. Captures a real PNG screenshot via SVG foreignObject (clones messages DOM, embeds CSS, renders to canvas, extracts base64 PNG). Sent as `ContentBlock.Image` to the agent.
  3. **Diagnose button** — Visible in debug mode (`--debug`) while the agent is working. Cancels the current task, collects browser state (console + DOM), and re-sends everything as a diagnostic prompt.

  **When to use which**: If you suspect a client-side issue, ask the user to check the Screenshot box and describe the problem — or read `browser://console` directly to check for JS errors. For stuck-task issues, the user can click Diagnose.

  **IMPORTANT — Always debug UI issues before guessing at fixes**: When any UI behavior is wrong (elements not appearing, wrong content, layout issues, interactions broken), you MUST investigate client-side state before making code changes:
  1. **First**: Read `browser://console` to check for JavaScript errors. JS errors in the WASM client or idiomorph can silently break rendering.
  2. **Second**: Read `browser://dom` to check DOM state (message count, WebSocket readyState, permission dialog visibility, etc.).
  3. **Third**: Ask the user to send a Screenshot if the issue is visual (layout, styling, missing elements).
  4. **If `browser://` reads fail or time out**: The `browser://` reads only work when the agent's file reads are routed through the ACP protocol's `fsReadTextFile`. If they return "File does not exist" or time out, ask the user to open browser DevTools (F12) and paste console errors directly. The browser may be unresponsive, or the ACP file routing may not be active.
  5. **If a permission dialog blocks the read**: The `browser://` reads may trigger an ACP permission prompt. If the browser is unresponsive, this creates a deadlock. Ask the user to refresh and share console output manually.

  **Do NOT** skip debugging and jump to speculative code fixes for UI issues. The server-side HTML generation (Fragments.kt) and the client-side DOM patching (App.kt via idiomorph) are two separate layers — verify which layer has the bug before changing code.

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

1. **Fragment tests** (`shared/src/commonTest/.../FragmentsTest.kt`) — Verify HTML output of fragment builders: CSS classes, IDs, data attributes, nesting, tool block summary logic. Run with `./gradlew :shared:jvmTest`.
2. **Rendering flow tests** (`server/src/test/.../RenderingFlowTest.kt`) — Verify the WebSocket handler sends the correct sequence of `HtmlUpdate` messages (targets, swap modes, HTML content) for prompt round-trips. Uses `ControllableFakeClientSession` to simulate ACP events. Run with `./gradlew :server:test --tests "*.RenderingFlowTest"`.
3. **Server tests** (`ServerTest.kt`) — HTTP endpoints, WebSocket connection, file attachment handling. Run with `./gradlew :server:test`.
4. **Browser integration tests** (`BrowserIntegrationTest.kt`) — Full E2E with Playwright in Docker. Tests page load, form submission, permission dialogs, screenshots. Run with `./gradlew :server:browserTest`.
5. **Agent integration tests** (`AgentIntegrationTest.kt`) — Real ACP agent, slow. Run with `./gradlew :server:integrationTest -Dtest.acp.agent=github-copilot-cli`.

### When to add tests for UI changes
- **Changed Fragments.kt** → Add/update fragment tests verifying HTML structure.
- **Changed WebSocketHandler.kt rendering logic** → Add/update rendering flow tests verifying HtmlUpdate sequence.
- **Changed App.kt (client-side)** → Add/update browser integration tests. Also check via `browser://console` for JS errors.
- **Changed Styles.kt** → Visual verification via screenshot. Consider browser integration test if layout-critical.

## Tech Gotchas

### kotlinx.html
- First positional arg varies by tag: `div("classes")` works, but `form("...")` is `action`, `textArea("...")` is `wrap`.
- **Always use the named `classes = "..."` parameter** to be safe.

### Kotlin/Wasm (web module)
- Uses `kotlin-wrappers:kotlin-browser` for typed DOM access (`web.dom.document`, `web.html.HTMLElement`, `web.location.location`, `web.sockets.WebSocket`, `web.timers.*`, `web.keyboard.KeyboardEvent`).
- kotlin-browser's branded string types (`ElementId`, `ClassName`, `HtmlSource`, `InsertPosition`) and `EventHandler` external interface can't be easily constructed from Kotlin/Wasm, so `@JsFun` helpers are used for: `getEl`, `addCls`/`rmCls`/`setCls`, `setHtml`, `insertHtml`, and event binding (`onSubmit`, `onClick`, `onKeyDown`, etc.).
- Remaining `@JsFun` declarations for: idiomorph, file reading, drag-drop, paste, event delegation, console capture, browser state, screenshot.
- Callbacks from JS to Wasm use `wrapStringCallback`/`wrapTwoStringCallback`/`wrapDropCallback` patterns.
- Requires `@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)`.
- WASM webpack dev output path: `build/kotlin-webpack/wasmJs/developmentExecutable/` (NOT `build/dist/...`).
- Client is a thin patcher — receives pre-rendered HTML from server, applies via idiomorph.

### kotlin-css (shared module)
- Typed CSS DSL via `CssBuilder`. All styles defined in `shared/.../Styles.kt`.
- `appStylesheet()` generates the full CSS string, served at `/styles.css`.
- `object Css` holds all class name constants (e.g. `Css.MSG_USER`, `Css.TOOL_BLOCK`).
- `BorderRadius()` constructor doesn't exist for multi-value — use `put("border-radius", "...")`.
- `RuleSet` = `CssBuilder.() -> Unit` for composable style blocks.

### idiomorph
- ~3KB DOM differ inlined in the page template (Pages.kt `IDIOMORPH_INLINE`).
- **PATCHED**: `createMorphContext` deep-merges `config.callbacks` onto defaults. Stock idiomorph uses `Object.assign` which shallow-merges, so passing a partial `callbacks` object (e.g. only `beforeNodeMorphed`) would replace ALL default callbacks and crash. Our patch saves the user's callbacks, assigns defaults, then merges user overrides on top.
- Called via `Idiomorph.morph(el, html, {morphStyle: 'outerHTML', callbacks: {…}})` from client. Only specify the callbacks you need to customize — unspecified ones get defaults.
- Preserves scroll position, focus state, and form values during morph.

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
- kotlinx-html: 0.12.0
- kotlin-css: 2026.3.8
- kotlin-browser: 2026.3.8
- commonmark: 0.27.1
- idiomorph: 0.3.0 (inlined)
