plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.jamesward.acpgateway.cli.CliKt"
}

val ktorVersion = "3.4.1"

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
