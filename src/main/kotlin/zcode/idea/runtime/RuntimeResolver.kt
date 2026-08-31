package zcode.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import zcode.idea.settings.ZcodeSettings
import java.io.File
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
            "D:\\Nodejs\\node.exe",
            "D:\\Program Files\\nodejs\\node.exe",
        ).firstOrNull { File(it).isFile() }?.let { return it to "常见位置" }
        return null
    }

    fun findRuntimeScript(settings: ZcodeSettings): Pair<String, String>? {
        settings.state.runtimePath.takeIf { it.isNotBlank() }?.let {
            if (File(it).isFile) return it to "设置指定"
            LOG.warn("设置中的 runtime 路径不存在: $it")
        }
        // 1. ZCode 桌面端自带的 runtime（首选，避免依赖第三方 npm 包）
        val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home") + "\\AppData\\Local"
        listOf(
            "$localAppData\\Programs\\ZCode\\resources\\glm\\zcode.cjs",
            "C:\\Program Files\\ZCode\\resources\\glm\\zcode.cjs",
            "D:\\Tools\\ZCode\\resources\\glm\\zcode.cjs",
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

    /** 用 where.exe 查找可执行文件（仅 Windows；其他平台用 which）。 */
    private fun where(executable: String): String? {
        val finder = if (System.getProperty("os.name").lowercase().contains("win")) "where.exe" else "which"
        return try {
            val proc = ProcessBuilder(finder, executable)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && File(it).isFile }
        } catch (e: Exception) {
            LOG.debug("查找 $executable 失败", e)
            null
        }
    }

    fun nodeNotFoundMessage(): String =
        "未找到 Node.js（需要 >= 22.19）。请在设置 → Tools → ZCode 中指定 node.exe 路径，或将其加入 PATH。"

    fun runtimeNotFoundMessage(): String =
        "未找到 zcode runtime（zcode.cjs）。请安装 zcode-app-cli（npm i -g zcode-app-cli）、安装 ZCode 桌面端，" +
            "或在设置 → Tools → ZCode 中手动指定 zcode.cjs 路径。"
}
