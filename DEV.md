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
