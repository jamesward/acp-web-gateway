plugins {
    kotlin("multiplatform") version "2.3.10" apply false
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
    kotlin("plugin.power-assert") version "2.3.10" apply false
    kotlin("plugin.compose") version "2.3.10" apply false
    id("org.jetbrains.compose") version "1.11.0-alpha04" apply false
    id("io.ktor.plugin") version "3.4.1" apply false
    id("dev.kilua") version "0.0.32" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("dev.kilua.rpc") version "0.0.42" apply false
    id("org.graalvm.buildtools.native") version "0.10.6" apply false
    id("com.google.cloud.tools.jib") version "3.5.3" apply false
    id("com.palantir.git-version") version "5.0.0"
}

val gitVersion: groovy.lang.Closure<String> by extra
allprojects {
    version = try { gitVersion() } catch (_: Exception) { "unspecified" }
}

tasks.register("stage") {
    dependsOn(":server:installDist")
}
