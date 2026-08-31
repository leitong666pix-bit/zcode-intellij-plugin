package zcode.idea.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用假进程（管道）验证 AppServerClient 的 JSON-RPC 分帧：
 * 响应分发、通知分发、server 请求应答。
 */
class AppServerClientTest {

    private class FakeProcess : Process() {
        val stdinSink = PipedInputStream()   // client 写入的
        val stdoutSource = PipedOutputStream() // 我们模拟 server 写出的
        val fakeStdout = PipedInputStream(stdoutSource)
        val stderrSource = PipedOutputStream()
        val fakeStderr = PipedInputStream(stderrSource)

        override fun getOutputStream() = PipedOutputStream(stdinSink)
        override fun getInputStream() = fakeStdout
        override fun getErrorStream() = fakeStderr
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            // 必须关闭写端：PipedInputStream 只有在 closedByWriter 时才会让阻塞的 read() 返回 EOF
            runCatching { stdoutSource.close() }
            runCatching { stderrSource.close() }
        }
    }

    private fun constructFake(fake: FakeProcess): AppServerClient {
        val ctor = AppServerClient::class.java.getDeclaredConstructor(Process::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fake)
    }

    @Test
    fun `request response dispatch`() {
        val fake = FakeProcess()
        val client = constructFake(fake)
        val future = client.request("session/create", JsonObject().apply { addProperty("x", 1) })
        // 读取 client 发出的一行并回包
        val line = fake.stdinSink.bufferedReader().readLine()
        val sent = JsonParser.parseString(line).asJsonObject
        assertEquals("session/create", sent.get("method").asString)
        val id = sent.get("id").asInt
        fake.stdoutSource.write("""{"id":$id,"result":{"ok":true}}""".toByteArray())
        fake.stdoutSource.write("\n".toByteArray())
        fake.stdoutSource.flush()
        val result = future.get(5, TimeUnit.SECONDS)
        assertTrue(result.get("ok").asBoolean)
        client.close()
        awaitReaderThreadsGone()
    }

    @Test
    fun `notification and server request routing`() {
        val fake = FakeProcess()
        val client = constructFake(fake)
        val notified = CountDownLatch(1)
        val requested = CountDownLatch(1)
        var responded: JsonObject? = null
        client.listener = object : AppServerClient.Listener {
            override fun onNotification(method: String, params: JsonObject?) {
                if (method == "session/event") notified.countDown()
            }

            override fun onRequest(id: String, method: String, params: JsonObject, responder: (JsonObject?) -> Unit) {
                requested.countDown()
                responder(JsonObject().apply { addProperty("decision", "deny") }).let {}
            }

            override fun onExited(code: Int?) {}
        }
        fake.stdoutSource.write("""{"method":"session/event","params":{"type":"turn.started"}}""".toByteArray())
        fake.stdoutSource.write("\n".toByteArray())
        fake.stdoutSource.write("""{"id":"server-1","method":"interaction/requestPermission","params":{"toolName":"Write"}}""".toByteArray())
        fake.stdoutSource.write("\n".toByteArray())
        fake.stdoutSource.flush()
        assertTrue(notified.await(5, TimeUnit.SECONDS))
        assertTrue(requested.await(5, TimeUnit.SECONDS))
        // client 应答写回 stdin
        val reply = fake.stdinSink.bufferedReader().readLine()
        val parsed = JsonParser.parseString(reply).asJsonObject
        assertEquals("server-1", parsed.get("id").asString)
        assertNotNull(parsed.get("result"))
        assertEquals("deny", parsed.getAsJsonObject("result").get("decision").asString)
        client.close()
        awaitReaderThreadsGone()
    }

    /** 等待 client 的后台线程退出（避免测试框架的线程泄漏断言误报）。 */
    private fun awaitReaderThreadsGone() {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val alive = Thread.getAllStackTraces().keys.any { it.name.startsWith("zcode-app-server") }
            if (!alive) return
            Thread.sleep(20)
        }
    }
}
