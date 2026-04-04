plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.power-assert")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.kilua")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        useEsModules()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled = true
                }
                outputFileName = "web.js"
            }
        }
        binaries.executable()
        compilerOptions {
            target.set("es2015")
            freeCompilerArgs.add("-Xpartial-linkage-loglevel=ERROR")
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation("dev.kilua:kilua:0.0.32")
            implementation("dev.kilua:kilua-rpc-core:0.0.42")
            implementation("dev.kilua:kilua-marked:0.0.32")
            implementation("io.ktor:ktor-client-core:3.4.2")
            implementation("io.ktor:ktor-client-js-wasm-js:3.4.2")
            implementation(npm("html2canvas", "1.4.1"))
            implementation(npm("highlight.js", "11.11.1"))
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Fix task dependency ordering between development and production WASM builds
//tasks.configureEach {
//    if (name == "wasmJsProductionExecutableCompileSync") {
//        mustRunAfter(tasks.named("wasmJsBrowserDevelopmentWebpack"))
//    }
//}
