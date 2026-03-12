# TODO

- Additional functionality
  - Skills Directory with SkillsJars
  - MCP servers
  - Option to externalize the history (likely Valkey)
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
