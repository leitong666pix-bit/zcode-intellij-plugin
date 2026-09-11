package zcode.idea.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import zcode.idea.settings.ZcodeSettings
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exercises the real service and reader with a fake stdio process; never launches CLI or reads credentials. */
class ZcodeSessionServiceTest {
    @org.junit.jupiter.api.io.TempDir lateinit var tempDir: java.nio.file.Path
    private lateinit var service: ZcodeSessionService
    private lateinit var client: AppServerClient
    private lateinit var process: FakeProcess
    private var previousApplication: Application? = null
    private val applicationField = ApplicationManager::class.java.getDeclaredField("ourApplication").apply { isAccessible = true }
    private val settings = ZcodeSettings()
    private val notices = CopyOnWriteArrayList<String>()
    private val deltas = CopyOnWriteArrayList<String>()
    private val modes = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val ui = LinkedBlockingQueue<Runnable>()
    @Volatile private var deferUi = false

    private class FakeProcess : Process() {
        val requests = LinkedBlockingQueue<JsonObject>()
        private val stdout = PipedOutputStream()
        private val stderr = PipedOutputStream()
        private val input = PipedInputStream(stdout, 65536)
        private val error = PipedInputStream(stderr)
        private val exited = CountDownLatch(1)
        private val output = object : OutputStream() {
            private val line = ByteArrayOutputStream()
            override fun write(value: Int) {
                if (value == 10) {
                    requests.add(JsonParser.parseString(line.toString(Charsets.UTF_8)).asJsonObject)
                    line.reset()
                } else line.write(value)
            }
        }
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun isAlive() = exited.count > 0
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException()
            return 0
        }
        override fun destroy() {
            exited.countDown()
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
        fun send(text: String) {
            stdout.write((text + "\n").toByteArray(Charsets.UTF_8))
            stdout.flush()
        }
    }

