package zcode.idea.vfs

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import zcode.idea.core.ChangedFile
import java.io.File

/** 用 IDE 原生 diff 查看会话内被 zcode 修改的文件（对比首次修改前内容）。 */
object DiffOpener {

    fun show(project: Project, changed: ChangedFile) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(changed.path))
            if (vf == null) {
                Messages.showInfoMessage("文件不存在：${changed.path}", "ZCode")
                return@invokeLater
            }
            val factory = DiffContentFactory.getInstance()
            val old = changed.oldContent ?: "" // 新建文件：修改前为空
            val c1 = factory.create(old)
            val c2 = factory.create(project, vf)
            val relPath = project.basePath?.let { base ->
                changed.path.replace('\\', '/').removePrefix(base.replace('\\', '/').trimEnd('/') + "/")
            } ?: changed.path
            DiffManager.getInstance().showDiff(
                project,
                SimpleDiffRequest("ZCode 修改: $relPath", c1, c2, "修改前（${changed.toolName}）", "当前"),
            )
        }
    }
}
