package zcode.idea.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import zcode.idea.runtime.RuntimeResolver

class ZcodeConfigurable : BoundConfigurable("ZCode") {

    private val settings = ZcodeSettings.getInstance()

    override fun createPanel() = panel {
        row("Node.js 路径 (node.exe):") {
            // 用 (String, Project, FileChooserDescriptor) 这个 242/243/252 共有的重载：
            // 242 只有此老签名，243+ 新增的 descriptor-first 重载在 242 不存在；
            // 252 将老签名标为 ERROR 级弃用（字节码仍在），为兼容下限须抑制
            @Suppress("DEPRECATION_ERROR")
            textFieldWithBrowseButton(
                "选择 node.exe",
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor(),
            )
                .bindText({ settings.state.nodePath }, { settings.state.nodePath = it.trim() })
                .comment("留空则自动探测（PATH / 常见安装位置）。要求 Node >= 22.19。")
        }
        row("zcode runtime 路径 (zcode.cjs):") {
            @Suppress("DEPRECATION_ERROR") // 同上：242 共有签名，252 中须抑制
            textFieldWithBrowseButton(
                "选择 zcode.cjs",
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor(),
            )
                .bindText({ settings.state.runtimePath }, { settings.state.runtimePath = it.trim() })
                .comment("留空则自动探测：zcode-app-cli（npm 全局）→ ZCode 桌面端安装目录。")
        }
        row("默认权限模式:") {
            comboBox(ZcodeSettings.Mode.entries.map { it.label })
                .bindItem(
                    { ZcodeSettings.Mode.fromId(settings.state.defaultMode).label },
                    { selected ->
                        settings.state.defaultMode =
                            ZcodeSettings.Mode.entries.firstOrNull { it.label == selected }?.id
                                ?: ZcodeSettings.Mode.EDIT.id
                    },
                )
                .comment("build/edit：文件修改等有副作用的工具会弹窗审批；plan 只读；yolo 全自动执行（谨慎）。")
        }
        row {
            checkBox("发送消息时自动附带选区/活动文件上下文")
                .bindSelected({ settings.state.injectSelectionContext }, { settings.state.injectSelectionContext = it })
        }
        row("选区注入上限（字符）:") {
            textField()
                .bindText(
                    { settings.state.maxSelectionChars.toString() },
                    { s -> s.toIntOrNull()?.let { settings.state.maxSelectionChars = it.coerceIn(1000, 1_000_000) } },
                )
        }
        row {
            button("检测环境") { checkEnvironment() }
        }
    }

    private fun checkEnvironment() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val node = RuntimeResolver.findNode(settings)
            val runtime = RuntimeResolver.findRuntimeScript(settings)
            val ok = node != null && runtime != null
            val msg = buildString {
                appendLine(if (node != null) "✔ Node: ${node.first}（${node.second}）" else "✘ ${RuntimeResolver.nodeNotFoundMessage()}")
                append(if (runtime != null) "✔ Runtime: ${runtime.first}（${runtime.second}）" else "✘ ${RuntimeResolver.runtimeNotFoundMessage()}")
            }
            ApplicationManager.getApplication().invokeLater {
                Notification(
                    "ZCode",
                    "环境检测",
                    "<html>${msg.lineSequence().joinToString("<br>")}</html>",
                    if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                ).notify(null)
            }
        }
    }
}
