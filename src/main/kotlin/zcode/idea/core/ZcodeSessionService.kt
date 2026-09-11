package zcode.idea.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import zcode.idea.commands.SlashCommands
import zcode.idea.context.SelectionContext
import zcode.idea.runtime.RuntimeResolver
import zcode.idea.settings.ZcodeSettings
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState { DISCONNECTED, STARTING, READY, RUNNING, DEAD }

enum class ToolStatus { PENDING, RUNNING, DONE, FAILED, DENIED }

enum class AssistantDeltaKind { TEXT, REASONING }

class ToolCallInfo(val id: String) {
    var name: String = ""
    var inputJson: JsonObject? = null
    var status: ToolStatus = ToolStatus.PENDING
    var summary: String? = null
    var filePath: String? = null
}

/** 会话内被 zcode 修改过的文件（保留首次修改前内容用于 diff） */
class ChangedFile(
    val path: String,
    val before: BeforeContent,
    val toolName: String,
    val createdAt: Long,
    /** 历史回填：没有修改前快照。此时 oldContent=null 不代表新建文件，diff 时应提示而非按空文件对比。 */
    val fromHistory: Boolean = false,
) {
    val oldContent: String? get() = (before as? BeforeContent.Captured)?.text
}

data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val mode: String?,
    val status: String?,
    val updatedAt: Long,
)

/** 模型选择器的一个可选项。 */
data class ModelOption(
    val providerId: String,
    val modelId: String,
    val label: String,
    val supportsImages: Boolean = false,
) {
    val display: String get() = label.ifBlank { "$providerId/$modelId" }
}

/** 待随消息发送的图片（本地文件）。 */
data class ImageRef(val fileName: String, val absolutePath: String)

/** 思考强度的一个可选项（值由服务端给定，如 low/high/max）。 */
data class ThoughtLevelInfo(val value: String, val label: String)

data class TranscriptEntry(
    val role: String,
    val text: String?,
    val reasoning: String?,
    val toolName: String?,
    /** 工具调用的目标摘要（file_path/command 等），历史渲染时展示在工具行上。 */
    val toolTarget: String? = null,
)

/**
 * 项目级会话服务：持有 app-server 子进程与当前会话，
 * 把协议事件翻译成语义回调（在 EDT 上派发），并追踪被修改的文件。
 */
@Service(Service.Level.PROJECT)
class ZcodeSessionService(val project: Project) : Disposable {

    private val log = Logger.getInstance(ZcodeSessionService::class.java)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val startLock = Any()
    private val tasks = SessionTaskQueue()
    @Volatile private var disposed = false

    fun viewGeneration(): Long = tasks.token()
    fun isViewCurrent(token: Long): Boolean = tasks.isCurrent(token) && !project.isDisposed

    @Volatile private var activeSessionGeneration = tasks.token()

    private fun <T> CompletableFuture<T>.await(seconds: Long): T = tasks.await(this, seconds)

    private fun AppServerClient.rpc(method: String, params: JsonObject? = null): CompletableFuture<JsonObject> {
        tasks.checkCurrent()
        return request(method, params)
    }

    /**
     * 后台串行队列内的发送占位：消息派发后保持占位，直到本轮完成或明确被拒。
     * 超时先查询服务端状态，不自动重发或中止可能已经接受的请求。
     */
    private val sendInFlight = AtomicBoolean(false)

    /**
     * 运行中收到的新消息排队区（FIFO）：当前轮结束后自动顺序发出。
     * 气泡在消息真正发出时才回显，保证界面上问答严格成对相邻。
     */
    private val sendQueue = ConcurrentLinkedQueue<QueuedSend>()

    /** 一条待发消息：[content] 为实际发送文本（含注入上下文/图片），[prompt]/[displayBlock] 用于回显。 */
    private class QueuedSend(val prompt: String, val content: String, val displayBlock: String?)

    /**
     * 本客户端视角"一轮进行中"：从派发消息起置位，直到收到 `turn.completed` / `idle` 才清除。
     * 排队与出队只看这个标志 + 发送占位，不信任服务端 state 推送的时序——
     * 实测 `prompt_completed` 可能提前推送，曾把第二条消息放进正在流式的轮次，
     * 导致回答文本被劈到两个气泡、第二条消息不被回答。
     */
    @Volatile
    private var turnActive = false

    @Volatile
    private var client: AppServerClient? = null

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    private var mode: String = ZcodeSettings.getInstance().state.defaultMode

    /** 最近一次连接时读到的 CLI 配置（构造 resume 用 runtimeModel）。 */
    @Volatile
    private var cliConfig: ZcodeCliConfig.CliConfig? = null

    /** 连接后从服务端读到的可用模型（空 = 尚未同步或 CLI 配置不可用）。 */
    @Volatile
    var availableModels: List<ModelOption> = emptyList()
        private set

    /** 当前生效的模型。 */
    @Volatile
    var currentModel: ModelOption? = null
        private set

    /** 当前模型可用的思考强度（空 = 模型不支持/未同步）。 */
    @Volatile
    var availableThoughtLevels: List<ThoughtLevelInfo> = emptyList()
        private set

    /** 当前生效的思考强度。 */
    @Volatile
    var currentThoughtLevel: String? = null
        private set

    /** 服务端快照暴露的内置斜杠命令（goal/compact/init/plan…），驱动输入框 "/" 补全。 */
    @Volatile
    var slashCommands: List<SlashCommands.CommandDef> = emptyList()
        private set

    private val toolCalls = ConcurrentHashMap<String, ToolCallInfo>()

    @Volatile private var snapshots = FileSnapshots()
    private data class PermissionRequest(
        val connection: AppServerClient, val generation: Long, val sessionId: String,
        val params: JsonObject, val responder: (JsonObject?) -> Unit,
    )
    private val permissionQueue = ConcurrentLinkedQueue<PermissionRequest>()
    private var permissionDialog: zcode.idea.ui.PermissionDialog? = null
    private var displayedPermission: PermissionRequest? = null
    @Volatile private var modeChanging = false
    @Volatile
    private var permissionDialogShowing = false

    interface Listener {
        fun onStateChanged(state: ConnectionState, detail: String?) {}
        fun onUserEcho(text: String, contextBlock: String?) {}
        fun onAssistantDelta(kind: AssistantDeltaKind, text: String) {}
        fun onAssistantDone(footer: String?) {}
        fun onToolCall(info: ToolCallInfo) {}
        fun onToolUpdate(info: ToolCallInfo) {}
        fun onTurnCompleted(summary: String) {}
        fun onContextUsage(usedTokens: Long, sizeTokens: Long) {}
        fun onNotice(text: String, error: Boolean) {}
        fun onHistoryCleared() {}
        fun onModeChanged(mode: String, pending: Boolean) {}
        fun onModelsChanged(
            models: List<ModelOption>,
            current: ModelOption?,
            thoughtLevels: List<ThoughtLevelInfo>,
            currentThoughtLevel: String?,
        ) {
        }

