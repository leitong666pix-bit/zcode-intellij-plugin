package zcode.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件全局设置。运行时（node + zcode.cjs）按机器维度配置，故为应用级服务。
 */
@State(name = "ZcodeSettings", storages = [Storage("zcode-idea.xml")])
@Service(Service.Level.APP)
class ZcodeSettings : PersistentStateComponent<ZcodeSettings.State> {

    /**
     * label/desc 逐字取自 ZCode 桌面端 i18n（app.asar 中 mode.label.glm.* / mode.description.glm.*），
     * 与官方客户端显示保持一致；id 是 app-server session/setMode 的协议值，不可改动。
     */
    enum class Mode(val id: String, val label: String, val desc: String) {
        BUILD("build", "变更前确认", "改文件前先问我。"),
        EDIT("edit", "自动编辑", "自动编辑文件。"),
        PLAN("plan", "计划模式", "编辑前先出计划。"),
        YOLO("yolo", "完全访问", "减少确认次数。");

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: EDIT
        }
    }

    data class State(
        /** node.exe 完整路径；空 = 自动探测（PATH / 常见安装位置） */
        var nodePath: String = "",
        /** zcode.cjs（官方 agent runtime）完整路径；空 = 自动探测（zcode-app-cli / ZCode 桌面端） */
        var runtimePath: String = "",
        /** 会话默认权限模式 */
        var defaultMode: String = Mode.EDIT.id,
        /** 发送消息时自动附带当前选区/活动文件上下文 */
        var injectSelectionContext: Boolean = true,
        /** 选区注入的最大字符数 */
        var maxSelectionChars: Int = 8000,
        /** 用户在插件里选定的模型（provider/model 两段式，空 = 用 CLI 配置的默认模型） */
        var preferredModelProvider: String = "",
        var preferredModelId: String = "",
        /** 用户选定的思考强度（low/high/max 等，空 = 服务端默认） */
        var preferredThoughtLevel: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ZcodeSettings =
            ApplicationManager.getApplication().getService(ZcodeSettings::class.java)
    }
}
