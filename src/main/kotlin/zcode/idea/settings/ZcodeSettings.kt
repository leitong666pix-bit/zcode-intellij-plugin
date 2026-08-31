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

    enum class Mode(val id: String, val label: String) {
        BUILD("build", "build（构建：默认审批）"),
        EDIT("edit", "edit（编辑：文件修改需审批）"),
        PLAN("plan", "plan（只读规划）"),
        YOLO("yolo", "yolo（全自动，谨慎）");

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