        fun onSlashCommands(commands: List<SlashCommands.CommandDef>) {}
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val settings: ZcodeSettings get() = ZcodeSettings.getInstance()

    // ------------------------------------------------------------------ 对外 API

    /** 预热连接：工具窗口打开时后台拉起 app-server，让首次发送/恢复不必干等冷启动。 */
    fun prewarm() {
        if (client?.isAlive == true) return
        tasks.execute {
            runCatching { ensureConnected() }
        }
    }

    /**
     * 在 EDT 上调用：读取 IDE 上下文并发送一条用户消息。
     * [explicitContext] 为用户显式引用的上下文块（右键"引用选中代码"），非空时不再自动采集。
     * [images] 为随消息发送的本地图片：app-server 协议的 attachments 只认服务端发放的
     * artifact 引用（无客户端上传 RPC），但 runtime 会解析消息文本里的 Markdown 图片引用
     * （多模态模型实测可见图），所以图片以内嵌 Markdown 方式追加。
     */
    fun send(prompt: String, explicitContext: String? = null, images: List<ImageRef> = emptyList()) {
        val trimmedPrompt = prompt.trim()
        // 斜杠命令原样发送：服务端对 /compact、/fork 的拦截要求文本精确匹配，
        // 自动附加的上下文/图片会让它退化成普通 prompt
        if (trimmedPrompt.startsWith("/")) {
            sendCommand(trimmedPrompt, trimmedPrompt)
            return
        }
        if (images.isNotEmpty() && currentModel?.supportsImages != true) {
            notice("当前模型 ${currentModel?.display ?: ""} 不支持图像输入，请先在模型下拉中切换到多模态模型", error = true)
            return
        }
        val basePath = project.basePath
        if (basePath == null) {
            notice("当前项目没有磁盘路径，无法启动 zcode 会话", error = true)
            return
        }
        val imageMd = images.joinToString("") { "\n\n![${it.fileName}](${imageUriOf(it.absolutePath)})" }
        val contextBlock: String? = explicitContext
            ?: if (settings.state.injectSelectionContext) {
                SelectionContext.capture(project)
                    ?.let { SelectionContext.buildBlock(it, settings.state.maxSelectionChars) }
            } else null
        // 回显气泡里把图片引用并进折叠上下文区，用户点开能看到发了什么
        val displayBlock = (imageMd + (contextBlock ?: "")).trim('\n').ifEmpty { null }
        val content = prompt + imageMd + (contextBlock ?: "")
        enqueueOrDispatch(QueuedSend(prompt, content, displayBlock))
    }

    /**
     * 发送一条命令类消息：[display] 是回显文本（如 `/review fix 42`），[content] 是实际发送文本
     * （自定义命令为客户端展开后的正文；服务端拦截类命令与 display 相同）。
     * 命令消息不注入 IDE 上下文与图片。运行中照常排队，当前轮结束后自动发出。
     */
    fun sendCommand(display: String, content: String) {
        if (display.isBlank()) return
        enqueueOrDispatch(QueuedSend(display, content, null))
    }

    /**
     * 发送一条内嵌引用块的消息：[display] 是回显文本（输入框原文，含 `@文件:行` 记号），
     * [content] 是记号已展开成引用代码块的完整正文。不注入自动 IDE 上下文——
     * 用户已显式引用了要看的代码（与旧 explicitContext 行为一致）。图片 Markdown 仍追加末尾。
     */
    fun sendWithRefs(display: String, content: String, images: List<ImageRef> = emptyList()) {
        if (display.isBlank()) return
        val imageMd = images.joinToString("") { "\n\n![${it.fileName}](${imageUriOf(it.absolutePath)})" }
        enqueueOrDispatch(QueuedSend(display, content + imageMd, imageMd.trim('\n').ifEmpty { null }))
    }

    /** 占坑成功且无轮次在跑 → 立即派发；否则入队等当前轮结束（详见 [send] 的排队说明）。 */
    private fun enqueueOrDispatch(msg: QueuedSend) = tasks.execute { dispatchOrQueue(msg) }

    private fun dispatchOrQueue(msg: QueuedSend) {
        // 占坑失败、或本端观察本轮仍在流式（turnActive）、或状态机在 RUNNING：一律入队。
        // turnActive 是关键：即使服务端状态事件提前/乱序把占位释放了，消息也不会挤进正在输出的轮次
        val acquired = sendInFlight.compareAndSet(false, true)
        if (!acquired || turnActive || modeChanging || state == ConnectionState.RUNNING) {
            if (acquired) sendInFlight.set(false)
            sendQueue.add(msg)
            log.info("消息入队（第 ${sendQueue.size} 条），待当前轮结束后发送")
            notice("正在等待当前操作完成，已排队（第 ${sendQueue.size} 条），完成后自动发送", error = false)
            return
        }
        dispatchSend(msg)
    }

    /** 立即发出一条消息：回显气泡 + 后台执行整轮（含状态核实及 -32031 恢复路径）。 */
    private fun dispatchSend(msg: QueuedSend) {
        log.info("派发消息: ${msg.prompt.lineSequence().firstOrNull()?.take(40)}")
        fire { it.onUserEcho(msg.prompt, msg.displayBlock) }
        tasks.execute {
            try {
                ensureConnected()
                val sid = sessionId
                if (sid == null) {
                    setState(ConnectionState.DEAD, "会话未就绪")
                    notice("会话未就绪，请点击“新会话”重试", error = true)
                    return@execute
                }
                setState(ConnectionState.RUNNING, null)
                turnActive = true
                try {
                    sendWithSession(sid, msg.content)
                } catch (e: Exception) {
                    when (rpcCodeOf(e)) {
                        // 恢复的历史会话绑定的模型已不可用：fork 出一个继承全部历史的新会话继续
                        -32031 -> {
                            val forked = forkSession(sid)
                            if (forked == null || forked == sid) throw e
                            log.info("session/send 被拒(-32031)，已 fork 继续会话: $forked")
                            notice("原会话绑定的模型已不可用，已切换到继承历史记录的新会话", error = false)
                            sessionId = forked
                            subscribeSession(client!!, forked)
                            sendWithSession(forked, msg.content)
                        }
                        else -> throw e
                    }
                }
            } catch (e: Exception) {
                tasks.checkCurrent()
                log.warn("session/send 失败", e)
                notice(describeError(e), error = true)
                val c = client
                if (c?.isAlive != true) {
                    turnActive = false
                    setState(ConnectionState.DEAD, e.message)
                } else if (rpcCodeOf(e) != null && rpcCodeOf(e) != -32010) {
                    finishRejectedSend()
                } else {
                    // Timeout does not imply rejection. Reconcile before releasing the queue.
                    val status = runCatching {
                        sessionId?.let { sessionStatus(subscribeRequest(c, it).await(10)) }
                    }.getOrNull()
                    tasks.checkCurrent()
                    if (status == "idle") finishRejectedSend()
                    else {
                        turnActive = true
                        setState(ConnectionState.RUNNING, "等待服务端确认")
                        notice("发送结果尚未确认，已暂停后续消息；可等待当前轮结束或点击停止", error = true)
                    }
                }
            }
        }
    }

