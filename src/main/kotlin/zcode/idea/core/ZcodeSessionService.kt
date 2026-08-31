package zcode.idea.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import zcode.idea.context.SelectionContext
import zcode.idea.runtime.RuntimeResolver
import zcode.idea.settings.ZcodeSettings
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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
class ChangedFile(val path: String, val oldContent: String?, val toolName: String, val createdAt: Long)

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

    private val toolCalls = ConcurrentHashMap<String, ToolCallInfo>()

    /** toolCallId -> (path, 修改前内容) */
    private val pendingSnapshots = ConcurrentHashMap<String, Pair<String, String?>>()

    /** path -> ChangedFile（多轮修改同一文件时保留最早快照） */
    private val changedFiles = java.util.Collections.synchronizedMap(LinkedHashMap<String, ChangedFile>())

    private val permissionQueue = ConcurrentLinkedQueue<Array<Any>>() // [id, params, responder]
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
        fun onModelsChanged(
            models: List<ModelOption>,
            current: ModelOption?,
            thoughtLevels: List<ThoughtLevelInfo>,
            currentThoughtLevel: String?,
        ) {
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val settings: ZcodeSettings get() = ZcodeSettings.getInstance()

    // ------------------------------------------------------------------ 对外 API

    /** 预热连接：工具窗口打开时后台拉起 app-server，让首次发送/恢复不必干等冷启动。 */
    fun prewarm() {
        if (client?.isAlive == true) return
        ApplicationManager.getApplication().executeOnPooledThread {
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
        if (state == ConnectionState.RUNNING) {
            notice("上一轮仍在运行，请等待完成或点击停止", error = true)
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
        fire { it.onUserEcho(prompt, displayBlock) }
        val content = prompt + imageMd + (contextBlock ?: "")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureConnected()
                val sid = sessionId
                if (sid == null) {
                    setState(ConnectionState.DEAD, "会话未就绪")
                    notice("会话未就绪，请点击“新会话”重试", error = true)
                    return@executeOnPooledThread
                }
                setState(ConnectionState.RUNNING, null)
                try {
                    sendWithSession(sid, content)
                } catch (e: Exception) {
                    when (rpcCodeOf(e)) {
                        // 服务端认为该会话上一轮还没结束（如恢复了中断的会话）：stop 后重试一次
                        -32010 -> {
                            log.info("session/send 被拒(-32010)，session/stop 后重试一次")
                            runCatching {
                                client?.request("session/stop", JsonObject().apply { addProperty("sessionId", sid) })
                                    ?.get(10, TimeUnit.SECONDS)
                            }
                            sendWithSession(sid, content)
                        }
                        // 恢复的历史会话绑定的模型已不可用：fork 出一个继承全部历史的新会话继续
                        -32031 -> {
                            val forked = forkSession(sid)
                            if (forked == null || forked == sid) throw e
                            log.info("session/send 被拒(-32031)，已 fork 继续会话: $forked")
                            notice("原会话绑定的模型已不可用，已切换到继承历史记录的新会话", error = false)
                            sessionId = forked
                            subscribeSession(client!!, forked)
                            sendWithSession(forked, content)
                        }
                        else -> throw e
                    }
                }
            } catch (e: Exception) {
                log.warn("session/send 失败", e)
                setState(if (client?.isAlive == true) ConnectionState.READY else ConnectionState.DEAD, e.message)
                notice(describeError(e), error = true)
            }
        }
    }

    /** Markdown 图片引用用 file:/// + 正斜杠路径（实测两种写法 runtime 都能解析）。 */
    private fun imageUriOf(path: String): String = "file:///" + path.replace('\\', '/')

    private fun sendWithSession(sid: String, content: String) {
        val params = JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("content", content)
        }
        client!!.request("session/send", params).get(60, TimeUnit.SECONDS)
    }

    /** session/fork：派生一个继承当前会话全部历史的新会话（新记录绑定当前可用模型），返回新 sessionId。 */
    private fun forkSession(sid: String): String? = runCatching {
        val r = client!!.request("session/fork", JsonObject().apply { addProperty("sessionId", sid) })
            .get(30, TimeUnit.SECONDS)
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

    fun stopCurrentTurn() {
        val c = client ?: return
        val sid = sessionId ?: return
        c.request("session/stop", JsonObject().apply { addProperty("sessionId", sid) })
            .exceptionally { null }
    }

    /** 新建会话（不复用当前 sessionId）。 */
    fun newSession() {
        val old = sessionId
        sessionId = null
        toolCalls.clear()
        pendingSnapshots.clear()
        synchronized(changedFiles) { changedFiles.clear() }
        fire { it.onHistoryCleared() }
        if (client?.isAlive == true && old != null) {
            client?.request("session/close", JsonObject().apply { addProperty("sessionId", old) })
                ?.exceptionally { null }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { ensureConnected() }
        }
    }

    /** 恢复历史会话并回调转录（EDT）。session/read 只对进程内活跃的会话可用，必须先 session/resume 激活。 */
    fun resumeSession(id: String, onTranscript: (List<TranscriptEntry>) -> Unit, onError: (String) -> Unit) {
        fire { it.onHistoryCleared() }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val c = ensureConnected()
                activateSession(c, id)
                val entries = readTranscript(id)
                ApplicationManager.getApplication().invokeLater { onTranscript(entries) }
            } catch (e: Exception) {
                log.warn("恢复会话失败", e)
                ApplicationManager.getApplication().invokeLater { onError(describeError(e)) }
            }
        }
    }

    fun listSessions(cb: (List<SessionSummary>) -> Unit, onError: (String) -> Unit) {
        val basePath = project.basePath ?: return onError("项目无磁盘路径")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureConnected()
                val result = client!!.request("session/list", workspaceParams(basePath)).get(30, TimeUnit.SECONDS)
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
                ApplicationManager.getApplication().invokeLater { cb(sessions) }
            } catch (e: Exception) {
                log.warn("session/list 失败", e)
                ApplicationManager.getApplication().invokeLater { onError(describeError(e)) }
            }
        }
    }

    fun setMode(modeId: String) {
        mode = modeId
        val c = client ?: return
        val sid = sessionId ?: return
        c.request("session/setMode", JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("mode", modeId)
        }).exceptionally { null }
    }

    /**
     * 删除历史会话。协议没有删除 RPC，会话落在 ~/.zcode/cli/db/db.sqlite（session 表，
     * 外键级联清掉消息/工具记录），用 node 自带的 node:sqlite 执行删除。
     */
    fun deleteSession(id: String, onDone: () -> Unit, onError: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 服务端可能仍持有该会话，先尝试通知关闭（非活跃时会被拒绝，忽略即可）
                client?.takeIf { it.isAlive }?.request("session/close", JsonObject().apply {
                    addProperty("sessionId", id)
                })?.exceptionally { null }?.get(5, TimeUnit.SECONDS)

                if (id == sessionId) {
                    sessionId = null
                    toolCalls.clear()
                    pendingSnapshots.clear()
                    synchronized(changedFiles) { changedFiles.clear() }
                    fire { it.onHistoryCleared() }
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
                val out = proc.inputStream.bufferedReader().readText().trim()
                val finished = proc.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    error("删除超时")
                }
                val changes = out.toIntOrNull()
                if (changes == null) {
                    val errOut = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("")
                    error("删除失败：${(errOut.lineSequence() + out.lineSequence()).firstOrNull { it.isNotBlank() } ?: "未知错误"}")
                }
                if (changes == 0) error("会话不存在（可能已被删除）")
                log.info("已删除会话 $id")
                ApplicationManager.getApplication().invokeLater { onDone() }
            } catch (e: Exception) {
                log.warn("删除会话失败 $id", e)
                ApplicationManager.getApplication().invokeLater { onError(describeError(e)) }
            }
        }
    }

    fun currentMode(): String = mode

    fun changedFilesSnapshot(): List<ChangedFile> = synchronized(changedFiles) { changedFiles.values.toList() }

    /** 首次修改前内容（用于 diff），文件为新建时为 null。 */
    fun oldContentOf(path: String): String? = changedFilesSnapshot().firstOrNull { it.path == path }?.oldContent

    fun restartProcess() {
        synchronized(startLock) {
            client?.close()
            client = null
            sessionId = null
            setState(ConnectionState.DISCONNECTED, null)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { ensureConnected() }
        }
    }

    // ------------------------------------------------------------------ 连接管理

    private fun ensureConnected(): AppServerClient {
        val existing = client?.takeIf { it.isAlive }
        if (existing != null && sessionId != null) return existing
        synchronized(startLock) {
            client?.takeIf { it.isAlive }?.let { c ->
                if (sessionId != null) return c
                // 连接仍在但没有活跃会话（例如刚点过“新会话”把 sessionId 置空）：
                // 必须在现有连接上补建会话，不能直接返回——否则后续 send 永远“会话未就绪”
                val basePath = project.basePath ?: error("项目无磁盘路径")
                createOrResumeSession(c, basePath)
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
            c.listener = ClientListener()
            client = c
            try {
                syncModelCatalog(c, basePath)
                createOrResumeSession(c, basePath)
            } catch (e: Exception) {
                c.close()
                client = null
                setState(ConnectionState.DEAD, e.message)
                throw e
            }
            setState(ConnectionState.READY, resolved.source)
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
                    c.request("workspace/readState", workspaceParams(basePath).apply {
                        add("runtimeModel", cfg.runtimeModelJson(provider, model, "idea-${provider.providerId}-${model.modelId}-${System.currentTimeMillis()}"))
                    }).get(30, TimeUnit.SECONDS)
                }.onFailure { log.warn("模型目录回推失败 ${provider.providerId}/${model.modelId}", it) }
            }
        }
        // 回推完成后读一次最终状态：拿权威的可用模型列表喂给选择器
        runCatching {
            val st = c.request("workspace/readState", workspaceParams(basePath)).get(30, TimeUnit.SECONDS)
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

    /** 用户在思考强度下拉里选定：持久化 + 切换当前会话。 */
    fun selectThoughtLevel(value: String) {
        settings.state.preferredThoughtLevel = value
        currentThoughtLevel = value
        fire { it.onModelsChanged(availableModels, currentModel, availableThoughtLevels, value) }
        val c = client ?: return
        val sid = sessionId ?: return
        c.request("session/setThoughtLevel", JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("thoughtLevel", value)
        }).exceptionally { null }
    }

    private fun preferredModelRef(): ZcodeCliConfig.ModelRef? {
        val s = settings.state
        return if (s.preferredModelProvider.isNotBlank() && s.preferredModelId.isNotBlank()) {
            ZcodeCliConfig.ModelRef(s.preferredModelProvider, s.preferredModelId)
        } else null
    }

    /** 用户在模型选择器里选定模型：持久化 + 切换当前会话。 */
    fun selectModel(option: ModelOption) {
        settings.state.preferredModelProvider = option.providerId
        settings.state.preferredModelId = option.modelId
        currentModel = option
        fire { it.onModelsChanged(availableModels, option, availableThoughtLevels, currentThoughtLevel) }
        val c = client ?: return
        val sid = sessionId ?: return
        c.request("session/setModel", JsonObject().apply {
            addProperty("sessionId", sid)
            add("model", JsonObject().apply {
                addProperty("providerId", option.providerId)
                addProperty("modelId", option.modelId)
            })
        }).exceptionally { null }
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
                c.request("session/resume", resumeParams(basePath, existing)).get(60, TimeUnit.SECONDS)
            }.onFailure { log.info("session/resume 失败，回退为新建: ${it.message}") }.getOrNull()
            if (result != null) resumedId = existing
        }
        if (result == null) {
            result = c.request("session/create", JsonObject().apply {
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
            }).get(90, TimeUnit.SECONDS)
        }
        val sid = result.getAsJsonObject("session")?.get("sessionId")?.asString
            ?: resumedId
            ?: error("session/create 响应中没有 sessionId")
        sessionId = sid
        subscribeSession(c, sid)
        // create 参数不支持思考强度，建好后单独应用偏好
        settings.state.preferredThoughtLevel.takeIf { it.isNotBlank() }?.let { level ->
            runCatching {
                c.request("session/setThoughtLevel", JsonObject().apply {
                    addProperty("sessionId", sid)
                    addProperty("thoughtLevel", level)
                }).get(15, TimeUnit.SECONDS)
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
        val reply = subscribeRequest(c, sid).get(30, TimeUnit.SECONDS)
        fireContextUsage(reply)
    }

    private fun subscribeRequest(c: AppServerClient, sid: String) =
        c.request("session/subscribe", JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("deliveryKind", "desktop-continuous")
            // 不传 afterSeq：服务端只在显式传 afterSeq 时才回放该 seq 之后的历史事件，缺省仅推送订阅之后的新事件
            addProperty("includeSnapshot", true)
        })

    /** 每轮结束后刷新上下文占用：重复 subscribe（无 afterSeq 不回放事件）只为拿最新快照。 */
    private fun pollContextUsage() {
        val c = client ?: return
        val sid = sessionId ?: return
        subscribeRequest(c, sid).whenComplete { reply, _ -> reply?.let(::fireContextUsage) }
    }

    private fun fireContextUsage(reply: JsonObject?) {
        val usage = reply?.obj("snapshot")?.obj("runtime")?.obj("contextUsage") ?: return
        val used = usage.get("used")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        val size = usage.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        fire { it.onContextUsage(used, size) }
    }

    private fun JsonObject.obj(member: String): JsonObject? =
        get(member)?.takeIf { it.isJsonObject }?.asJsonObject

    /** 激活一个历史会话（session/read、session/send、session/subscribe 都只对活跃会话可用）。 */
    private fun activateSession(c: AppServerClient, id: String) {
        if (id == sessionId && (state == ConnectionState.READY || state == ConnectionState.RUNNING)) return
        val basePath = project.basePath ?: error("项目无磁盘路径")
        runCatching {
            c.request("session/resume", resumeParams(basePath, id)).get(60, TimeUnit.SECONDS)
        }.getOrElse { e ->
            throw IllegalStateException("激活历史会话失败: ${describeError(e)}", e)
        }
        subscribeSession(c, id)
        sessionId = id
        // 恢复的会话可能带着上次中断时的“运行中”状态；显式回 READY，避免客户端把后续发送拦下。
        // 若服务端确实要续跑上一轮，会再推 state.updated(running) 把状态改回去。
        if (state == ConnectionState.RUNNING || state == ConnectionState.STARTING) {
            setState(ConnectionState.READY, null)
        }
        log.info("历史会话已激活: $id")
    }

    private fun readTranscript(sid: String): List<TranscriptEntry> {
        val result = client!!.request("session/read", JsonObject().apply { addProperty("sessionId", sid) })
            .get(60, TimeUnit.SECONDS)
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
                            entries.add(TranscriptEntry(role, null, null, "🔧 $name"))
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
        val path = filePathOf(name, input) ?: return
        registerChangedFile(path, name, oldContent = null)
    }

    // ------------------------------------------------------------------ 协议事件处理

    private inner class ClientListener : AppServerClient.Listener {

        override fun onNotification(method: String, params: JsonObject?) {
            when (method) {
                "session/event" -> handleSessionEvent(params ?: return)
                "state.updated" -> handleStateUpdated(params ?: return)
            }
        }

        override fun onRequest(id: String, method: String, params: JsonObject, responder: (JsonObject?) -> Unit) {
            when (method) {
                "interaction/requestPermission" -> {
                    permissionQueue.add(arrayOf(id, params, responder))
                    pumpPermissionQueue()
                }
                else -> {
                    // session/requestRuntimePreferences 等返回 -32601 是官方容错路径（走默认值）
                    client?.respondError(id, -32601, "not supported by idea plugin")
                }
            }
        }

        override fun onExited(code: Int?) {
            if (state != ConnectionState.DISCONNECTED) {
                setState(ConnectionState.DEAD, "zcode 进程退出（code=$code），下次发送时将自动重启")
                notice("zcode 进程退出（code=$code）", error = true)
            }
        }
    }

    private fun handleSessionEvent(params: JsonObject) {
        val type = params.get("type")?.asString ?: return
        val payload = params.getAsJsonObject("payload") ?: JsonObject()
        when (type) {
            "model.streaming" -> handleStreaming(payload)
            "tool.updated" -> handleToolUpdated(payload)
            "turn.completed" -> {
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
                if (info.filePath != null && isFileTool(info.name)) {
                    snapshotBeforeWrite(id, info.filePath!!)
                }
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
                if (success && info.filePath != null && isFileTool(info.name)) {
                    registerChangedFile(info.filePath!!, info.name, pendingSnapshots.remove(id)?.second)
                    refreshVfsFile(info.filePath!!)
                }
            }
        }
        fire { it.onToolUpdate(info) }
    }

    private fun handleStateUpdated(params: JsonObject) {
        val patch = params.getAsJsonObject("patch") ?: return
        val status = patch.get("status")?.asString
        val reason = params.get("reason")?.asString
        when (status) {
            "running" -> if (state == ConnectionState.READY || state == ConnectionState.STARTING) {
                setState(ConnectionState.RUNNING, null)
            }
            "idle" -> if (state == ConnectionState.RUNNING) {
                setState(ConnectionState.READY, null)
                fire { it.onAssistantDone(null) }
                refreshVfsAsync()
            }
        }
        if (reason == "prompt_completed" && state == ConnectionState.RUNNING) {
            setState(ConnectionState.READY, null)
            fire { it.onAssistantDone(null) }
        }
    }

    // ------------------------------------------------------------------ 权限审批

    @Suppress("UNCHECKED_CAST")
    private fun pumpPermissionQueue() {
        if (permissionDialogShowing) return
        val req = permissionQueue.poll() ?: return
        permissionDialogShowing = true
        val params = req[1] as JsonObject
        val responder = req[2] as (JsonObject?) -> Unit
        ApplicationManager.getApplication().invokeLater {
            val response = zcode.idea.ui.PermissionDialog(project, params).showAndGetResponse()
            permissionDialogShowing = false
            responder(response)
            pumpPermissionQueue()
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

    private fun snapshotBeforeWrite(toolCallId: String, path: String) {
        val file = File(path)
        val content = if (file.isFile && file.length() < 16 * 1024 * 1024) {
            runCatching { file.readText() }.getOrNull()
        } else null
        pendingSnapshots[toolCallId] = path to content
    }

    private fun registerChangedFile(path: String, toolName: String, oldContent: String?) {
        synchronized(changedFiles) {
            if (!changedFiles.containsKey(path)) {
                changedFiles[path] = ChangedFile(path, oldContent, toolName, System.currentTimeMillis())
            }
        }
    }

    private fun refreshVfsFile(path: String) {
        val ioFile = File(path)
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
        state = s
        fire { it.onStateChanged(s, detail) }
    }

    private fun notice(text: String, error: Boolean) = fire { it.onNotice(text, error) }

    private fun fire(f: (Listener) -> Unit) {
        ApplicationManager.getApplication().invokeLater { listeners.forEach(f) }
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
        client?.close()
        client = null
    }
}
