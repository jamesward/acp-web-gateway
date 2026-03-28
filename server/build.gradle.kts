plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.power-assert")
    id("io.ktor.plugin")
    id("com.google.cloud.tools.jib")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.jamesward.acpgateway.server.ServerKt"
}

val ktorVersion = "3.4.2"
val kiluaRpcVersion = "0.0.42"

dependencies {
    implementation(project(":shared"))
    implementation("io.modelcontextprotocol:kotlin-sdk:0.10.0")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("dev.kilua:kilua-rpc-ktor-jvm:$kiluaRpcVersion")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.orange-buffalo:testcontainers-playwright:0.12.0")
    testImplementation("org.testcontainers:testcontainers:2.0.4")
}

tasks.test {
    exclude("**/AgentIntegrationTest*")
    exclude("**/BrowserIntegrationTest*")
    exclude("**/CliRelayIntegrationTest*")
    exclude("**/McpServerTest*")
    exclude("**/McpServerTsTest*")
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that require a real ACP agent"
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    include("**/AgentIntegrationTest*")
    systemProperty("test.acp.agent", System.getProperty("test.acp.agent") ?: project.findProperty("test.acp.agent")?.toString() ?: "claude-acp")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED, org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED, org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED, org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    }
}

tasks.register<Test>("cliRelayTest") {
    description = "Runs CLI relay integration tests (no real agent or browser needed)"
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    include("**/CliRelayIntegrationTest*")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED, org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED, org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED, org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    }
}

tasks.register<Test>("mcpTest") {
    description = "Runs MCP Streamable HTTP server tests (no real agent needed)"
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    include("**/McpServerTest*", "**/McpServerTsTest*")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED, org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED, org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED, org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    }
}

tasks.register<Test>("browserTest") {
    description = "Runs browser integration tests with Playwright"
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    include("**/BrowserIntegrationTest*")
    dependsOn("processResources")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED, org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED, org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED, org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    }
}

val isProduction = gradle.startParameter.taskNames.any { "stage" in it || "installDist" in it || "jib" in it.lowercase() }

jib {
    from {
        image = "eclipse-temurin:25-jre"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = project.findProperty("jib.to.image")?.toString() ?: "acp-web-gateway"
        val cleanVersion = project.version.toString().removePrefix("v").removeSuffix(".dirty")
        tags = (project.findProperty("jib.to.tags")?.toString()
            ?: "$cleanVersion,latest").split(",").toSet()
    }
    container {
        mainClass = "com.jamesward.acpgateway.server.ServerKt"
        ports = listOf("8080")
        args = emptyList()
    }
}

val copyWasm = tasks.register<Copy>("copyWasmAssets") {
    if (isProduction) {
        dependsOn(":web:wasmJsBrowserProductionWebpack")
        from(project(":web").layout.buildDirectory.dir("kotlin-webpack/wasmJs/productionExecutable"))
    } else {
        dependsOn(":web:wasmJsBrowserDevelopmentWebpack")
        from(project(":web").layout.buildDirectory.dir("kotlin-webpack/wasmJs/developmentExecutable"))
    }
    into(layout.buildDirectory.dir("resources/main/static"))
}

tasks.named("processResources") {
    dependsOn(copyWasm)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

abstract class DevRunTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val gradlew: Property<String>

    @get:Input
    abstract val appArgs: Property<String>

    @get:Input
    abstract val gradleTarget: Property<String>

    @TaskAction
    fun run() {
        while (true) {
            val result = execOps.exec {
                commandLine(gradlew.get(), gradleTarget.get(), "--args=${appArgs.get()}")
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                logger.lifecycle("Server exited with code ${result.exitValue}. Not restarting.")
                break
            }
            logger.lifecycle("Server exited cleanly. Restarting...")
        }
    }
}

tasks.register<JavaExec>("runSimulate") {
    description = "Runs the server with a simulated agent (FakeClientSession) for UI development"
    group = "application"
    mainClass = "com.jamesward.acpgateway.server.SimulationServerKt"
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runAutoPilot") {
    description = "Runs the server with autopilot support (Playwright container for self-evaluation via /autopilot command)"
    group = "application"
    mainClass = "com.jamesward.acpgateway.server.AutoPilotServerKt"
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runDev") {
    description = "Runs the dev server with in-process agent (test classpath)"
    group = "application"
    mainClass = "com.jamesward.acpgateway.server.DevServerKt"
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<DevRunTask>("devRun") {
    description = "Runs the dev server in a restart loop for development. /autopilot command is always available."
    group = "application"
    gradlew = "${rootProject.projectDir}/gradlew"
    gradleTarget = ":server:runAutoPilot"
    appArgs = providers.gradleProperty("args").orElse("--agent claude-acp --debug --dev")
}
