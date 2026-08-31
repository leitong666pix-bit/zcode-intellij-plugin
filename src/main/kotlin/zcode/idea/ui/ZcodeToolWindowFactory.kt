package zcode.idea.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** 面板实例注册表：编辑器右键动作据此把选区上下文投递到当前项目的聊天面板。 */
object ChatPanelRegistry {
    private val panels = java.util.concurrent.ConcurrentHashMap<Project, ChatPanel>()

    fun register(project: Project, panel: ChatPanel) { panels[project] = panel }
    fun unregister(project: Project) { panels.remove(project) }
    fun forProject(project: Project): ChatPanel? = panels[project]
}

class ZcodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        ChatPanelRegistry.register(project, panel)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer {
            ChatPanelRegistry.unregister(project)
            panel.dispose()
        }
        content.setPreferredFocusableComponent(panel)
        toolWindow.contentManager.addContent(content)
    }
}
