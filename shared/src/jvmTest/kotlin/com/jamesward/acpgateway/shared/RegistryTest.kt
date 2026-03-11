package com.jamesward.acpgateway.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RegistryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseRegistryResponse() {
        val jsonStr = """
        {
            "version": "1.0.0",
            "agents": [
                {
                    "id": "test-agent",
                    "name": "Test Agent",
                    "version": "1.0.0",
                    "description": "A test agent",
                    "distribution": {
                        "npx": {
                            "package": "@test/agent@1.0.0"
                        }
                    }
                }
            ]
        }
        """.trimIndent()

        val registry = json.decodeFromString<RegistryResponse>(jsonStr)
        assertEquals("1.0.0", registry.version)
        assertEquals(1, registry.agents.size)
        assertEquals("test-agent", registry.agents[0].id)
        assertNotNull(registry.agents[0].distribution.npx)
        assertEquals("@test/agent@1.0.0", registry.agents[0].distribution.npx!!.packageName)
    }

    @Test
    fun resolveNpxAgent() {
        val agent = RegistryAgent(
            id = "test",
            name = "Test",
            version = "1.0.0",
            distribution = Distribution(npx = NpxDistribution(packageName = "@test/pkg@1.0.0")),
        )
        val cmd = resolveAgentCommand(agent)
        assertEquals("npx", cmd.command)
        assertEquals(listOf("-y", "@test/pkg@1.0.0"), cmd.args)
    }

    @Test
    fun resolveUvxAgent() {
        val agent = RegistryAgent(
            id = "test",
            name = "Test",
            version = "1.0.0",
            distribution = Distribution(uvx = UvxDistribution(packageName = "test-pkg")),
        )
        val cmd = resolveAgentCommand(agent)
        assertEquals("uvx", cmd.command)
        assertEquals(listOf("test-pkg"), cmd.args)
    }

    @Test
    fun parseBinaryDistribution() {
        val jsonStr = """
        {
            "version": "1.0.0",
            "agents": [
                {
                    "id": "bin-agent",
                    "name": "Binary Agent",
                    "version": "0.1.0",
                    "distribution": {
                        "binary": {
                            "linux-x86_64": {
                                "archive": "https://example.com/agent.tar.gz",
                                "cmd": "./agent"
                            },
                            "darwin-aarch64": {
                                "archive": "https://example.com/agent-mac.tar.gz",
                                "cmd": "./agent"
                            }
                        }
                    }
                }
            ]
        }
        """.trimIndent()

        val registry = json.decodeFromString<RegistryResponse>(jsonStr)
        val agent = registry.agents[0]
        assertNotNull(agent.distribution.binary)
        assertEquals(2, agent.distribution.binary!!.size)
        assertEquals("./agent", agent.distribution.binary!!["linux-x86_64"]!!.cmd)
    }
}
