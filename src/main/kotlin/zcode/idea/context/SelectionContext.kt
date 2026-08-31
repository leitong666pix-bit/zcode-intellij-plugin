package zcode.idea.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * 发送消息时采集 IDE 上下文（活动编辑器、选区、打开的文件），
 * 生成附加到 prompt 的上下文块。只在实际发送时按需读取，无需常驻监听。
 */
object SelectionContext {

    /** 上下文块起始标记：注入 prompt 时以此为界，恢复历史时据此把上下文折叠展示。 */
    const val CONTEXT_MARKER = "--- IDE 上下文（由 IDEA 插件自动附加）---"

    data class Info(
        val activeFile: VirtualFile?,
        val relativePath: String?,
        val selectionText: String?,
        val startLine: Int?,
        val endLine: Int?,
        val openFiles: List<String>,
    )

    fun capture(project: Project): Info? = ReadAction.compute<Info?, Exception> {
        val fem = FileEditorManager.getInstance(project)
        val editor: Editor = fem.selectedTextEditor ?: return@compute null
        val file: VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)
        val selectionModel = editor.selectionModel
        val selectedText: String? = selectionModel.selectedText?.takeIf { it.isNotBlank() }
        var startLine: Int? = null
        var endLine: Int? = null
        if (selectedText != null) {
            val doc: Document = editor.document
            startLine = doc.getLineNumber(selectionModel.selectionStart) + 1
            endLine = doc.getLineNumber(selectionModel.selectionEnd) + 1
        }
        val base = project.baseDir
        Info(
            activeFile = file,
            relativePath = file?.let { relPath(it, base) },
            selectionText = selectedText,
            startLine = startLine,
            endLine = endLine,
            openFiles = fem.openFiles.take(12).map { relPath(it, base) },
        )
    }

    /** 只在有非空选区时返回 Info（右键"引用选中代码"用）。 */
    fun captureSelection(project: Project): Info? =
        capture(project)?.takeIf { !it.selectionText.isNullOrBlank() }

    private fun relPath(file: VirtualFile, base: VirtualFile?): String =
        base?.let { VfsUtil.getRelativePath(file, it) } ?: file.path

    /**
     * 把历史消息里的完整文本拆成 (用户输入, 上下文块)。
     * 没有上下文块时第二项为 null。
     */
    fun splitContext(text: String): Pair<String, String?> {
        val idx = text.indexOf(CONTEXT_MARKER)
        if (idx < 0) return text to null
        val prompt = text.substring(0, idx).trimEnd()
        return (if (prompt.isEmpty()) "（仅发送了 IDE 上下文）" else prompt) to text.substring(idx).trim()
    }

    /**
     * 生成注入到 prompt 末尾的上下文块；无可用上下文时返回 null。
     */
    fun buildBlock(info: Info, maxSelectionChars: Int): String? {
        if (info.activeFile == null && info.openFiles.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("\n\n").append(CONTEXT_MARKER).append('\n')
        if (info.relativePath != null) {
            sb.append("当前活动文件: ").append(info.relativePath)
            if (info.startLine != null && info.endLine != null) sb.append("（选区行 ").append(info.startLine).append('-').append(info.endLine).append('）')
            sb.append('\n')
        }
        appendSelection(sb, info, maxSelectionChars)
        if (info.openFiles.isNotEmpty()) {
            sb.append("当前打开的文件: ").append(info.openFiles.joinToString(", ")).append('\n')
        }
        return sb.toString().trimEnd().plus('\n')
    }

    /** 用户显式"引用选中代码"时的上下文块：只含选区，不带打开文件列表等噪音。 */
    fun buildSelectionBlock(info: Info, maxSelectionChars: Int): String? {
        val selection = info.selectionText ?: return null
        val sb = StringBuilder()
        sb.append("\n\n").append(CONTEXT_MARKER).append('\n')
        sb.append("用户引用的选区: ").append(info.relativePath ?: info.activeFile?.path ?: "")
        if (info.startLine != null && info.endLine != null) sb.append("（第 ").append(info.startLine).append('-').append(info.endLine).append(" 行）")
        sb.append('\n')
        appendSelection(sb, info.copy(selectionText = selection), maxSelectionChars)
        return sb.toString().trimEnd().plus('\n')
    }

    private fun appendSelection(sb: StringBuilder, info: Info, maxSelectionChars: Int) {
        val selection = info.selectionText ?: return
        val lang = info.activeFile?.extension ?: ""
        val trimmed = if (selection.length > maxSelectionChars) {
            selection.take(maxSelectionChars) + "\n…（已截断，完整内容请自行读取文件）"
        } else selection
        sb.append("用户选中的代码:\n```").append(lang).append('\n').append(trimmed).append("\n```\n")
    }
}
