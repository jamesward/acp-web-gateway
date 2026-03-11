package com.jamesward.acpgateway.shared

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@OptIn(UnstableApi::class)
fun buildSimulationResponse(): suspend () -> Flow<Event> = {
    flow {
        // ── Phase 1: Extended thinking (30s) ──
        val thinkingChunks = listOf(
            "Let me ",
            "think about this carefully. ",
            "Kotlin coroutines are a broad topic ",
            "covering structured concurrency, ",
            "suspend functions, flows, channels, ",
            "and error handling patterns. ",
            "I'll organize this into a comprehensive guide ",
            "starting with fundamentals, ",
            "then moving to intermediate concepts, ",
            "and finally covering advanced patterns ",
            "like custom coroutine contexts, ",
            "supervision, and backpressure handling. ",
            "Let me also find some relevant source files ",
            "to ground the examples in real code.",
        )
        for (chunk in thinkingChunks) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text(chunk))))
            delay(333)
        }

        // ── Phase 2: Read a file (5s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-read-1"),
            title = "Read src/main/kotlin/Example.kt",
            kind = com.agentclientprotocol.model.ToolKind.READ,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("src/main/kotlin/Example.kt")),
        )))
        delay(500)
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-read-1"),
            title = "Read src/main/kotlin/Example.kt",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Content(ContentBlock.Text(
                "package com.example\n\nimport kotlinx.coroutines.*\n\nfun main() = runBlocking {\n    println(\"Hello from coroutines!\")\n    launch {\n        delay(1000)\n        println(\"World!\")\n    }\n    println(\"Waiting...\")\n}"
            ))),
        )))
        delay(333)

        // Usage update after initial thinking + read
        emit(Event.SessionUpdateEvent(SessionUpdate.UsageUpdate(
            used = 12400,
            size = 200000,
            cost = Cost(amount = 0.12, currency = "USD"),
        )))

        // ── Phase 3: First response section — Fundamentals (30s) ──
        val fundamentals = listOf(
            "# Kotlin Coroutines — A Comprehensive Guide\n\n",
            "## 1. Fundamentals\n\n",
            "Kotlin coroutines are a ",
            "**lightweight concurrency framework** ",
            "built into the language. ",
            "Unlike threads, coroutines are ",
            "incredibly cheap to create — ",
            "you can launch thousands of them ",
            "without running out of memory.\n\n",
            "### What is a Coroutine?\n\n",
            "A coroutine is a ",
            "**suspendable computation**. ",
            "It can be paused at any ",
            "`suspend` point and resumed later, ",
            "potentially on a different thread. ",
            "This is fundamentally different from threads, ",
            "which are managed by the OS.\n\n",
            "### Coroutine Builders\n\n",
            "There are several ways to start a coroutine:\n\n",
            "1. **`launch`** — Fire-and-forget. ",
            "Returns a `Job` that can be cancelled.\n",
            "2. **`async`** — Returns a `Deferred<T>` ",
            "whose result can be awaited.\n",
            "3. **`runBlocking`** — Bridges the ",
            "blocking and non-blocking worlds.\n",
            "4. **`coroutineScope`** — Creates a scope ",
            "that waits for all children to complete.\n\n",
        )
        for (chunk in fundamentals) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(167)
        }

        // ── Phase 4: Read build config (5s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-read-2"),
            title = "Read build.gradle.kts",
            kind = com.agentclientprotocol.model.ToolKind.READ,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("build.gradle.kts")),
        )))
        delay(500)
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-read-2"),
            title = "Read build.gradle.kts",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Content(ContentBlock.Text(
                "dependencies {\n    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0\")\n    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0\")\n    testImplementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0\")\n}"
            ))),
        )))
        delay(333)

        // More thinking
        val moreThinking = listOf(
            "\n\nNow let me consider ",
            "the structured concurrency model ",
            "and how it relates to error handling. ",
            "This is crucial for production code.",
        )
        for (chunk in moreThinking) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentThoughtChunk(ContentBlock.Text(chunk))))
            delay(250)
        }

        // ── Phase 5: Suspend functions section (25s) ──
        val suspendSection = listOf(
            "## 2. Suspend Functions\n\n",
            "A `suspend` function is the basic building block. ",
            "It can call other suspend functions ",
            "and will pause execution without blocking a thread.\n\n",
            "```kotlin\n",
            "suspend fun fetchUserProfile(userId: String): UserProfile {\n",
            "    val user = userService.getUser(userId)    // suspends\n",
            "    val prefs = prefService.getPrefs(userId)  // suspends\n",
            "    return UserProfile(user, prefs)\n",
            "}\n",
            "```\n\n",
            "**Key insight**: The two service calls above ",
            "run **sequentially**. Each `suspend` call ",
            "pauses the coroutine until the result is ready, ",
            "but it doesn't block the underlying thread.\n\n",
            "### Making Calls Concurrent\n\n",
            "To run both calls concurrently, use `async`:\n\n",
            "```kotlin\n",
            "suspend fun fetchUserProfile(userId: String): UserProfile = coroutineScope {\n",
            "    val user = async { userService.getUser(userId) }\n",
            "    val prefs = async { prefService.getPrefs(userId) }\n",
            "    UserProfile(user.await(), prefs.await())\n",
            "}\n",
            "```\n\n",
            "Now both calls happen simultaneously, ",
            "roughly halving the total time.\n\n",
        )
        for (chunk in suspendSection) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(167)
        }

        // ── Phase 6: Write a file (8s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-write-1"),
            title = "Write src/main/kotlin/CoroutineBasics.kt",
            kind = com.agentclientprotocol.model.ToolKind.EDIT,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("src/main/kotlin/CoroutineBasics.kt")),
        )))
        delay(667)

        val basicsFile = """package com.example

import kotlinx.coroutines.*

// Demonstrates basic coroutine patterns
suspend fun fetchData(id: Int): String {
    delay(1000) // simulate network call
    return "Data for id=${'$'}id"
}

suspend fun processAll(ids: List<Int>): List<String> = coroutineScope {
    ids.map { id ->
        async { fetchData(id) }
    }.awaitAll()
}

fun main() = runBlocking {
    println("Fetching data concurrently...")
    val start = System.currentTimeMillis()
    val results = processAll((1..10).toList())
    val elapsed = System.currentTimeMillis() - start
    results.forEach { println(it) }
    println("Completed in ${'$'}{elapsed}ms (should be ~1000ms, not ~10000ms)")
}"""

        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-write-1"),
            title = "Write src/main/kotlin/CoroutineBasics.kt",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Diff(
                path = "src/main/kotlin/CoroutineBasics.kt",
                oldText = null,
                newText = basicsFile,
            )),
        )))
        delay(333)

        // ── Phase 7: Structured Concurrency section (30s) ──
        val structuredSection = listOf(
            "## 3. Structured Concurrency\n\n",
            "Structured concurrency is Kotlin's approach ",
            "to managing coroutine lifecycles. ",
            "Every coroutine must be launched in a **scope**, ",
            "and when a scope is cancelled, ",
            "all its children are cancelled too.\n\n",
            "### Why It Matters\n\n",
            "Without structured concurrency, you get:\n",
            "- **Leaked coroutines** that run forever\n",
            "- **Lost exceptions** that nobody handles\n",
            "- **Resource leaks** from forgotten cleanup\n\n",
            "### The Scope Hierarchy\n\n",
            "```\n",
            "runBlocking          (top-level, blocks thread)\n",
            "  └─ coroutineScope  (suspends, doesn't block)\n",
            "       ├─ launch     (child coroutine 1)\n",
            "       └─ async      (child coroutine 2)\n",
            "```\n\n",
            "If any child fails, the scope cancels ",
            "all other children and re-throws the exception. ",
            "This ensures you never have half-finished work ",
            "lingering in the background.\n\n",
            "### SupervisorScope\n\n",
            "Sometimes you want children to fail independently. ",
            "`supervisorScope` allows one child to fail ",
            "without cancelling siblings:\n\n",
            "```kotlin\n",
            "supervisorScope {\n",
            "    launch { riskyOperation1() }  // if this fails...\n",
            "    launch { riskyOperation2() }  // ...this keeps running\n",
            "}\n",
            "```\n\n",
        )
        for (chunk in structuredSection) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(150)
        }

        // ── Phase 8: Run command (8s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-run-1"),
            title = "Run ./gradlew :app:run",
            kind = com.agentclientprotocol.model.ToolKind.EXECUTE,
            status = ToolCallStatus.IN_PROGRESS,
        )))
        delay(833)
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-run-1"),
            title = "Run ./gradlew :app:run",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Content(ContentBlock.Text(
                "Fetching data concurrently...\nData for id=1\nData for id=2\nData for id=3\nData for id=4\nData for id=5\nData for id=6\nData for id=7\nData for id=8\nData for id=9\nData for id=10\nCompleted in 1023ms (should be ~1000ms, not ~10000ms)"
            ))),
        )))
        delay(500)

        // Usage update mid-turn
        emit(Event.SessionUpdateEvent(SessionUpdate.UsageUpdate(
            used = 87500,
            size = 200000,
            cost = Cost(amount = 0.68, currency = "USD"),
        )))

        // ── Phase 9: Flows section (25s) ──
        val flowSection = listOf(
            "## 4. Kotlin Flows\n\n",
            "Flows are the coroutine equivalent of ",
            "reactive streams. They represent a ",
            "**cold, asynchronous sequence** of values.\n\n",
            "### Basic Flow\n\n",
            "```kotlin\n",
            "fun numberFlow(): Flow<Int> = flow {\n",
            "    for (i in 1..5) {\n",
            "        delay(500)\n",
            "        emit(i)\n",
            "    }\n",
            "}\n",
            "```\n\n",
            "### Flow Operators\n\n",
            "Flows support familiar operators:\n\n",
            "- **`map`** — Transform each element\n",
            "- **`filter`** — Keep matching elements\n",
            "- **`take`** — Limit the number of elements\n",
            "- **`collect`** — Terminal operator that processes values\n",
            "- **`toList`** — Collect all values into a list\n",
            "- **`reduce`** / **`fold`** — Aggregate values\n\n",
            "### StateFlow & SharedFlow\n\n",
            "For hot streams (always active), use:\n",
            "- **`StateFlow`** — Holds a single updatable value, ",
            "like `LiveData` but coroutine-native\n",
            "- **`SharedFlow`** — Broadcasts values ",
            "to multiple collectors\n\n",
        )
        for (chunk in flowSection) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(167)
        }

        // ── Phase 10: Write advanced example (8s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-write-2"),
            title = "Write src/main/kotlin/FlowExample.kt",
            kind = com.agentclientprotocol.model.ToolKind.EDIT,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("src/main/kotlin/FlowExample.kt")),
        )))
        delay(667)

        val flowFile = """package com.example

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Temperature sensor simulation using Flow
fun temperatureReadings(): Flow<Double> = flow {
    val baseTemp = 20.0
    var drift = 0.0
    while (true) {
        drift += (-0.5..0.5).random()
        emit(baseTemp + drift + (-1.0..1.0).random())
        delay(200)
    }
}

fun main() = runBlocking {
    println("Monitoring temperature (10 readings)...")

    temperatureReadings()
        .map { temp -> "%.1f°C".format(temp) }
        .take(10)
        .collect { reading ->
            println("  Sensor: ${'$'}reading")
        }

    println("Done monitoring.")
}"""

        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-write-2"),
            title = "Write src/main/kotlin/FlowExample.kt",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Diff(
                path = "src/main/kotlin/FlowExample.kt",
                oldText = null,
                newText = flowFile,
            )),
        )))
        delay(333)

        // ── Phase 11: Error handling section (20s) ──
        val errorSection = listOf(
            "## 5. Error Handling\n\n",
            "Coroutine error handling follows ",
            "structured concurrency rules:\n\n",
            "### Try-Catch in Coroutines\n\n",
            "```kotlin\n",
            "val result = runCatching {\n",
            "    fetchRemoteData()\n",
            "}.getOrElse { error ->\n",
            "    logger.warn(\"Fetch failed: ${'$'}{error.message}\")\n",
            "    cachedData() // fallback\n",
            "}\n",
            "```\n\n",
            "### Exception Propagation\n\n",
            "- **`launch`** — Exceptions propagate to the parent scope\n",
            "- **`async`** — Exceptions are stored in the `Deferred` ",
            "and thrown when `await()` is called\n\n",
            "### CoroutineExceptionHandler\n\n",
            "For top-level uncaught exceptions:\n\n",
            "```kotlin\n",
            "val handler = CoroutineExceptionHandler { _, exception ->\n",
            "    logger.error(\"Unhandled: ${'$'}{exception.message}\")\n",
            "}\n",
            "\n",
            "scope.launch(handler) {\n",
            "    riskyOperation()\n",
            "}\n",
            "```\n\n",
        )
        for (chunk in errorSection) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(133)
        }

        // ── Phase 12: Read test file (5s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-read-3"),
            title = "Read src/test/kotlin/CoroutineTest.kt",
            kind = com.agentclientprotocol.model.ToolKind.READ,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("src/test/kotlin/CoroutineTest.kt")),
        )))
        delay(500)
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-read-3"),
            title = "Read src/test/kotlin/CoroutineTest.kt",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Content(ContentBlock.Text(
                "class CoroutineTest {\n    @Test\n    fun `concurrent fetch completes in bounded time`() = runTest {\n        val results = processAll((1..100).toList())\n        assertEquals(100, results.size)\n    }\n\n    @Test\n    fun `cancellation propagates to children`() = runTest {\n        val job = launch {\n            repeat(1000) { i ->\n                delay(100)\n                println(\"Working ${'$'}i\")\n            }\n        }\n        delay(350)\n        job.cancel()\n        assertTrue(job.isCancelled)\n    }\n}"
            ))),
        )))
        delay(333)

        // ── Phase 13: Advanced patterns section (25s) ──
        val advancedSection = listOf(
            "## 6. Advanced Patterns\n\n",
            "### Channels\n\n",
            "Channels are like blocking queues ",
            "but for coroutines. They're useful ",
            "for producer-consumer patterns:\n\n",
            "```kotlin\n",
            "val channel = Channel<Int>(capacity = 10)\n",
            "\n",
            "launch {\n",
            "    for (i in 1..100) {\n",
            "        channel.send(i)  // suspends if buffer full\n",
            "    }\n",
            "    channel.close()\n",
            "}\n",
            "\n",
            "for (value in channel) {\n",
            "    process(value)  // suspends if buffer empty\n",
            "}\n",
            "```\n\n",
            "### Mutex & Semaphore\n\n",
            "For coroutine-safe shared state:\n\n",
            "```kotlin\n",
            "val mutex = Mutex()\n",
            "var counter = 0\n",
            "\n",
            "coroutineScope {\n",
            "    repeat(1000) {\n",
            "        launch {\n",
            "            mutex.withLock { counter++ }\n",
            "        }\n",
            "    }\n",
            "}\n",
            "// counter == 1000, guaranteed\n",
            "```\n\n",
            "### Custom Dispatchers\n\n",
            "You can create dispatchers ",
            "for specific use cases:\n\n",
            "```kotlin\n",
            "val dbDispatcher = Dispatchers.IO.limitedParallelism(4)\n",
            "val cpuDispatcher = Dispatchers.Default.limitedParallelism(2)\n",
            "```\n\n",
        )
        for (chunk in advancedSection) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(117)
        }

        // ── Phase 14: Write test file (8s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-write-3"),
            title = "Write src/test/kotlin/AdvancedCoroutineTest.kt",
            kind = com.agentclientprotocol.model.ToolKind.EDIT,
            status = ToolCallStatus.IN_PROGRESS,
            locations = listOf(ToolCallLocation("src/test/kotlin/AdvancedCoroutineTest.kt")),
        )))
        delay(667)

        val testFile = """package com.example

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class AdvancedCoroutineTest {

    @Test
    fun `supervisor scope isolates failures`() = runTest {
        val results = mutableListOf<String>()

        supervisorScope {
            launch {
                results.add("task1-start")
                delay(100)
                results.add("task1-done")
            }
            launch {
                results.add("task2-start")
                throw RuntimeException("task2 failed")
            }
            launch {
                results.add("task3-start")
                delay(200)
                results.add("task3-done")
            }
        }

        assertTrue("task1-done" in results)
        assertTrue("task3-done" in results)
        assertFalse("task2-done" in results)
    }

    @Test
    fun `flow backpressure works correctly`() = runTest {
        val collected = mutableListOf<Int>()

        flow {
            repeat(100) { emit(it) }
        }
        .buffer(10)
        .collect {
            delay(10)
            collected.add(it)
        }

        assertEquals(100, collected.size)
    }
}"""

        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-write-3"),
            title = "Write src/test/kotlin/AdvancedCoroutineTest.kt",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Diff(
                path = "src/test/kotlin/AdvancedCoroutineTest.kt",
                oldText = null,
                newText = testFile,
            )),
        )))
        delay(333)

        // ── Phase 15: Run tests (8s) ──
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCall(
            toolCallId = ToolCallId("tc-run-2"),
            title = "Run ./gradlew test",
            kind = com.agentclientprotocol.model.ToolKind.EXECUTE,
            status = ToolCallStatus.IN_PROGRESS,
        )))
        delay(833)
        emit(Event.SessionUpdateEvent(SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tc-run-2"),
            title = "Run ./gradlew test",
            status = ToolCallStatus.COMPLETED,
            content = listOf(ToolCallContent.Content(ContentBlock.Text(
                "> Task :test\n\ncom.example.AdvancedCoroutineTest\n  ✓ supervisor scope isolates failures (45ms)\n  ✓ flow backpressure works correctly (1023ms)\n\ncom.example.CoroutineTest\n  ✓ concurrent fetch completes in bounded time (1015ms)\n  ✓ cancellation propagates to children (362ms)\n\n4 tests completed, 0 failed\n\nBUILD SUCCESSFUL in 8s"
            ))),
        )))
        delay(500)

        // Final usage update
        emit(Event.SessionUpdateEvent(SessionUpdate.UsageUpdate(
            used = 156000,
            size = 200000,
            cost = Cost(amount = 1.24, currency = "USD"),
        )))

        // ── Phase 16: Summary section (15s) ──
        val summary = listOf(
            "## Summary\n\n",
            "Here's what we covered and created:\n\n",
            "### Files Created\n",
            "- `src/main/kotlin/CoroutineBasics.kt` — ",
            "Concurrent data fetching with `async`/`awaitAll`\n",
            "- `src/main/kotlin/FlowExample.kt` — ",
            "Temperature sensor simulation using `Flow`\n",
            "- `src/test/kotlin/AdvancedCoroutineTest.kt` — ",
            "Tests for supervisor scope and flow backpressure\n\n",
            "### Key Takeaways\n\n",
            "1. **Coroutines are cheap** — ",
            "Launch thousands without worry\n",
            "2. **Structured concurrency prevents leaks** — ",
            "Scopes manage lifecycle automatically\n",
            "3. **`async`/`awaitAll` for concurrent calls** — ",
            "Run independent operations in parallel\n",
            "4. **Flows for async sequences** — ",
            "Cold streams with backpressure support\n",
            "5. **`supervisorScope` for fault isolation** — ",
            "Let siblings survive independent failures\n",
            "6. **`runTest` for testing** — ",
            "Virtual time makes tests fast and deterministic\n\n",
            "All tests pass. ",
            "Let me know if you'd like to explore ",
            "any of these topics in more depth, ",
            "or if you have a specific use case ",
            "you'd like me to help implement!",
        )
        for (chunk in summary) {
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            delay(100)
        }

        // End turn
        emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN)))
    }
}
