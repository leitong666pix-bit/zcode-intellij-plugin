package zcode.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import zcode.idea.settings.ZcodeSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 解析 zcode 运行所需的两样东西：
 * 1. node.exe（>=22.19）
 * 2. zcode.cjs —— ZCode 官方 agent runtime（桌面端 resources/glm/zcode.cjs，或 zcode-app-cli 的 vendor/zcode.cjs）
 *
 * 插件不随包分发 runtime，只做发现；均可在设置页覆盖。
 */
object RuntimeResolver {

    private val LOG = Logger.getInstance(RuntimeResolver::class.java)

    data class ResolvedRuntime(val nodeExecutable: String, val runtimeScript: String, val source: String)

    sealed interface ResolveError {
        data class NodeNotFound(val detail: String) : ResolveError
        data class RuntimeNotFound(val detail: String) : ResolveError
    }

    fun resolve(settings: ZcodeSettings): Result<ResolvedRuntime> {
        val node = findNode(settings)
            ?: return Result.failure(IllegalStateException(nodeNotFoundMessage()))
        // 真正校验版本：deleteSession 依赖 node:sqlite（22.5+ 才内置），文档口径 22.19。
        // 只查文件存在的话，老 node 会在删除会话时才炸出难懂的错
        val version = nodeVersionOf(node.first)
        if (version == null || !versionAtLeast(version, 22, 19)) {
            return Result.failure(IllegalStateException(
                "Node 版本不满足要求（需要 >= 22.19，当前 ${version ?: "未知"}）：${node.first}。" +
                    "请在设置 → Tools → ZCode 中指定满足要求的 node.exe。"
            ))
        }
        val runtime = findRuntimeScript(settings)
            ?: return Result.failure(IllegalStateException(runtimeNotFoundMessage()))
        return Result.success(ResolvedRuntime(node.first, runtime.first, "node: ${node.second}；runtime: ${runtime.second}"))
    }

    /** 返回 (路径, 来源说明) */
    fun findNode(settings: ZcodeSettings): Pair<String, String>? {
        settings.state.nodePath.takeIf { it.isNotBlank() }?.let {
            if (File(it).isFile) return it to "设置指定"
            LOG.warn("设置中的 node 路径不存在: $it")
        }
        where("node")?.let { return it to "PATH" }
        listOf(
            "C:\\Program Files\\nodejs\\node.exe",
            "/usr/local/bin/node",
            "/opt/homebrew/bin/node",
        ).firstOrNull { File(it).isFile }?.let { return it to "常见位置" }
        return null
    }

    fun findRuntimeScript(settings: ZcodeSettings): Pair<String, String>? {
        settings.state.runtimePath.takeIf { it.isNotBlank() }?.let {
            if (File(it).isFile) return it to "设置指定"
            LOG.warn("设置中的 runtime 路径不存在: $it")
        }
        // 1. ZCode 桌面端自带的 runtime（首选，避免依赖第三方 npm 包）；只放通用安装位置，不含个人路径
        val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home") + "\\AppData\\Local"
        val home = System.getProperty("user.home")
        listOf(
            "$localAppData\\Programs\\ZCode\\resources\\glm\\zcode.cjs",
            "C:\\Program Files\\ZCode\\resources\\glm\\zcode.cjs",
            "$home/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs",
            "/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs",
        ).forEach { path ->
            candidate(path)?.let { return it to "ZCode 桌面端" }
        }
        // 2. zcode-app-cli（npm 全局）作为回退：官方桌面端未安装时兜底
        System.getenv("APPDATA")?.let { appData ->
            candidate("$appData\\npm\\node_modules\\zcode-app-cli\\vendor\\zcode.cjs")?.let {
                return it to "zcode-app-cli（npm 回退）"
            }
        }
        // 3. zcode-app-cli（自定义 npm 前缀）：由 PATH 上的 zcode shim 反推
        where("zcode")?.let { shim ->
            val dir = File(shim).parentFile // <prefix>\bin 或 <prefix>
            listOf(dir, dir?.parentFile).filterNotNull().forEach { base ->
                candidate(File(base, "node_modules/zcode-app-cli/vendor/zcode.cjs").path)?.let {
                    return it to "zcode-app-cli（PATH 反推）"
                }
            }
        }
        return null
    }

    private fun candidate(path: String): String? = path.takeIf { File(it).isFile() }

    /** 用 where.exe 查找可执行文件（仅 Windows；其他平台用 which）。先等进程退出再读输出：
     *  readText 会阻塞到 EOF（进程退出），先读后等会让超时分支永远到不了。 */
    private fun where(executable: String): String? {
        val finder = if (System.getProperty("os.name").lowercase().contains("win")) "where.exe" else "which"
        return try {
            val proc = ProcessBuilder(finder, executable)
                .redirectErrorStream(true)
                .start()
            try {
                if (!proc.waitFor(5, TimeUnit.SECONDS)) return null
                proc.inputStream.bufferedReader().readText().lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && File(it).isFile }
            } finally {
                proc.destroyForcibly()
            }
        } catch (e: Exception) {
            LOG.debug("查找 $executable 失败", e)
            null
        }
    }

    /** node 版本缓存（path → 版本串；空串 = 无法解析）。避免每次 resolve 都起进程探测。 */
    private val nodeVersionCache = ConcurrentHashMap<String, String>()

    /** 读取 `node --version`；无法执行/解析时返回 null。 */
    fun nodeVersionOf(nodeExecutable: String): String? {
        nodeVersionCache[nodeExecutable]?.let { return it.ifEmpty { null } }
        val v = readNodeVersion(nodeExecutable)
        nodeVersionCache[nodeExecutable] = v ?: ""
        return v
    }

    private fun readNodeVersion(node: String): String? = runCatching {
        val proc = ProcessBuilder(node, "--version").redirectErrorStream(true).start()
        try {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) return@runCatching null
            Regex("v?\\d+(\\.\\d+)*").find(proc.inputStream.bufferedReader().readText().trim())?.value
        } finally {
            proc.destroyForcibly()
        }
    }.getOrNull()

    /** "22.19.0" 这类版本串是否 >= major.minor。 */
    fun versionAtLeast(version: String, major: Int, minor: Int): Boolean {
        val parts = version.removePrefix("v").split('.')
        val maj = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return maj > major || (maj == major && min >= minor)
    }

    fun nodeNotFoundMessage(): String =
        "未找到 Node.js（需要 >= 22.19）。请在设置 → Tools → ZCode 中指定 node.exe 路径，或将其加入 PATH。"

    fun runtimeNotFoundMessage(): String =
        "未找到 zcode runtime（zcode.cjs）。请安装 zcode-app-cli（npm i -g zcode-app-cli）、安装 ZCode 桌面端，" +
            "或在设置 → Tools → ZCode 中手动指定 zcode.cjs 路径。"
}
