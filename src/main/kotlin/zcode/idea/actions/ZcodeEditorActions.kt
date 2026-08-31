package zcode.idea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import zcode.idea.context.SelectionContext
import zcode.idea.core.ZcodeSessionService
import zcode.idea.ui.ChatPanelRegistry

/** 编辑器右键动作基类：激活工具窗口并把模板指令发给 zcode。 */
abstract class ZcodeEditorAction(text: String, description: String) : AnAction(text, description, null) {

    abstract fun buildPrompt(selection: String?, fileName: String?): String

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        activateToolWindow(project)
        val prompt = buildPrompt(editor?.selectionModel?.selectedText, file?.name)
        project.getService(ZcodeSessionService::class.java).send(prompt)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    private fun activateToolWindow(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow("ZCode")?.activate(null)
    }
}

/**
 * 引用选中代码到对话：不立即发送，而是把选区挂为待发送上下文，
 * 聚焦聊天输入框，等用户补充问题后随消息一起发出。
 */
class AttachSelectionAction :
    AnAction("引用选中代码到对话...", "把选中的代码作为上下文引用，在 ZCode 对话框中继续提问", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val info = SelectionContext.captureSelection(project) ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZCode") ?: return
        // 等工具窗口真正激活、面板已创建后再投递，否则首次打开时可能拿不到面板
        toolWindow.activate {
            ChatPanelRegistry.forProject(project)?.attachSelection(info)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.selectionModel.hasSelection()
    }
}

class ExplainSelectionAction : ZcodeEditorAction("解释选中的代码", "让 ZCode 解释当前选中的代码") {
    override fun buildPrompt(selection: String?, fileName: String?): String =
        if (selection != null) "请解释这段选中的代码${fileName?.let { "（$it）" } ?: ""}的作用、关键逻辑和潜在问题。"
        else "请解释当前活动文件的整体结构和主要逻辑。"
}

class ImproveSelectionAction : ZcodeEditorAction("优化选中的代码", "让 ZCode 优化当前选中的代码") {
    override fun buildPrompt(selection: String?, fileName: String?): String =
        if (selection != null) "请优化这段选中的代码${fileName?.let { "（$it）" } ?: ""}：提升可读性、性能或健壮性，并说明改动理由。若无必要修改请说明。"
        else "请审查当前活动文件，指出可以优化的地方（不直接修改文件，先给建议）。"
}

class WriteTestsAction : ZcodeEditorAction("为选中代码写测试", "让 ZCode 为选中的代码编写测试") {
    override fun buildPrompt(selection: String?, fileName: String?): String =
        "请为以下${if (selection != null) "选中的代码" else "活动文件"}编写单元测试：遵循项目现有测试框架，写入合适的测试文件位置。"
}

class CustomPromptAction : AnAction("自定义指令...", "输入自定义指令发送给 ZCode", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val input = Messages.showMultilineInputDialog(
            project,
            "要向 ZCode 下达什么指令？",
            "自定义指令",
            "",
            null,
            null,
        )?.trim() ?: return
        if (input.isEmpty()) return
        ToolWindowManager.getInstance(project).getToolWindow("ZCode")?.activate(null)
        project.getService(ZcodeSessionService::class.java).send(input)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
