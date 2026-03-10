# TODO

- UX
  - gateway CLI
    - should it only support remote proxy and be Kotlin Native?
    - users could spin up a docker container to run the gateway
- UI
  - Switch agents via the web UI
- Additional functionality
  - Skills Directory with SkillsJars
  - MCP servers
  - Option to externalize the history (likely Valkey)
- Additonal ACP
  - slash commands
  - file references
- Autopilot
  - /autopilot prompt
  - todo: what does it do?
- Code
  - Reduce nullability
  - Reduce mutable state, ideally only in tests
  - Provide better type safety for the UI
  - Make invalid states unrepresentable
- Meta
  - Have the agent use it's own UI to improve itself, finding more improvements along the way

