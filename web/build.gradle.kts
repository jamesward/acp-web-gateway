plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.power-assert")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("org.jetbrains.kotlin-wrappers:kotlin-browser:2026.3.8")
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Fix task dependency ordering between development and production WASM builds
tasks.configureEach {
    if (name == "wasmJsProductionExecutableCompileSync") {
        mustRunAfter(tasks.named("wasmJsBrowserDevelopmentWebpack"))
    }
}
