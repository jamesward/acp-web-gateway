plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.power-assert")
    id("io.ktor.plugin")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.jamesward.acpgateway.server.ServerKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

val ktorVersion = "3.4.1"
val acpSdkVersion = "0.16.5"

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("com.agentclientprotocol:acp:$acpSdkVersion")
    implementation("com.agentclientprotocol:acp-ktor-client:$acpSdkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.7.0")
    implementation("io.ktor:ktor-server-webjars:$ktorVersion")
    implementation("org.webjars.npm:tailwindcss__browser:4.2.1")
    implementation("org.commonmark:commonmark:0.27.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    exclude("**/AgentIntegrationTest*")
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that require a real ACP agent"
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    include("**/AgentIntegrationTest*")
}

val copyWasm = tasks.register<Copy>("copyWasmAssets") {
    dependsOn(":web:wasmJsBrowserDevelopmentWebpack")
    from(project(":web").layout.buildDirectory.dir("kotlin-webpack/wasmJs/developmentExecutable"))
    into(layout.buildDirectory.dir("resources/main/static"))
}

tasks.named("processResources") {
    dependsOn(copyWasm)
}
