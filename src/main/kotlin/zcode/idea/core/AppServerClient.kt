package zcode.idea.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class RpcException(val code: Int, message: String, val data: Any?) : Exception(message)

/**
 * 与 `node zcode.cjs app-server` 的 stdio 通道。
 * 协议：每行一个 JSON（见 docs/PROTOCOL.md）。
 *  - client→server 请求： {"id":n,"method":"...","params":{...}} → {"id":n,"result":...|error:{code,message,data}}
 *  - server→client 通知： {"method":"...","params":{...}}
 *  - server→client 请求： {"id":"server-n","method":"...","params":{...}}，需回 {"id":"server-n","result":...}
 */
class AppServerClient private constructor(
    private val process: Process,
) : AutoCloseable {

    private val log = Logger.getInstance(AppServerClient::class.java)
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val writerLock = Any()
    private val closed = AtomicBoolean(false)
    private val writer: BufferedWriter

    @Volatile
    var listener: Listener? = null

    interface Listener {
        /** server→client 通知（如 session/event、state.updated） */
        fun onNotification(method: String, params: JsonObject?)

        /**
         * server→client 请求（如 interaction/requestPermission）。
         * 在读线程上回调；必须通过 [responder] 异步应答（可以稍后、在任意线程）。
         */
        fun onRequest(id: String, method: String, params: JsonObject, responder: (JsonObject?) -> Unit)

        fun onExited(code: Int?)
    }

    companion object {
        fun start(nodeExecutable: String, runtimeScript: String, cwd: String? = null): AppServerClient {
            val pb = ProcessBuilder(nodeExecutable, runtimeScript, "app-server")
            cwd?.let { pb.directory(File(it)) }
            pb.environment()["NO_COLOR"] = "1"
            pb.environment()["ZCODE_DISABLE_UPDATE_CHECK"] = "1"
            pb.environment()["ZCODE_SURFACE"] = "desktop"
            val proc = pb.start()
            return AppServerClient(proc)
        }
    }

    init {
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        try {
                            dispatch(JsonParser.parseString(line).asJsonObject)
                        } catch (e: Exception) {
                            log.warn("无法解析 app-server 输出行: ${line.take(300)}", e)
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                if (!closed.get()) log.warn("app-server stdout 读取中断", e)
            }
            val code = runCatching { process.waitFor() }.getOrNull()
            if (!closed.get()) {
                failAllPending("app-server 进程退出 (code=$code)")
                listener?.onExited(code)
            }
        }, "zcode-app-server-reader").apply { isDaemon = true }.start()

        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) log.info("[app-server] $line")
                    }
                }
            } catch (_: Exception) {
            }
        }, "zcode-app-server-stderr").apply { isDaemon = true }.start()
    }

    private fun dispatch(msg: JsonObject) {
        val hasMethod = msg.has("method")
        val hasId = msg.has("id") && !msg.get("id").isJsonNull
        when {
            hasMethod && hasId && !msg.has("result") && !msg.has("error") -> {
                val requestId = msg.get("id").asString
                listener?.onRequest(requestId, msg.get("method").asString, msg.getAsJsonObject("params")) { result ->
                    runCatching {
                        if (result != null) {
                            writeLine(JsonObject().apply {
                                addProperty("id", requestId)
                                add("result", result)
                            })
                        } else {
                            respondError(requestId, -32603, "client no result")
                        }
                    }
                } ?: respondError(requestId, -32601, "no listener")
            }

            hasMethod ->
                listener?.onNotification(msg.get("method").asString, msg.getAsJsonObject("params"))

            hasId -> {
                val id = msg.get("id").asInt
                val future = pending.remove(id) ?: return
                val error = msg.getAsJsonObject("error")
                if (error != null) {
                    future.completeExceptionally(
                        RpcException(
                            error.get("code")?.asInt ?: -32000,
                            error.get("message")?.asString ?: "未知错误",
                            error.get("data"),
                        )
                    )
                } else {
                    future.complete(msg.getAsJsonObject("result") ?: JsonObject())
                }
            }
        }
    }

    fun request(method: String, params: JsonObject? = null): CompletableFuture<JsonObject> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("app-server 已关闭"))
        }
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val envelope = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params ?: JsonObject())
        }
        try {
            writeLine(envelope)
        } catch (e: Exception) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        return future
    }

    private fun writeLine(obj: JsonObject) {
        synchronized(writerLock) {
            writer.write(obj.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    fun respondError(id: String, code: Int, message: String) {
        runCatching {
            writeLine(JsonObject().apply {
                addProperty("id", id)
                add("error", JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", message)
                })
            })
        }
    }

    private fun failAllPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pending.clear()
    }

    val isAlive: Boolean get() = process.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        failAllPending("client closed")
        runCatching { writer.close() }
        runCatching { process.descendants().forEach { runCatching { it.destroy() } } }
        runCatching { process.destroy() }
        // 唤醒阻塞在流上的读线程（对真实进程无害，destroy 后流本就会 EOF）
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        if (process.isAlive) {
            Thread.sleep(200)
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
