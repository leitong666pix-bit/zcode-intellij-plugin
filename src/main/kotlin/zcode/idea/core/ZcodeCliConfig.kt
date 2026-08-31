package zcode.idea.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 读取 zcode CLI 的用户配置（~/.zcode/cli/config.json），把 provider/model 信息翻译成
 * app-server 需要的 runtimeModel（Mf 结构）。
 *
 * 背景：app-server 每个进程内的 workspace 模型目录（workspaceModelCatalogs）初始为空，
 * resume 历史会话时若沿用历史里已下线的模型，会置 restoreWarning，之后所有
 * session/send 都被 -32031（ZCODE_RUNTIME_MODEL_UNAVAILABLE）拒绝。
 * 客户端必须：连接后逐模型回推 runtimeModel 填充目录，且 resume 时显式携带 runtimeModel。
 */
object ZcodeCliConfig {

    data class ModelRef(val providerId: String, val modelId: String)

    data class ModelInfo(
        val modelId: String,
        val label: String?,
        val contextWindow: Long?,
        val maxOutputTokens: Long?,
    )

    data class ProviderInfo(
        val providerId: String,
        val kind: String,
        val source: String?,
        val label: String?,
        val baseURL: String?,
        val apiKey: String?,
        val apiKeyRequired: Boolean?,
        val models: List<ModelInfo>,
    )

    class CliConfig(
        val providers: Map<String, ProviderInfo>,
        /** config.json 里 model.main，形如 "zai/glm-5.3-flash" */
        val mainModel: ModelRef?,
    ) {
        /** 所有 (provider, model) 平铺，供选择器展示。 */
        fun allModels(): List<Pair<ProviderInfo, ModelInfo>> =
            providers.values.flatMap { p -> p.models.map { p to it } }

        fun providerOf(ref: ModelRef): ProviderInfo? = providers[ref.providerId]

        /**
         * 选择一个可用模型：优先 [preferred]（用户在插件里选的），
         * 其次 config 的 main，最后第一个可用模型。
         */
        fun resolvePreferred(preferred: ModelRef?): Pair<ProviderInfo, ModelInfo>? {
            val all = allModels()
            preferred?.let { ref ->
                all.firstOrNull { it.first.providerId == ref.providerId && it.second.modelId == ref.modelId }
                    ?.let { return it }
            }
            mainModel?.let { main ->
                all.firstOrNull { it.first.providerId == main.providerId && it.second.modelId == main.modelId }
                    ?.let { return it }
            }
            return all.firstOrNull()
        }

        /** 构造 app-server 协议里的 runtimeModel（Mf：revision/generatedAt/model/provider）。 */
        fun runtimeModelJson(provider: ProviderInfo, model: ModelInfo, revision: String): JsonObject =
            JsonObject().apply {
                addProperty("revision", revision)
                addProperty("generatedAt", System.currentTimeMillis())
                add("model", JsonObject().apply {
                    addProperty("providerId", provider.providerId)
                    addProperty("modelId", model.modelId)
                })
                add("provider", providerJson(provider))
            }

        private fun providerJson(p: ProviderInfo): JsonObject = JsonObject().apply {
            addProperty("providerId", p.providerId)
            addProperty("kind", p.kind)
            p.source?.let { addProperty("source", it) }
            p.label?.let { addProperty("label", it) }
            p.baseURL?.let { addProperty("baseURL", it) }
            p.apiKey?.let { apiKey ->
                add("apiKey", JsonObject().apply {
                    addProperty("source", "inline")
                    addProperty("value", apiKey)
                })
            }
            p.apiKeyRequired?.let { addProperty("apiKeyRequired", it) }
            add("models", com.google.gson.JsonArray().apply {
                for (m in p.models) {
                    add(JsonObject().apply {
                        addProperty("modelId", m.modelId)
                        m.label?.let { addProperty("label", it) }
                        m.contextWindow?.let { addProperty("contextWindow", it) }
                        m.maxOutputTokens?.let { addProperty("maxOutputTokens", it) }
                    })
                }
            })
        }
    }

    fun configFilePath(): File =
        File(File(System.getProperty("user.home"), ".zcode"), File("cli", "config.json").path)

    fun load(): CliConfig? = runCatching {
        val file = configFilePath()
        if (!file.isFile) return null
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val providers = LinkedHashMap<String, ProviderInfo>()
        root.getAsJsonObject("provider")?.entrySet()?.forEach { (pid, pe) ->
            val p = pe.asJsonObject
            val options = p.getAsJsonObject("options")
            val modelsObj = p.getAsJsonObject("models") ?: return@forEach
            val models = mutableListOf<ModelInfo>()
            modelsObj.entrySet().forEach { (mid, me) ->
                val m = me.asJsonObject
                val limit = m.getAsJsonObject("limit")
                models.add(
                    ModelInfo(
                        modelId = mid,
                        label = m.get("name")?.takeIf { !it.isJsonNull }?.asString,
                        contextWindow = limit?.get("context")?.takeIf { !it.isJsonNull }?.asLong,
                        maxOutputTokens = limit?.get("output")?.takeIf { !it.isJsonNull }?.asLong,
                    )
                )
            }
            if (models.isEmpty()) return@forEach
            providers[pid] = ProviderInfo(
                providerId = pid,
                kind = p.get("kind")?.takeIf { !it.isJsonNull }?.asString ?: "openai-compatible",
                source = p.get("source")?.takeIf { !it.isJsonNull }?.asString,
                label = p.get("name")?.takeIf { !it.isJsonNull }?.asString,
                baseURL = options?.get("baseURL")?.takeIf { !it.isJsonNull }?.asString,
                apiKey = options?.get("apiKey")?.takeIf { !it.isJsonNull }?.asString,
                apiKeyRequired = options?.get("apiKeyRequired")?.takeIf { !it.isJsonNull }?.asBoolean,
                models = models,
            )
        }
        val main = root.getAsJsonObject("model")?.get("main")?.takeIf { !it.isJsonNull }?.asString
            ?.split('/')?.takeIf { it.size == 2 }
            ?.let { ModelRef(it[0], it[1]) }
        CliConfig(providers, main)
    }.getOrNull()
}
