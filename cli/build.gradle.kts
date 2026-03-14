plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.beryx.runtime")
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
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

runtime {
    additive = true
    modules = listOf("jdk.crypto.ec", "jdk.crypto.cryptoki")
    options = listOf("--compress", "zip-6", "--no-header-files", "--no-man-pages")

    jpackage {
        imageName = "acp2web"
        // jpackage requires numeric version (macOS additionally requires first segment > 0)
        val v = project.version.toString()
            .replace(Regex("^[^0-9]*"), "")       // strip leading non-digits (e.g. "v")
            .replace(Regex("[^0-9.].*"), "")       // strip everything after first non-numeric segment
        // macOS jpackage rejects versions starting with 0 — ensure first segment >= 1
        appVersion = v.ifEmpty { "1.0.0" }.replace(Regex("^0\\."), "1.")
    }
}

// Creates a single self-extracting executable that bundles the entire jpackage app-image.
// Extracts to ~/.local/share/acp2web/<version>/ on first run, skips if dir exists.
// Handles both Linux (acp2web/bin/acp2web) and macOS (acp2web.app/Contents/MacOS/acp2web) layouts.
tasks.register("packageExecutable") {
    description = "Creates a single self-extracting executable"
    group = "distribution"
    dependsOn("jpackageImage")

    val jpackageDir = layout.buildDirectory.dir("jpackage")
    val outputFile = layout.buildDirectory.file("dist/acp2web")
    inputs.dir(jpackageDir)
    outputs.file(outputFile)

    doLast {
        val distDir = outputFile.get().asFile.parentFile
        distDir.mkdirs()

        val jpDir = jpackageDir.get().asFile
        // Find the app image: "acp2web" on Linux, "acp2web.app" on macOS
        val appDir = jpDir.listFiles()?.firstOrNull { it.name.startsWith("acp2web") }
            ?: error("No jpackage output found in ${jpDir.absolutePath}")
        val isMacOS = appDir.name.endsWith(".app")

        // Launcher path relative to extracted dir
        val launcherPath = if (isMacOS) "Contents/MacOS/acp2web" else "bin/acp2web"

        val tarFile = File(distDir, "acp2web.tar.gz")
        ProcessBuilder("tar", "czf", tarFile.absolutePath, appDir.name)
            .directory(jpDir)
            .inheritIO()
            .start()
            .waitFor()

        val appVersion = project.version.toString()

        val headerTemplate = """
            |#!/bin/sh
            |set -e
            |BASE_DIR="${'$'}{XDG_DATA_HOME:-${'$'}HOME/.local/share}/acp2web"
            |INSTALL_DIR="${'$'}BASE_DIR/$appVersion"
            |if [ ! -d "${'$'}INSTALL_DIR" ]; then
            |  mkdir -p "${'$'}INSTALL_DIR"
            |  tail -c +ARCHIVE_OFFSET "${'$'}0" | tar xz -C "${'$'}INSTALL_DIR" --strip-components=1
            |fi
            |exec "${'$'}INSTALL_DIR/$launcherPath" "${'$'}@"
            |
        """.trimMargin()

        val placeholderLen = "ARCHIVE_OFFSET".length
        val headerWithPlaceholder = headerTemplate.toByteArray()
        val estimatedOffset = headerWithPlaceholder.size + 1
        val offsetStr = estimatedOffset.toString().padStart(placeholderLen, '0')
        val header = headerTemplate.replace("ARCHIVE_OFFSET", offsetStr)
        val headerBytes = header.toByteArray()
        assert(headerBytes.size == estimatedOffset) {
            "Header size mismatch: ${headerBytes.size} != $estimatedOffset"
        }

        val out = outputFile.get().asFile
        out.outputStream().use { os ->
            os.write(headerBytes)
            tarFile.inputStream().use { it.copyTo(os) }
        }
        out.setExecutable(true)
        tarFile.delete()

        logger.lifecycle("Self-extracting executable: ${out.absolutePath} (${out.length() / 1024 / 1024}MB)")
    }
}

// Windows self-extracting .bat — uses PowerShell + tar.exe (Windows 10 1803+).
// Extracts to %LOCALAPPDATA%\acp2web\<checksum>\ on first run, skips if dir exists.
tasks.register("packageExecutableWindows") {
    description = "Creates a single self-extracting .bat executable for Windows"
    group = "distribution"
    dependsOn("jpackageImage")

    val appImageDir = layout.buildDirectory.dir("jpackage/acp2web")
    val outputFile = layout.buildDirectory.file("dist/acp2web.bat")
    inputs.dir(appImageDir)
    outputs.file(outputFile)

    doLast {
        val distDir = outputFile.get().asFile.parentFile
        distDir.mkdirs()

        val tarFile = File(distDir, "acp2web-win.tar.gz")
        ProcessBuilder("tar", "czf", tarFile.absolutePath, "acp2web")
            .directory(layout.buildDirectory.dir("jpackage").get().asFile)
            .inheritIO()
            .start()
            .waitFor()

        val appVersion = project.version.toString()

        val headerTemplate = """
            |@echo off
            |setlocal
            |set "INSTALL_DIR=%LOCALAPPDATA%\acp2web\$appVersion"
            |if exist "%INSTALL_DIR%" goto :run
            |mkdir "%INSTALL_DIR%"
            |set "TGZFILE=%TEMP%\acp2web_%RANDOM%.tar.gz"
            |powershell -NoProfile -Command "${'$'}b=[IO.File]::ReadAllBytes('%~f0'); ${'$'}o=ARCHIVE_OFFSET; [IO.File]::WriteAllBytes('%TGZFILE%',${'$'}b[${'$'}o..(${'$'}b.Length-1)])"
            |tar xzf "%TGZFILE%" -C "%INSTALL_DIR%" --strip-components=1
            |del "%TGZFILE%"
            |:run
            |"%INSTALL_DIR%\bin\acp2web" %*
            |exit /b %ERRORLEVEL%
            |
        """.trimMargin()

        val placeholderLen = "ARCHIVE_OFFSET".length
        val headerWithPlaceholder = headerTemplate.toByteArray(Charsets.ISO_8859_1)
        val estimatedOffset = headerWithPlaceholder.size
        val offsetStr = estimatedOffset.toString().padStart(placeholderLen, '0')
        val header = headerTemplate.replace("ARCHIVE_OFFSET", offsetStr)
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)
        assert(headerBytes.size == estimatedOffset) {
            "Header size mismatch: ${headerBytes.size} != $estimatedOffset"
        }

        val out = outputFile.get().asFile
        out.outputStream().use { os ->
            os.write(headerBytes)
            tarFile.inputStream().use { it.copyTo(os) }
        }
        tarFile.delete()

        logger.lifecycle("Self-extracting Windows executable: ${out.absolutePath} (${out.length() / 1024 / 1024}MB)")
    }
}
