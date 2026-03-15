package com.jamesward.acpgateway.shared

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val DEFAULT_REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"

@Serializable
data class RegistryResponse(
    val version: String,
    val agents: List<RegistryAgent>,
)

@Serializable
data class RegistryAgent(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val icon: String? = null,
    val args: List<String> = emptyList(),
    val distribution: Distribution = Distribution(),
)

@Serializable
data class Distribution(
    val npx: NpxDistribution? = null,
    val uvx: UvxDistribution? = null,
    val binary: Map<String, BinaryPlatform>? = null,
)

@Serializable
data class NpxDistribution(
    @SerialName("package") val packageName: String,
    val args: List<String> = emptyList(),
)

@Serializable
data class UvxDistribution(
    @SerialName("package") val packageName: String,
    val args: List<String> = emptyList(),
)

@Serializable
data class BinaryPlatform(
    val archive: String,
    val cmd: String,
)

data class ProcessCommand(
    val command: String,
    val args: List<String>,
    val env: Map<String, String> = emptyMap(),
)

suspend fun fetchRegistry(url: String = DEFAULT_REGISTRY_URL): List<RegistryAgent> {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    return client.use { it.get(url).body<RegistryResponse>().agents }
}

fun resolveAgentCommand(agent: RegistryAgent): ProcessCommand {
    val dist = agent.distribution
    return when {
        dist.npx != null -> ProcessCommand(
            command = "npx",
            args = listOf("-y", dist.npx.packageName) + dist.npx.args + agent.args,
        )
        dist.uvx != null -> ProcessCommand(
            command = "uvx",
            args = listOf(dist.uvx.packageName) + dist.uvx.args + agent.args,
        )
        dist.binary != null -> {
            val platform = detectPlatformKey()
            val bin = dist.binary[platform]
                ?: error("No binary for platform '$platform'. Available: ${dist.binary.keys}")
            // For binary distributions, the archive needs to be downloaded first.
            // For now, assume the cmd is available on PATH or use npx/uvx instead.
            ProcessCommand(command = bin.cmd, args = agent.args)
        }
        else -> error("No distribution found for agent ${agent.id}")
    }
}

/**
 * Parses a command string (e.g. "kiro-cli acp") into a ProcessCommand by splitting on whitespace.
 */
fun parseCommandString(commandString: String): ProcessCommand {
    val parts = commandString.trim().split("\\s+".toRegex())
    require(parts.isNotEmpty() && parts[0].isNotEmpty()) { "Agent command must not be empty" }
    return ProcessCommand(command = parts[0], args = parts.drop(1))
}

private fun detectPlatformKey(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osKey = when {
        os.contains("mac") || os.contains("darwin") -> "darwin"
        os.contains("linux") -> "linux"
        os.contains("windows") -> "windows"
        else -> os
    }
    val archKey = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
        arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
        else -> arch
    }
    return "$osKey-$archKey"
}
