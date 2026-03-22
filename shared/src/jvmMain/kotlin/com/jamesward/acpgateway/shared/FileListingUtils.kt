package com.jamesward.acpgateway.shared

import java.io.File

/**
 * Lists project files under [cwd], respecting .gitignore via `git ls-files`.
 * Falls back to recursive directory listing if not a git repo.
 * Filters case-insensitively by [query] and returns at most [limit] results.
 */
fun listProjectFiles(cwd: String, query: String = "", limit: Int = 50): List<String> {
    val allFiles = try {
        val process = ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard")
            .directory(File(cwd))
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        val exitCode = process.waitFor()
        if (exitCode == 0) lines else fallbackFileList(cwd)
    } catch (_: Exception) {
        fallbackFileList(cwd)
    }

    if (query.isBlank()) return allFiles.take(limit)

    val lowerQuery = query.lowercase()
    return allFiles
        .filter { it.lowercase().contains(lowerQuery) }
        .take(limit)
}

private fun fallbackFileList(cwd: String): List<String> {
    val root = File(cwd)
    return root.walk()
        .filter { it.isFile }
        .map { it.relativeTo(root).path }
        .toList()
}
