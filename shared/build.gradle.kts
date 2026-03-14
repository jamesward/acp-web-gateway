plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.power-assert")
    id("com.google.devtools.ksp")
    id("dev.kilua.rpc")
}

val ktorVersion = "3.4.1"
val acpSdkVersion = "0.16.5"
val kiluaRpcVersion = "0.0.42"

extensions.configure<dev.kilua.rpc.gradle.KiluaRpcExtension> {
    enableGradleTasks.set(false)
}

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
            implementation("dev.kilua:kilua-rpc-ktor:$kiluaRpcVersion")
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
            implementation("io.github.java-diff-utils:java-diff-utils:4.15")
            implementation("ch.qos.logback:logback-classic:1.5.32")
        }
    }
}
