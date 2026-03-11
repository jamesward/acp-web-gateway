plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.power-assert")
}

val ktorVersion = "3.4.1"
val acpSdkVersion = "0.16.5"

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-html:0.12.0")
            implementation("org.jetbrains.kotlin-wrappers:kotlin-css:2026.3.8")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
            api("com.agentclientprotocol:acp:$acpSdkVersion")
            api("io.ktor:ktor-websockets:$ktorVersion")
            implementation("io.ktor:ktor-client-cio:$ktorVersion")
            implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.7.0")
            implementation("org.commonmark:commonmark:0.27.1")
            implementation("io.github.java-diff-utils:java-diff-utils:4.15")
            implementation("ch.qos.logback:logback-classic:1.5.18")
        }
    }
}