    @BeforeEach fun setUp() {
        previousApplication = applicationField.get(null) as? Application
        settings.state.defaultMode = "yolo"
        val app = Proxy.newProxyInstance(Application::class.java.classLoader, arrayOf(Application::class.java)) { _, method, args ->
            when (method.name) {
                "getService" -> if (args?.firstOrNull() == ZcodeSettings::class.java) settings else null
                "invokeLater" -> {
                    val runnable = args!![0] as Runnable
                    if (deferUi) ui.add(runnable) else runnable.run()
                    null
                }
                "isUnitTestMode", "isHeadlessEnvironment" -> true
                "isDisposed", "isDisposeInProgress" -> false
                "toString" -> "SessionTestApplication"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as Application
        applicationField.set(null, app)
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> System.getProperty("java.io.tmpdir")
                "isDisposed" -> false
                "toString" -> "SessionTestProject"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as Project
        service = ZcodeSessionService(project)
        process = FakeProcess()
        val constructor = AppServerClient::class.java.getDeclaredConstructor(Process::class.java).apply { isAccessible = true }
        client = constructor.newInstance(process)
        setField("client", client)
        setField("sessionId", "old")
        setField("state", ConnectionState.READY)
        setField("cliConfig", ZcodeCliConfig.CliConfig(emptyMap(), null))
        val listenerType = ZcodeSessionService::class.java.declaredClasses.single { it.simpleName == "ClientListener" }
        val listenerConstructor = listenerType.declaredConstructors.single().apply { isAccessible = true }
        client.listener = listenerConstructor.newInstance(service, client) as AppServerClient.Listener
        service.addListener(object : ZcodeSessionService.Listener {
            override fun onAssistantDelta(kind: AssistantDeltaKind, text: String) { deltas.add(text) }
            override fun onNotice(text: String, error: Boolean) { notices.add(text) }
            override fun onModeChanged(mode: String, pending: Boolean) { modes.add(mode to pending) }
        })
    }

    @AfterEach fun tearDown() {
        if (::service.isInitialized) service.dispose()
        applicationField.set(null, previousApplication)
    }

    private fun setField(name: String, value: Any?) {
        ZcodeSessionService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun tasks(): SessionTaskQueue =
        ZcodeSessionService::class.java.getDeclaredField("tasks").apply { isAccessible = true }.get(service) as SessionTaskQueue

    private fun idle() {
        val barrier = CompletableFuture<Unit>()
        tasks().execute { barrier.complete(Unit) }
        barrier.get(3, TimeUnit.SECONDS)
    }

    private fun next(method: String): JsonObject {
        val request = process.requests.poll(3, TimeUnit.SECONDS)
        assertNotNull(request, "Expected RPC " + method)
        assertEquals(method, request!!.get("method").asString)
        return request
    }

    private fun reply(request: JsonObject, result: String = "{}") {
        process.send("{\"id\":" + request.get("id") + ",\"result\":" + result + "}")
    }

    private fun reject(request: JsonObject, code: Int = -32602) {
        process.send("{\"id\":" + request.get("id") + ",\"error\":{\"code\":" + code + ",\"message\":\"rejected\"}}")
    }

    private fun delta(sid: String, text: String) {
        process.send("{\"method\":\"session/event\",\"params\":{\"sessionId\":\"" + sid +
            "\",\"type\":\"model.streaming\",\"payload\":{\"kind\":\"text_delta\",\"delta\":\"" + text + "\"}}}")
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }

    @Test fun modelRefreshDoesNotBlockRpcReader() {
        service.selectModel(ModelOption("provider", "model", "Model"))
        reply(next("session/setModel"))
        val refresh = next("workspace/readState")
        // While the session worker awaits refresh, the reader must still complete unrelated RPCs.
        val ping = client.request("ping")
        reply(next("ping"))
        ping.get(1, TimeUnit.SECONDS)
        reply(refresh, """{"settings":{"thoughtLevel":{"available":[{"value":"high","label":"High"}],"current":"high"}}}""")
        waitUntil { service.currentThoughtLevel == "high" }
        assertEquals("model", service.currentModel?.modelId)
    }

    @Test fun snapshotIsCapturedBeforeWriteEvenWhileWorkerWaitsForRpc() {
        val file = tempDir.resolve("source.txt").toFile().apply { writeText("before") }
        service.selectModel(ModelOption("provider", "model", "Model"))
        val model = next("session/setModel")
        val event = JsonObject().apply {
            addProperty("method", "session/event")
            add("params", JsonObject().apply {
                addProperty("sessionId", "old")
                addProperty("type", "model.streaming")
                add("payload", JsonObject().apply {
                    addProperty("kind", "tool_call")
                    addProperty("toolCallId", "edit-1")
                    addProperty("toolName", "Edit")
                    add("input", JsonObject().apply { addProperty("file_path", file.path) })
                })
            })
        }
        process.send(event.toString())
        val ping = client.request("ping")
        reply(next("ping"))
        ping.get(1, TimeUnit.SECONDS)
        file.writeText("after")
        reply(model)
        reply(next("workspace/readState"))
        process.send("""{"method":"session/event","params":{"sessionId":"old","type":"tool.updated","payload":{"kind":"result","toolCallId":"edit-1","result":{"success":true}}}}""")
        waitUntil { service.changedFilesSnapshot().isNotEmpty() }
        assertEquals("before", service.changedFilesSnapshot().single().oldContent)
    }

    @Test fun rejectedSendDoesNotStrandNextMessage() {
        service.sendCommand("first", "first")
        val first = next("session/send")
        service.sendCommand("second", "second")
        reject(first)
        val second = next("session/send")
        assertEquals("second", second.getAsJsonObject("params").get("content").asString)
        reply(second)
        idle()
        assertTrue(notices.any { it.contains("rejected") })
    }

    @Test fun uncertainSendQueriesStateAndDoesNotReplayOrStopRunningTurn() {
        service.sendCommand("first", "first")
        val first = next("session/send")
        service.sendCommand("second", "second")
        val pendingField = AppServerClient::class.java.getDeclaredField("pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(client) as Map<Int, CompletableFuture<JsonObject>>
        pending[first.get("id").asInt]!!.completeExceptionally(TimeoutException("simulated timeout"))
        reply(next("session/subscribe"), """{"snapshot":{"session":{"status":"running"}}}""")
        waitUntil { notices.any { it.contains("尚未确认") } }
        idle()
        assertEquals(ConnectionState.RUNNING, service.state)
        assertTrue(process.requests.isEmpty(), "Must not resend or stop a possibly accepted prompt")
        process.send("""{"method":"session/event","params":{"sessionId":"old","type":"turn.completed","payload":{}}}""")
        reply(next("session/subscribe"))
        val second = next("session/send")
        assertEquals("second", second.getAsJsonObject("params").get("content").asString)
        reply(second)
    }

    @Test fun failedModeChangeKeepsConfirmedMode() {
        service.setMode("build")
        val request = next("session/setMode")
        assertEquals("yolo", service.currentMode())
        reject(request)
        waitUntil { modes.contains("yolo" to false) }
        assertEquals("yolo", service.currentMode())
        assertTrue(notices.any { it.contains("切换权限模式失败") })
        service.setMode("build")
        reply(next("session/setMode"))
        waitUntil { service.currentMode() == "build" }
    }

    @Test fun uncertainModeChangeUsesActualServerModeBeforeReleasingSends() {
        service.setMode("build")
        val request = next("session/setMode")
        service.sendCommand("queued", "queued")
        val pendingField = AppServerClient::class.java.getDeclaredField("pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(client) as Map<Int, CompletableFuture<JsonObject>>
        pending[request.get("id").asInt]!!.completeExceptionally(TimeoutException("mode timeout"))
        reply(next("session/subscribe"), """{"snapshot":{"session":{"mode":"build","status":"idle"}}}""")
        val send = next("session/send")
        assertEquals("build", service.currentMode())
        reply(send)
    }

    @Test fun resumeFiltersOldEventsAndLoadsActualPermissionMode() {
        val restored = CountDownLatch(1)
        service.resumeSession("new", { restored.countDown() }, { fail(it) })
        reply(next("session/close"))
        val resume = next("session/resume")
        delta("old", "must be dropped")
        reply(resume, """{"session":{"sessionId":"new","status":"idle","mode":"build"}}""")
        reply(next("session/subscribe"), """{"snapshot":{"session":{"sessionId":"new","status":"idle","mode":"build"}}}""")
        reply(next("session/read"), """{"messages":[]}""")
        assertTrue(restored.await(3, TimeUnit.SECONDS))
        delta("old", "still old")
        delta("new", "current")
        waitUntil { deltas.contains("current") }
        assertEquals(listOf("current"), deltas.toList())
        assertEquals("build", service.currentMode())
        assertEquals("new", service.sessionId)
    }

    @Test fun stopCancelsAnUnacknowledgedSendWithoutWaitingForItsTimeout() {
        service.sendCommand("first", "first")
        val first = next("session/send")
        service.stopCurrentTurn()
        reply(next("session/stop"))
        waitUntil { service.state == ConnectionState.READY }
        reply(first) // Late send acknowledgement must not restart the stopped turn.
        idle()
        assertEquals(ConnectionState.READY, service.state)
        assertTrue(process.requests.isEmpty())
    }

    @Test fun modeChangeHoldsSendsUntilAcknowledged() {
        service.setMode("build")
        val mode = next("session/setMode")
        service.sendCommand("queued", "queued")
        idle()
        assertTrue(process.requests.isEmpty())
        reply(mode)
        val send = next("session/send")
        assertEquals("queued", send.getAsJsonObject("params").get("content").asString)
        assertEquals("build", service.currentMode())
        reply(send)
    }

    @Test fun newSessionOnExistingConnectionBecomesReady() {
        setField("state", ConnectionState.RUNNING)
        service.newSession()
        reply(next("session/close"))
        reply(next("session/create"), """{"session":{"sessionId":"fresh","status":"idle","mode":"yolo"}}""")
        reply(next("session/subscribe"), """{"snapshot":{"session":{"sessionId":"fresh","status":"idle","mode":"yolo"}}}""")
        waitUntil { service.state == ConnectionState.READY && service.sessionId == "fresh" }
        service.sendCommand("fresh prompt", "fresh prompt")
        val request = next("session/send")
        assertEquals("fresh", request.getAsJsonObject("params").get("sessionId").asString)
        reply(request)
    }

    @Test fun staleUiCallbacksAreDroppedAfterSwitch() {
        deferUi = true
        delta("old", "stale UI")
        waitUntil { ui.isNotEmpty() }
        tasks().invalidate()
        while (true) (ui.poll() ?: break).run()
        assertTrue(deltas.isEmpty())
    }

    @Test fun approvalForAnotherSessionIsDeniedWithoutDialog() {
        process.send("""{"id":"permission-1","method":"interaction/requestPermission","params":{"sessionId":"other","toolName":"Write"}}""")
        val response = process.requests.poll(3, TimeUnit.SECONDS)
        assertNotNull(response)
        assertEquals("deny", response!!.getAsJsonObject("result").get("decision").asString)
    }
}
