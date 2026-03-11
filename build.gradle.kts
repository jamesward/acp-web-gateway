plugins {
    kotlin("multiplatform") version "2.3.10" apply false
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
    kotlin("plugin.power-assert") version "2.3.10" apply false
    kotlin("plugin.compose") version "2.3.10" apply false
    id("org.jetbrains.compose") version "1.11.0-alpha02" apply false
    id("io.ktor.plugin") version "3.4.1" apply false
    id("dev.kilua") version "0.0.32" apply false
    id("com.google.devtools.ksp") version "2.3.5" apply false
    id("dev.kilua.rpc") version "0.0.42" apply false
}

tasks.register("stage") {
    dependsOn(":server:installDist")
}
