# Developer Usage

## Run the combined server & ACP proxy

```
./gradlew :server:devRun
```

Access at: http://localhost:8080/


## Run with CLI & Docker server

Start the server:
```
./gradlew :server:run
```

Start the CLI:
```
./gradlew :cli:run --args="--remote http://localhost:8080"
```

Optionally pass in the `--agent [id from ACP registry]` or `--agent-command [command]` flags to specify the agent to use.

Open the browser to the URL printed in the CLI.


## Run the CLI, auto-starting the server

Create the container:
```
./gradlew :server:jibDockerBuild -Djib.to.image=ghcr.io/jamesward/acp-web-gateway
```

Start the CLI, which starts the server:
```
./gradlew :cli:run --args="--debug"
```

Or build the CLI native exec (requires local gcc, etc) and run it:

```
./gradlew :cli:nativeCompile
./cli/build/native/nativeCompile/acp2web --debug
```


## Simulated Agent

Run the dev server with a simulated agent (no real ACP agent needed):

```
PORT=8081 ./gradlew :server:runDev --args="--agent simulate --debug"
```

Type `/simulate-default` in the chat to replay the default simulation (thinking, tool calls, images). Use `/simulate-default fast` to skip delays.

All simulations are JSON files in `server/src/test/resources/simulations/`. The name after `/simulate-` matches the filename (without `.json`), e.g., `/simulate-plan` loads `plan.json`.


## Capture & Replay

Capture a real agent interaction for future replay as a simulation.

### 1. Start a dev server with a real agent in debug mode

```
PORT=8081 ./gradlew :server:runDev --args="--agent claude-acp --debug"
```

### 2. Capture an interaction

In the chat, type `/capture` followed by your prompt:

```
/capture write a plan to change the LLM persona based on whether the user is in light or dark mode
```

The agent responds normally AND every message is recorded. After the turn completes, a JSON file is saved to the project directory (e.g., `simulation-capture-1711234567890.json`).

### 3. Install the captured data

Move the capture file to the simulations resource directory with a descriptive name:

```
mv simulation-capture-*.json server/src/test/resources/simulations/plan.json
```

### 4. Replay the captured simulation

Type `/simulate-plan` in any debug-mode chat to replay. Add `fast` to skip delays (1ms pacing instead of 100ms):

```
/simulate-plan
/simulate-plan fast
```
