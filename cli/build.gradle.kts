import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import java.nio.file.LinkOption
import kotlin.io.path.createLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.graalvm.buildtools.native")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.jamesward.acpgateway.cli.CliKt"
}

val ktorVersion = "3.4.1"

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "acp2web"
            mainClass = "com.jamesward.acpgateway.cli.CliKt"
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-Os",
                "--gc=epsilon",
                "-H:+UnlockExperimentalVMOptions",
                "-H:-IncludeMethodData",
                "--exclude-config", "kotlin-reflect-2\\..*\\.jar", "META-INF/native-image/.*",
            )
            if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
                buildArgs.add("-H:+StripDebugInfo")
            }
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
    metadataRepository {
        enabled = true
    }
    toolchainDetection = true
}


// fix for: https://github.com/gradle/gradle/issues/28583

fun fixSymlink(target: java.nio.file.Path, expectedSrc: java.nio.file.Path) {
    if (!expectedSrc.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
        logger.info("fixSymlink: expected is not regular, skip (expected: {})", expectedSrc)
        return
    }
    if (!target.isRegularFile(LinkOption.NOFOLLOW_LINKS) || target.fileSize() > 0) {
        logger.info("fixSymlink: target is not regular or the file size > 0, skip (target: {})", target)
        return
    }
    logger.warn("fixSymlink: {} -> {}", target, expectedSrc)
    target.deleteExisting()
    target.createLinkPointingTo(expectedSrc)
}

tasks.named("nativeCompile") {
    doFirst {
        val mainCompileOpt = project.extensions.getByType(GraalVMExtension::class).binaries["main"].asCompileOptions()
        val binPath = mainCompileOpt.javaLauncher.get().executablePath.asFile.toPath().parent
        val svmBinPath = binPath.resolve("../lib/svm/bin")
        fixSymlink(binPath.resolve("native-image"), svmBinPath.resolve("native-image"))
    }
}
