# Agent Client Protocol Web Gateway

## Goal

A web interface to a server running an Agent Client Protocol server

## Background

The Agent Client Protocol was created to provide IDEs (primarily Zed & JetBrains) with rich integrations to CLI agents.
https://agentclientprotocol.com/

## UX

- Users will start the ACP Web Gateway on their machine in the context of a project
- When users launch the gateway, they can specify which agent from the registry (https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) to use. By default this is a cli flag but there should also be support for a global config file default.
- Users can choose to either run in local mode (local web server) or proxy mode
- In proxy mode the ACP Web Gateway runs somewhere else and the user provides the base URL. Initialization creates a UUID for the session. The user can then interact with that session using the base URL / UUID
- Users should be able to perform normal AI code assistant interactions via the web gateway (prompts, approvals, diff review, etc)
- When a user joins a session they should see the history, but this is only kept in memory in the web gateway
- Web UI needs to be desktop & mobile friendly and support multiple modalities (markdown, images, code syntax highlighting, etc)

## Architecture

- The ACP Web Gateway will spawn the ACP server
- The gateway will contain the compiled Wasm artifacts for interactive web aspects

## Technologies

- Kotlin ACP Client (https://github.com/agentclientprotocol/kotlin-sdk)
- Ktor Server
- Kotlin Native & Kotlin JVM runtimes
- Tailwind CSS
- Kotlin WASM for browser logic
- WebSockets
- Kotlin Power Assert for tests
- Docker container for web gateway server
- Gradle build with kts definitions
- All html templates are typesafe using kotlinx.html (potentially reuse for server-side & client-side?)

## Distribution

- GitHub Actions for automated releases
- Docker container on ghcr.io
- Binaries via Kotlin Native for Linux, Mac, Windows
- Jar on Maven Central