    /**
     * 当前轮结束后调用：取出一条排队消息继续发。
     * 一次只取一条，它自己的轮结束会再次触发 drain，逐条消化；
     * CAS 保证并发触发（idle 事件 + 错误恢复路径同帧到达）时也只有一条真正发出。
     */
    private fun drainQueue() {
        if (turnActive || modeChanging) return // 本端还没观察到本轮结束，绝不放行下一条
        val next = sendQueue.peek() ?: return
        if (!sendInFlight.compareAndSet(false, true)) return
        sendQueue.poll()
        dispatchSend(next)
    }

    /** 丢弃全部排队消息并告知（点停止 / 断连 / 进程退出时）。 */
    private fun clearQueue() {
        val dropped = sendQueue.size
        sendQueue.clear()
        if (dropped > 0) notice("已清空 $dropped 条排队消息", error = false)
    }

    /** Markdown 图片引用用 file:/// + 正斜杠路径（实测两种写法 runtime 都能解析）。
     *  逐段 URL 编码：文件名里的空格/#/) 会截断 Markdown 链接解析，必须转义。 */
    private fun imageUriOf(path: String): String =
        "file:///" + path.replace('\\', '/').split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }

    private fun sendWithSession(sid: String, content: String) {
        val params = JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("content", content)
        }
        client!!.rpc("session/send", params).await(60)
    }

    /** session/fork：派生一个继承当前会话全部历史的新会话（新记录绑定当前可用模型），返回新 sessionId。 */
    private fun forkSession(sid: String): String? = runCatching {
        val r = client!!.rpc("session/fork", JsonObject().apply { addProperty("sessionId", sid) })
            .await(30)
        r.getAsJsonObject("session")?.get("sessionId")?.takeIf { !it.isJsonNull }?.asString
            ?: r.get("forkedSessionId")?.takeIf { !it.isJsonNull }?.asString
    }.onFailure { log.warn("session/fork 失败", it) }.getOrNull()

    /** 沿 cause 链找 RpcException 的错误码。 */
    private fun rpcCodeOf(e: Throwable?): Int? {
        var cur = e
        while (cur != null) {
            if (cur is RpcException) return cur.code
            cur = cur.cause?.takeIf { it !== cur }
        }
        return null
    }

    private fun finishRejectedSend() {
        turnActive = false
        snapshots.clearPending()
        setState(ConnectionState.READY, null)
        fire { it.onAssistantDone(null) }
        drainQueue()
    }

    private fun sessionStatus(reply: JsonObject): String? {
        val snapshot = reply.obj("snapshot") ?: reply
        return snapshot.obj("session")?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
            ?: snapshot.obj("projection")?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
    }

    fun stopCurrentTurn() {
        val generation = tasks.invalidate()
        tasks.execute(generation) {
            clearQueue()
            fire { it.onModeChanged(mode, modeChanging) }
            cancelPermissions()
            val c = client ?: return@execute
            val sid = sessionId ?: return@execute
            activeSessionGeneration = generation
            c.rpc("session/stop", JsonObject().apply { addProperty("sessionId", sid) })
                .whenComplete { _, error ->
                    tasks.execute(generation) completion@{
                        if (client !== c || sessionId != sid) return@completion
                        if (error != null) {
                            notice("停止失败：" + describeError(error), error = true)
                        } else {
                            if (modeChanging) reconcileMode(c, sid)
                            finishRejectedSend()
                        }
                    }
                }
        }
    }

    fun newSession() {
        val generation = tasks.invalidate()
        tasks.execute(generation) {
            val old = sessionId
            resetConversation()
            sessionId = null
            if (old != null) client?.rpc("session/close", JsonObject().apply { addProperty("sessionId", old) })
            runCatching { ensureConnected() }.onFailure {
                tasks.checkCurrent()
                notice(describeError(it), error = true)
            }
        }
    }

    private fun resetConversation() {
        toolCalls.clear()
        snapshots = FileSnapshots()
        sendQueue.clear()
        sendInFlight.set(false)
        turnActive = false
        modeChanging = false
        cancelPermissions()
        fire { it.onHistoryCleared() }
        fire { it.onModeChanged(mode, false) }
    }

    fun resumeSession(id: String, onTranscript: (List<TranscriptEntry>) -> Unit, onError: (String) -> Unit) {
        val generation = tasks.invalidate()
        tasks.execute(generation) {
            val old = sessionId
            resetConversation()
            try {
                val c = ensureConnected()
                if (old != null && old != id) {
                    c.rpc("session/close", JsonObject().apply { addProperty("sessionId", old) })
                }
                activateSession(c, id)
                val entries = readTranscript(id)
                onEdt(generation) { onTranscript(entries) }
            } catch (e: Exception) {
                tasks.checkCurrent()
                log.warn("恢复会话失败", e)
                sessionId = null
                setState(ConnectionState.DEAD, "恢复会话失败")
                onEdt(generation) { onError(describeError(e)) }
            }
        }
    }

    fun listSessions(cb: (List<SessionSummary>) -> Unit, onError: (String) -> Unit) {
        val basePath = project.basePath ?: return onError("项目无磁盘路径")
        tasks.execute {
            try {
                ensureConnected()
                val result = client!!.rpc("session/list", workspaceParams(basePath)).await(30)
                val sessions = (result.getAsJsonArray("sessions") ?: JsonArray()).mapNotNull { el ->
                    val o = el.asJsonObject
                    SessionSummary(
                        sessionId = o.get("sessionId")?.asString ?: return@mapNotNull null,
                        title = o.get("title")?.takeIf { !it.isJsonNull }?.asString,
                        mode = o.get("mode")?.takeIf { !it.isJsonNull }?.asString,
                        status = o.get("status")?.takeIf { !it.isJsonNull }?.asString,
                        updatedAt = o.get("updatedAt")?.asLong ?: 0L,
                    )
                }
                onEdt { cb(sessions) }
            } catch (e: Exception) {
                log.warn("session/list 失败", e)
                onEdt { onError(describeError(e)) }
            }
        }
    }

    fun setMode(modeId: String) = tasks.execute {
        if (ZcodeSettings.Mode.entries.none { it.id == modeId } || modeChanging) {
            fire { it.onModeChanged(mode, modeChanging) }
            return@execute
        }
        val c = client
        val sid = sessionId
        if (c == null || sid == null) {
            mode = modeId
            fire { it.onModeChanged(mode, false) }
            return@execute
        }
        modeChanging = true
        fire { it.onModeChanged(mode, true) }
        val generation = tasks.token()
        c.rpc("session/setMode", JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("mode", modeId)
        }).whenComplete { _, error ->
            tasks.execute(generation) completion@{
                if (client !== c || sessionId != sid) return@completion
                if (error == null) {
                    mode = modeId
                    modeChanging = false
                } else if (rpcCodeOf(error) != null) {
                    modeChanging = false
                    notice("切换权限模式失败：" + describeError(error), error = true)
                } else {
                    // A transport failure can arrive after the server already applied the mode.
                    // Keep sends paused until a snapshot confirms the actual permission policy.
                    reconcileMode(c, sid)
                }
                fire { it.onModeChanged(mode, modeChanging) }
                drainQueue()
            }
        }
    }

    private fun syncMode(reply: JsonObject): Boolean {
        val snapshot = reply.obj("snapshot") ?: reply
        val actual = snapshot.obj("session")?.get("mode")?.takeIf { it.isJsonPrimitive }?.asString
            ?: snapshot.obj("projection")?.get("mode")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return false
        if (ZcodeSettings.Mode.entries.none { it.id == actual }) return false
        mode = actual
        fire { it.onModeChanged(actual, modeChanging) }
        return true
    }

    private fun reconcileMode(c: AppServerClient, sid: String) {
        modeChanging = true
        val reply = runCatching { subscribeRequest(c, sid).await(10) }.getOrNull()
        tasks.checkCurrent()
        if (reply != null && syncMode(reply)) {
            modeChanging = false
        } else {
            notice("尚未确认服务端权限模式，已暂停发送；请新建会话或重新连接后再试", error = true)
        }
        fire { it.onModeChanged(mode, modeChanging) }
    }

    /**
     * 删除历史会话。协议没有删除 RPC，会话落在 ~/.zcode/cli/db/db.sqlite（session 表，
     * 外键级联清掉消息/工具记录），用 node 自带的 node:sqlite 执行删除。
     */
    fun deleteSession(id: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val generation = if (id == sessionId) tasks.invalidate() else tasks.token()
        tasks.execute(generation) {
            try {
                // 服务端可能仍持有该会话，先尝试通知关闭（非活跃时会被拒绝，忽略即可）
                client?.takeIf { it.isAlive }?.rpc("session/close", JsonObject().apply {
                    addProperty("sessionId", id)
                })?.exceptionally { null }?.await(5)

                if (id == sessionId) {
                    sessionId = null
                    resetConversation()
                }

                val node = RuntimeResolver.resolve(settings).getOrThrow().nodeExecutable
                val dbPath = File(System.getProperty("user.home"), ".zcode${File.separator}cli${File.separator}db${File.separator}db.sqlite")
                if (!dbPath.isFile) error("未找到会话数据库: ${dbPath.path}")
                val script = """
                    const {DatabaseSync} = require('node:sqlite');
                    const db = new DatabaseSync(process.argv[1]);
                    db.exec('PRAGMA busy_timeout=5000');
                    const r = db.prepare('DELETE FROM session WHERE id=?').run(process.argv[2]);
                    db.close();
                    process.stdout.write(String(r.changes));
                """.trimIndent()
                val proc = ProcessBuilder(node, "-e", script, dbPath.absolutePath, id)
                    .redirectErrorStream(false)
                    .start()
                proc.outputStream.close()
                // 先等退出再读流：readText 会阻塞到 EOF（进程退出），先读后等会让超时分支永远到不了
                if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    error("删除超时")
                }
                val out = runCatching { proc.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
                val changes = out.toIntOrNull()
                if (changes == null) {
                    val errOut = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("")
                    error("删除失败：${(errOut.lineSequence() + out.lineSequence()).firstOrNull { it.isNotBlank() } ?: "未知错误"}")
                }
                if (changes == 0) error("会话不存在（可能已被删除）")
                log.info("已删除会话 $id")
                onEdt { onDone() }
            } catch (e: Exception) {
                log.warn("删除会话失败 $id", e)
                onEdt { onError(describeError(e)) }
            }
        }
    }

    fun currentMode(): String = mode

    fun changedFilesSnapshot(): List<ChangedFile> = snapshots.files()

    /** 首次修改前内容（用于 diff），文件为新建时为 null。 */
    fun oldContentOf(path: String): String? = changedFilesSnapshot().firstOrNull { it.path == path }?.oldContent

    fun restartProcess() {
        val generation = tasks.invalidate()
        tasks.execute(generation) {
            client?.close()
            client = null
            sessionId = null
            resetConversation()
            setState(ConnectionState.DISCONNECTED, null)
            runCatching { ensureConnected() }.onFailure {
                tasks.checkCurrent()
                notice(describeError(it), error = true)
            }
        }
    }

    // ------------------------------------------------------------------ 连接管理

    private fun ensureConnected(): AppServerClient {
        tasks.checkCurrent()
        val existing = client?.takeIf { it.isAlive }
        if (existing != null && sessionId != null) return existing
        synchronized(startLock) {
            client?.takeIf { it.isAlive }?.let { c ->
                if (sessionId != null) return c
                // 连接仍在但没有活跃会话（例如刚点过“新会话”把 sessionId 置空）：
                // 必须在现有连接上补建会话，不能直接返回——否则后续 send 永远“会话未就绪”
                val basePath = project.basePath ?: error("项目无磁盘路径")
                createOrResumeSession(c, basePath)
                setState(if (turnActive) ConnectionState.RUNNING else ConnectionState.READY, null)
                return c
            }
            setState(ConnectionState.STARTING, null)
            val resolved = RuntimeResolver.resolve(settings)
                .getOrElse { e ->
                    setState(ConnectionState.DEAD, e.message)
                    throw e
                }
            val basePath = project.basePath ?: error("项目无磁盘路径")
            val c = AppServerClient.start(resolved.nodeExecutable, resolved.runtimeScript, basePath)
            client = c
            c.listener = ClientListener(c)
            if (disposed) { c.close(); tasks.checkCurrent() }
            try {
                syncModelCatalog(c, basePath)
                createOrResumeSession(c, basePath)
            } catch (e: Exception) {
                c.close()
                client = null
                tasks.checkCurrent()
                setState(ConnectionState.DEAD, e.message)
                throw e
            }
            setState(if (turnActive) ConnectionState.RUNNING else ConnectionState.READY, resolved.source)
            return c
        }
    }

    /**
     * 把 CLI 配置里的模型目录回推给 app-server。
     *
     * app-server 每个进程的 workspace 模型目录初始为空：不回推的话，resume 历史会话会
     * 因“历史模型不在目录中”置 restoreWarning，之后每次发送都被 -32031 拒绝；新建会话
     * 也只能用默认模型，无法切换。逐模型各推一次（readState+runtimeModel）是因为服务端
     * 的合并逻辑每次只保留被选中的那个模型，多推几次才能把目录补全。
     */
    private fun syncModelCatalog(c: AppServerClient, basePath: String) {
        val cfg = ZcodeCliConfig.load()
        if (cfg == null || cfg.providers.isEmpty()) {
            log.info("未找到 ~/.zcode/cli/config.json，跳过模型目录同步（沿用服务端默认模型）")
            return
        }
        cliConfig = cfg
        val preferred = preferredModelRef()
        for (provider in cfg.providers.values) {
            for (model in provider.models) {
                runCatching {
                    c.rpc("workspace/readState", workspaceParams(basePath).apply {
                        add("runtimeModel", cfg.runtimeModelJson(provider, model, "idea-${provider.providerId}-${model.modelId}-${System.currentTimeMillis()}"))
                    }).await(30)
                }.onFailure { log.warn("模型目录回推失败 ${provider.providerId}/${model.modelId}", it) }
            }
        }
        // 回推完成后读一次最终状态：拿权威的可用模型列表喂给选择器
        runCatching {
            val st = c.rpc("workspace/readState", workspaceParams(basePath)).await(30)
            val settingsObj = st.getAsJsonObject("settings") ?: return@runCatching
            val modelSettings = settingsObj.getAsJsonObject("model") ?: return@runCatching
            val options = (modelSettings.getAsJsonArray("available") ?: JsonArray()).mapNotNull { el ->
                val o = el.asJsonObject
                val ref = o.getAsJsonObject("ref") ?: return@mapNotNull null
                val label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: ""
                ModelOption(
                    providerId = ref.get("providerId")?.asString ?: return@mapNotNull null,
                    modelId = ref.get("modelId")?.asString ?: return@mapNotNull null,
                    label = label,
                    supportsImages = o.get("supportsImages")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                )
            }
            if (options.isNotEmpty()) {
                availableModels = options
                // 当前模型：优先用户偏好（若在列表中），否则服务端报的 current
                val serverCurrent = modelSettings.getAsJsonObject("current")?.let { cur ->
                    options.firstOrNull {
                        it.providerId == cur.get("providerId")?.asString && it.modelId == cur.get("modelId")?.asString
                    }
                }
                val preferredOption = preferred?.let { ref ->
                    options.firstOrNull { it.providerId == ref.providerId && it.modelId == ref.modelId }
                }
                currentModel = preferredOption ?: serverCurrent ?: options.first()
            }
            // 思考强度（随模型变化，可能为空 = 当前模型不支持）
            val tl = settingsObj.getAsJsonObject("thoughtLevel")
            runCatching {
                availableThoughtLevels = (tl?.getAsJsonArray("available") ?: JsonArray()).mapNotNull { el ->
                    val o = el.asJsonObject
                    ThoughtLevelInfo(
                        value = o.get("value")?.asString ?: return@mapNotNull null,
                        label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    )
                }
                val serverLevel = tl?.get("current")?.takeIf { !it.isJsonNull }?.asString
                val prefLevel = settings.state.preferredThoughtLevel.takeIf { it.isNotBlank() }
                currentThoughtLevel = prefLevel?.takeIf { pref -> availableThoughtLevels.any { it.value == pref } }
                    ?: serverLevel
            }.onFailure { log.info("解析思考强度失败", it) }
            if (availableModels.isNotEmpty() || availableThoughtLevels.isNotEmpty()) {
                fire { it.onModelsChanged(availableModels, currentModel, availableThoughtLevels, currentThoughtLevel) }
            }
        }.onFailure { log.warn("读取模型列表失败", it) }
    }

    fun selectThoughtLevel(value: String) = tasks.execute {
        val c = client
        val sid = sessionId
        if (c != null && sid != null) {
            try {
                c.rpc("session/setThoughtLevel", JsonObject().apply {
                    addProperty("sessionId", sid)
                    addProperty("thoughtLevel", value)
                }).await(15)
            } catch (e: Exception) {
                tasks.checkCurrent()
                notice("切换思考强度失败：" + describeError(e), error = true)
                fire { it.onModelsChanged(availableModels, currentModel, availableThoughtLevels, currentThoughtLevel) }
                return@execute
            }
        }
        settings.state.preferredThoughtLevel = value
        currentThoughtLevel = value
        fire { it.onModelsChanged(availableModels, currentModel, availableThoughtLevels, value) }
    }

    private fun preferredModelRef(): ZcodeCliConfig.ModelRef? {
        val s = settings.state
        return if (s.preferredModelProvider.isNotBlank() && s.preferredModelId.isNotBlank()) {
            ZcodeCliConfig.ModelRef(s.preferredModelProvider, s.preferredModelId)
        } else null
    }

    /** 用户在模型选择器里选定模型：切换当前会话；RPC 成功后才更新状态与偏好，失败则回滚显示并提示。 */
    fun selectModel(option: ModelOption) = tasks.execute {
        val previous = currentModel
        val c = client
        val sid = sessionId
        if (c != null && sid != null) {
            try {
                c.rpc("session/setModel", JsonObject().apply {
                    addProperty("sessionId", sid)
                    add("model", JsonObject().apply {
                        addProperty("providerId", option.providerId)
                        addProperty("modelId", option.modelId)
                    })
                }).await(30)
            } catch (e: Exception) {
                tasks.checkCurrent()
                currentModel = previous
                fire { it.onModelsChanged(availableModels, previous, availableThoughtLevels, currentThoughtLevel) }
                notice("切换模型失败：" + describeError(e), error = true)
                return@execute
            }
        }
        settings.state.preferredModelProvider = option.providerId
        settings.state.preferredModelId = option.modelId
        currentModel = option
        fire { it.onModelsChanged(availableModels, option, availableThoughtLevels, currentThoughtLevel) }
        if (c != null && sid != null) project.basePath?.let { refreshThoughtLevels(c, it) }
    }

    /** 重读 workspace/readState 的思考强度档位并广播（模型切换后档位随模型变化）。 */
    private fun refreshThoughtLevels(c: AppServerClient, basePath: String) {
        runCatching {
            val st = c.rpc("workspace/readState", workspaceParams(basePath)).await(30)
            val tl = st.getAsJsonObject("settings")?.getAsJsonObject("thoughtLevel")
            availableThoughtLevels = (tl?.getAsJsonArray("available") ?: JsonArray()).mapNotNull { el ->
                val o = el.asJsonObject
                ThoughtLevelInfo(
                    value = o.get("value")?.asString ?: return@mapNotNull null,
                    label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "",
                )
            }
            val serverLevel = tl?.get("current")?.takeIf { !it.isJsonNull }?.asString
            currentThoughtLevel = currentThoughtLevel
                ?.takeIf { cur -> availableThoughtLevels.any { it.value == cur } }
                ?: serverLevel
            fire { it.onModelsChanged(availableModels, currentModel, availableThoughtLevels, currentThoughtLevel) }
        }.onFailure { log.info("刷新思考强度失败", it) }
    }

    private fun workspaceParams(basePath: String): JsonObject = JsonObject().apply {
        add("workspace", JsonObject().apply {
            addProperty("workspaceKey", basePath)
            addProperty("workspacePath", basePath)
        })
    }

    private fun createOrResumeSession(c: AppServerClient, basePath: String) {
        val existing = sessionId
        var result: JsonObject? = null
        var resumedId: String? = null
        if (existing != null) {
            result = runCatching {
                c.rpc("session/resume", resumeParams(basePath, existing)).await(60)
            }.onFailure { log.info("session/resume 失败，回退为新建: ${it.message}") }.getOrNull()
            if (result != null) resumedId = existing
        }
        if (result == null) {
            result = c.rpc("session/create", JsonObject().apply {
                add("workspace", JsonObject().apply {
                    addProperty("workspaceKey", basePath)
                    addProperty("workspacePath", basePath)
                })
                addProperty("mode", mode)
                // 显式带上选定模型，避免新建会话落在别的默认模型上
                selectedModelForNewSession()?.let { sel ->
                    add("model", JsonObject().apply {
                        addProperty("providerId", sel.first.providerId)
                        addProperty("modelId", sel.second.modelId)
                    })
                }
            }).await(90)
        }
        val sid = result.getAsJsonObject("session")?.get("sessionId")?.asString
            ?: resumedId
            ?: error("session/create 响应中没有 sessionId")
        sessionId = sid
        activeSessionGeneration = tasks.token()
        turnActive = sessionStatus(result) == "running"
        syncMode(result)
        subscribeSession(c, sid)
        updateSlashCommands(result)
        // create 参数不支持思考强度，建好后单独应用偏好
        settings.state.preferredThoughtLevel.takeIf { it.isNotBlank() }?.let { level ->
            runCatching {
                c.rpc("session/setThoughtLevel", JsonObject().apply {
                    addProperty("sessionId", sid)
                    addProperty("thoughtLevel", level)
                }).await(15)
            }.onFailure { log.info("应用思考强度 $level 失败: ${it.message}") }
        }
        log.info("zcode 会话就绪: $sid")
    }

    /**
     * resume 参数：必须带 runtimeModel。服务端会从历史消息里恢复“上次使用的模型”，
     * 若该模型已下线，会置 restoreWarning 并拒绝后续发送；带上当前可用模型可整体覆盖。
     */
    private fun resumeParams(basePath: String, sid: String): JsonObject = JsonObject().apply {
        addProperty("sessionId", sid)
        add("workspace", JsonObject().apply {
            addProperty("workspaceKey", basePath)
            addProperty("workspacePath", basePath)
        })
        settings.state.preferredThoughtLevel.takeIf { it.isNotBlank() }?.let { addProperty("thoughtLevel", it) }
        val cfg = cliConfig ?: ZcodeCliConfig.load()
        val sel = cfg?.resolvePreferred(preferredModelRef())
        if (cfg != null && sel != null) {
            add("runtimeModel", cfg.runtimeModelJson(sel.first, sel.second, "idea-resume-${System.currentTimeMillis()}"))
        }
    }

    private fun selectedModelForNewSession(): Pair<ZcodeCliConfig.ProviderInfo, ZcodeCliConfig.ModelInfo>? =
        cliConfig?.resolvePreferred(preferredModelRef())

    private fun subscribeSession(c: AppServerClient, sid: String) {
        // includeSnapshot=true：回复带 session 快照，runtime.contextUsage 是当前上下文占用（used/size），
        // 用来驱动工具栏的“上下文 x/y”显示
        val reply = subscribeRequest(c, sid).await(30)
        fireContextUsage(reply)
        syncMode(reply)
        updateSlashCommands(reply)
    }

    private fun subscribeRequest(c: AppServerClient, sid: String) =
        c.rpc("session/subscribe", JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("deliveryKind", "desktop-continuous")
            // 不传 afterSeq：服务端只在显式传 afterSeq 时才回放该 seq 之后的历史事件，缺省仅推送订阅之后的新事件
            addProperty("includeSnapshot", true)
        })

    /** 每轮结束后刷新上下文占用：重复 subscribe（无 afterSeq 不回放事件）只为拿最新快照。 */
    private fun pollContextUsage() {
        val c = client ?: return
        val sid = sessionId ?: return
        val generation = tasks.token()
        subscribeRequest(c, sid).whenComplete { reply, _ ->
            tasks.execute(generation) {
                if (client === c && sessionId == sid) reply?.let(::fireContextUsage)
            }
        }
    }

    private fun fireContextUsage(reply: JsonObject?) {
        val usage = reply?.obj("snapshot")?.obj("runtime")?.obj("contextUsage") ?: return
        val used = usage.get("used")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        val size = usage.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        fire { it.onContextUsage(used, size) }
    }

    /**
     * 从会话快照类响应（session/create、session/resume、session/subscribe）解析服务端
     * 暴露的内置斜杠命令。只保留 builtin：custom 由插件本地扫描 .md 文件获得
     * （服务端只报名字不报内容，展开必须客户端自己做）。
     */
    private fun updateSlashCommands(source: JsonObject?) {
        val arr = source?.getAsJsonArray("slashCommands")
            ?: source?.obj("snapshot")?.getAsJsonArray("slashCommands")
            ?: return
        val builtins = arr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                SlashCommands.CommandDef(
                    name = name,
                    description = o.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    inputHint = o.get("inputHint")?.takeIf { !it.isJsonNull }?.asString,
                    source = "builtin",
                )
            }.getOrNull()
        }.filter { it.source == "builtin" }
        if (builtins.isNotEmpty() && builtins != slashCommands) {
            slashCommands = builtins
            log.info("服务端内置命令: ${builtins.joinToString(", ") { "/" + it.name }}")
            fire { it.onSlashCommands(builtins) }
        }
    }

    private fun JsonObject.obj(member: String): JsonObject? =
        get(member)?.takeIf { it.isJsonObject }?.asJsonObject

    /** 激活一个历史会话（session/read、session/send、session/subscribe 都只对活跃会话可用）。 */
    private fun activateSession(c: AppServerClient, id: String) {
        val basePath = project.basePath ?: error("项目无磁盘路径")
        val reply = c.rpc("session/resume", resumeParams(basePath, id)).await(60)
        sessionId = id
        activeSessionGeneration = tasks.token()
        syncMode(reply)
        subscribeSession(c, id)
        turnActive = sessionStatus(reply) == "running"
        setState(if (turnActive) ConnectionState.RUNNING else ConnectionState.READY, null)
        log.info("历史会话已激活: " + id)
    }

    private fun readTranscript(sid: String): List<TranscriptEntry> {
        val result = client!!.rpc("session/read", JsonObject().apply { addProperty("sessionId", sid) })
            .await(60)
        val entries = mutableListOf<TranscriptEntry>()
        val messages = result.getAsJsonArray("messages") ?: JsonArray()
        for (msgEl in messages) {
            val msg = msgEl.asJsonObject
            val role = msg.getAsJsonObject("info")?.get("role")?.asString ?: continue
            val parts = msg.getAsJsonArray("parts") ?: continue
            for (partEl in parts) {
                // 单个 part 解析失败只跳过该 part，绝不能让整个恢复炸掉
                //（tool part 的形态随 runtime 版本变过：tool 曾为对象、现为字符串名，详见 PROTOCOL.md）
                runCatching {
                    val part = partEl.asJsonObject
                    when (part.get("type")?.asString) {
                        "text" -> entries.add(TranscriptEntry(role, part.get("text")?.takeIf { !it.isJsonNull }?.asString, null, null))
                        "reasoning" -> entries.add(TranscriptEntry(role, null, part.get("text")?.takeIf { !it.isJsonNull }?.asString, null))
                        "tool" -> {
                            val toolEl = part.get("tool")
                            val name = when (toolEl) {
                                is com.google.gson.JsonPrimitive -> toolEl.asString
                                is JsonObject -> toolEl.get("name")?.takeIf { !it.isJsonNull }?.asString
                                else -> null
                            } ?: "?"
                            val input = (toolEl as? JsonObject)?.get("input")?.takeIf { it.isJsonObject }?.asJsonObject
                                ?: part.getAsJsonObject("state")?.get("input")?.takeIf { it.isJsonObject }?.asJsonObject
                            entries.add(TranscriptEntry(role, null, null, "🔧 $name", toolTargetOf(input)))
                            // 回填工具修改过的文件，便于 diff
                            registerToolFromHistory(name, input)
                        }
                        "file" -> {
                            // 图片/附件 part：给一行可读标记，不渲染内容本身
                            val mime = part.get("mime")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            val fname = part.get("filename")?.takeIf { !it.isJsonNull }?.asString
                            val label = when {
                                mime.startsWith("image/") -> "🖼 图片附件" + (fname?.let { "（$it）" } ?: "")
                                fname != null -> "📎 附件 $fname"
                                mime.isNotBlank() -> "📎 附件（$mime）"
                                else -> "📎 附件"
                            }
                            entries.add(TranscriptEntry(role, null, null, label))
                        }
                    }
                }.onFailure { log.warn("解析历史消息 part 失败（已跳过）", it) }
            }
        }
        return entries
    }

    private fun registerToolFromHistory(name: String, input: JsonObject?) {
        // 只登记写工具：历史里的 Read 等只读工具也带 file_path，但并不曾修改文件
        if (!isFileTool(name)) return
        val path = filePathOf(name, input) ?: return
        snapshots.registerHistory(resolveToolFile(path).path, name)
    }

    /** 历史工具调用的目标摘要（展示用）：file_path/path 优先，其次 command/pattern/query/url/prompt。 */
    private fun toolTargetOf(input: JsonObject?): String? {
        if (input == null) return null
        for (key in listOf("file_path", "path", "notebook_path", "command", "pattern", "query", "url", "prompt")) {
            val v = input.get(key)?.takeIf { !it.isJsonNull }?.asString
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    // ------------------------------------------------------------------ 协议事件处理

    private inner class ClientListener(private val connection: AppServerClient) : AppServerClient.Listener {
        override fun onNotification(method: String, params: JsonObject?) {
            val payload = params ?: return
            val store = snapshots
            val generation = tasks.token()
            if (activeSessionGeneration != generation || client !== connection || !belongsToSession(payload)) return
            // Preserve pre-write capture even while the session worker awaits another RPC.
            // A switched session owns a new store, so an old reader cannot pollute its snapshots.
            if (method == "session/event" && payload.get("type")?.asString == "model.streaming") {
                val event = payload.obj("payload")
                if (event?.get("kind")?.asString == "tool_call") {
                    val name = event.get("toolName")?.asString ?: ""
                    val id = event.get("toolCallId")?.asString
                    val path = filePathOf(name, event.obj("input"))
                    if (id != null && path != null && isFileTool(name)) {
                        store.capture(id, resolveToolFile(path))
                    }
                }
            }
            tasks.execute(generation) {
                if (client !== connection || !belongsToSession(payload)) return@execute
                when (method) {
                    "session/event" -> handleSessionEvent(payload)
                    "state.updated" -> handleStateUpdated(payload)
                }
            }
        }

        override fun onRequest(id: String, method: String, params: JsonObject, responder: (JsonObject?) -> Unit) {
            if (method != "interaction/requestPermission") {
                connection.respondError(id, -32601, "not supported by idea plugin")
                return
            }
            // Approvals must remain independent of the worker's synchronous RPC waits.
            val generation = tasks.token()
            val sid = params.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
            if (sid == null || activeSessionGeneration != generation ||
                client !== connection || sessionId != sid || !tasks.isCurrent(generation)) {
                responder(denyPermission())
                return
            }
            permissionQueue.add(PermissionRequest(connection, generation, sid, params, responder))
            pumpPermissionQueue()
        }

        override fun onExited(code: Int?) {
            tasks.execute {
                if (client !== connection || state == ConnectionState.DISCONNECTED) return@execute
                setState(ConnectionState.DEAD, "zcode 进程退出，下次发送时将自动重启")
                cancelPermissions()
                notice("zcode 进程退出（code=" + code + "）", error = true)
            }
        }
    }

    private fun belongsToSession(params: JsonObject): Boolean =
        params.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString?.let { it == sessionId } == true

    private fun handleSessionEvent(params: JsonObject) {
        val type = params.get("type")?.asString ?: return
        val payload = params.getAsJsonObject("payload") ?: JsonObject()
        when (type) {
            "model.streaming" -> handleStreaming(payload)
            "tool.updated" -> handleToolUpdated(payload)
            "turn.started" -> log.info("session/event: turn.started")
            "turn.completed" -> {
                // 权威的"本轮结束"信号（自带 usage）：先收尾状态与助手面板，再挂 footer，最后消化队列。
                // 只依赖 state.updated(idle/prompt_completed) 不可靠：实测个别 runtime 会在轮次中途
                // 提前推送 prompt_completed，把发送占位提前释放，下一条消息就挤进正在流式的轮次
                log.info("session/event: turn.completed")
                turnActive = false
                if (state == ConnectionState.RUNNING) setState(ConnectionState.READY, null)
                fire { it.onAssistantDone(null) }
                val usage = payload.getAsJsonObject("usage")
                val tokens = usage?.get("totalTokens")?.asLong
                val durationMs = payload.get("duration")?.asLong
                val summary = buildString {
                    if (tokens != null) append("tokens: $tokens")
                    if (durationMs != null) {
                        if (isNotEmpty()) append(" · ")
                        append("%.1fs".format(durationMs / 1000.0))
                    }
                }
                fire { it.onTurnCompleted(summary) }
                pollContextUsage()
                drainQueue()
            }
        }
    }

    private fun handleStreaming(payload: JsonObject) {
        when (payload.get("kind")?.asString) {
            "text_delta" -> {
                val delta = payload.get("delta")?.takeIf { !it.isJsonNull }?.asString ?: return
                fire { it.onAssistantDelta(AssistantDeltaKind.TEXT, delta) }
            }
            "reasoning_delta" -> {
                val delta = payload.get("delta")?.takeIf { !it.isJsonNull }?.asString ?: return
                fire { it.onAssistantDelta(AssistantDeltaKind.REASONING, delta) }
            }
            "tool_call" -> {
                val id = payload.get("toolCallId")?.asString ?: return
                val info = ToolCallInfo(id)
                info.name = payload.get("toolName")?.asString ?: "?"
                info.inputJson = payload.getAsJsonObject("input")
                info.filePath = filePathOf(info.name, info.inputJson)
                toolCalls[id] = info
                fire { it.onToolCall(info) }
            }
        }
    }

    private fun handleToolUpdated(payload: JsonObject) {
        val id = payload.get("toolCallId")?.asString ?: return
        val info = toolCalls[id] ?: return
        when (payload.get("kind")?.asString) {
            "started" -> info.status = ToolStatus.RUNNING
            "result" -> {
                val result = payload.getAsJsonObject("result")
                val success = result?.get("success")?.asBoolean ?: false
                info.status = if (success) ToolStatus.DONE else ToolStatus.FAILED
                info.summary = result?.get("content")?.takeIf { !it.isJsonNull }?.asString
                    ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200)
                // 所有终态（成功/失败）都取走快照：失败的写操作不能把大块修改前内容一直留在内存
                if (info.filePath != null && isFileTool(info.name)) {
                    snapshots.complete(id, resolveToolFile(info.filePath!!).path, info.name, success)
                    if (success) refreshVfsFile(info.filePath!!)
                }
            }
        }
        fire { it.onToolUpdate(info) }
    }

    private fun handleStateUpdated(params: JsonObject) {
        val patch = params.getAsJsonObject("patch") ?: return
        patch.get("mode")?.takeIf { it.isJsonPrimitive }?.asString?.let { actual ->
            syncMode(JsonObject().apply { add("session", JsonObject().apply { addProperty("mode", actual) }) })
        }
        val status = patch.get("status")?.asString
        val reason = params.get("reason")?.asString
        log.info("state.updated: status=$status reason=$reason")
        when (status) {
            "running" -> if (state == ConnectionState.READY || state == ConnectionState.STARTING) {
                setState(ConnectionState.RUNNING, null)
            }
            "idle" -> {
                if (state == ConnectionState.RUNNING) {
                    turnActive = false
                    setState(ConnectionState.READY, null)
                    fire { it.onAssistantDone(null) }
                    refreshVfsAsync()
                }
                // 兜底：状态已在 READY 时收到的 idle 也尝试消化队列（防 drain 被漏触发）
                drainQueue()
            }
        }
        if (reason == "compact_started") {
            // /compact（手动压缩）作为一轮后台任务执行：走正常 turn 事件流，
            // 结束后 pollContextUsage 会自动刷新"上下文 x/y"显示
            notice("正在压缩上下文…（总结当前对话以释放上下文空间）", error = false)
        }
        // 注意：prompt_completed 不再作为轮次结束信号。探针实测它跟在 turn.completed 之后，
        // 但用户环境的 runtime 会在轮次中途提前推送它——轮次结束一律以 turn.completed / idle 为准。
    }

    // ------------------------------------------------------------------ 权限审批

    /**
     * 权限审批串行泵。全部状态只在 EDT 读写：reader 线程的 onRequest 只负责投递，
     * 避免 reader 线程与"弹窗关闭后继续泵"并发 poll 导致双弹窗。
     */
    private fun pumpPermissionQueue() {
        ApplicationManager.getApplication().invokeLater { pumpOnEdt() }
    }

    private fun denyPermission() = JsonObject().apply {
        addProperty("decision", "deny")
        addProperty("reason", "Session changed or request cancelled")
    }

    private fun validPermission(req: PermissionRequest): Boolean =
        tasks.isCurrent(req.generation) && !project.isDisposed && req.connection.isAlive &&
            activeSessionGeneration == req.generation &&
            client === req.connection && sessionId == req.sessionId

    private fun cancelPermissions() {
        while (true) {
            val req = permissionQueue.poll() ?: break
            req.responder(denyPermission())
        }
        ApplicationManager.getApplication().invokeLater {
            displayedPermission?.let { if (!validPermission(it)) permissionDialog?.doCancelAction() }
        }
    }

    private fun pumpOnEdt() {
        if (permissionDialogShowing) return
        while (true) {
            val req = permissionQueue.poll() ?: return
            if (!validPermission(req)) { req.responder(denyPermission()); continue }
            permissionDialogShowing = true
            try {
                val dialog = zcode.idea.ui.PermissionDialog(project, req.params)
                permissionDialog = dialog
                displayedPermission = req
                val response = dialog.showAndGetResponse()
                req.responder(if (validPermission(req)) response else denyPermission())
            } catch (e: Exception) {
                req.responder(denyPermission())
                log.warn("工具审批失败", e)
            } finally {
                permissionDialog = null
                displayedPermission = null
                permissionDialogShowing = false
            }
        }
    }

    // ------------------------------------------------------------------ 文件追踪与 VFS

    private fun isFileTool(name: String): Boolean =
        name in setOf("Edit", "Write", "MultiEdit", "ApplyPatch", "NotebookEdit")

    private fun filePathOf(toolName: String, input: JsonObject?): String? {
        if (input == null) return null
        for (key in listOf("file_path", "path", "notebook_path")) {
            val v = input.get(key)?.takeIf { !it.isJsonNull }?.asString
            if (!v.isNullOrEmpty()) return v
        }
        // ApplyPatch 等输入可能是字符串补丁，其中包含路径 —— MVP 不解析
        return null
    }

    private fun resolveToolFile(path: String): File =
        File(path).let { if (it.isAbsolute) it else File(project.basePath ?: ".", path) }

    private fun refreshVfsFile(path: String) {
        val ioFile = resolveToolFile(path)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(listOf(ioFile))
            }
        }
    }

    private fun refreshVfsAsync() {
        val basePath = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(java.io.File(basePath).absolutePath.replace('\\', '/'))
            }
        }
    }

    // ------------------------------------------------------------------ 杂项

    private fun setState(s: ConnectionState, detail: String?) {
        tasks.checkCurrent()
        log.info("连接状态: $s${detail?.let { "（$it）" } ?: ""}")
        state = s
        // 离开运行链路（完成/出错/重置）即释放发送占位；STARTING 是冷启动中间态，不释放
        if (s == ConnectionState.READY || s == ConnectionState.DEAD || s == ConnectionState.DISCONNECTED) {
            sendInFlight.set(false)
            // 断连/进程退出后排队消息不再自动发送（避免反复触发重启重试），直接丢弃并告知
            if (s != ConnectionState.READY) {
                turnActive = false
                modeChanging = false
                fire { it.onModeChanged(mode, false) }
                clearQueue()
            }
        }
        fire { it.onStateChanged(s, detail) }
    }

    private fun notice(text: String, error: Boolean) = fire { it.onNotice(text, error) }

    private fun onEdt(generation: Long = tasks.token(), action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (isViewCurrent(generation)) action()
        }
    }

    private fun fire(f: (Listener) -> Unit) {
        onEdt { listeners.forEach(f) }
    }

    private fun describeError(e: Throwable): String = when (e) {
        is RpcException -> {
            val dataMsg = (e.data as? com.google.gson.JsonElement)?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("message")?.asString
            dataMsg?.let { "${e.message}（$it）" } ?: e.message ?: "未知错误"
        }
        is java.util.concurrent.ExecutionException -> describeError(e.cause ?: e)
        else -> e.message ?: e.toString()
    }

    override fun dispose() {
        disposed = true
        tasks.close()
        cancelPermissions()
        client?.close()
        client = null
        snapshots.clear()
        listeners.clear()
    }
}
