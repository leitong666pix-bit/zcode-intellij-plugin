package zcode.idea.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import javax.swing.JComponent

/** Resolves references off EDT, using the IDE's installed language contributors rather than a Java-only dependency. */
internal class ReferenceNavigator(
    private val project: Project,
    private val owner: JComponent,
    private val disposed: () -> Boolean,
    private val notice: (String) -> Unit,
) {
    private data class Target(val file: VirtualFile, val offset: Int = 0, val label: String)

    private fun <T : Any> resolve(action: () -> T, onResult: (T) -> Unit) {
        if (project.isDisposed || disposed()) return
        ReadAction.nonBlocking<T> { action() }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { disposed() }
            .finishOnUiThread(ModalityState.any()) { result ->
                if (!project.isDisposed && !disposed()) onResult(result)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun openFile(ref: FileReference) {
        resolve({ files(ref.path) }) { candidates ->
            choose(candidates, "选择文件：" + ref.path) { target -> navigateFile(target.file, ref) }
        }
    }

    private fun files(path: String): List<Target> {
        val local = LocalFileSystem.getInstance()
        val normalized = path.replace('\\', '/')
        val absolute = File(normalized)
        if (absolute.isAbsolute) {
            val found = local.findFileByIoFile(absolute)
            return found?.takeIf { !it.isDirectory }?.let { listOf(Target(it, label = it.path)) } ?: emptyList()
        }
        project.basePath?.let { base ->
            local.findFileByIoFile(File(base, normalized))?.takeIf { !it.isDirectory }?.let {
                return listOf(Target(it, label = relative(it)))
            }
        }
        val matches = FilenameIndex.getVirtualFilesByName(normalized.substringAfterLast('/'), GlobalSearchScope.projectScope(project))
            .filter { !it.isDirectory }
        val paths = ReferenceTargets.preferMatchingSuffix(normalized, matches.map { it.path })
        return matches.filter { it.path in paths }.sortedBy { it.path }.map { Target(it, label = relative(it)) }
    }

    private fun relative(file: VirtualFile): String =
        project.basePath?.replace('\\', '/')?.let { file.path.removePrefix(it.trimEnd('/') + "/") } ?: file.path

    private fun navigateFile(file: VirtualFile, ref: FileReference) {
        if (!file.isValid) { notice("文件已移动或删除：" + ref.path); return }
        val descriptor = OpenFileDescriptor(project, file, (ref.startLine ?: 1) - 1, 0)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor == null) { descriptor.navigate(true); return }
        val range = ReferenceTargets.lineOffsets(editor.document, ref)
        editor.caretModel.moveToOffset(range.first)
        if (ref.endLine != null) editor.selectionModel.setSelection(range.first, range.second)
        else editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        if ((ref.startLine ?: 1) > editor.document.lineCount || (ref.endLine ?: 1) > editor.document.lineCount) {
            notice("文件行数已变化，已定位到当前可用范围：" + ref.path)
        }
    }

    fun openSymbol(text: String) {
        resolve({ symbols(text) }) { candidates ->
            choose(candidates, "选择定义：" + text) { target ->
                if (target.file.isValid) OpenFileDescriptor(project, target.file, target.offset).navigate(true)
                else notice("目标文件已移动或删除，请重新点击引用")
            }
        }
    }

    private fun symbols(text: String): List<Target> {
        val name = SymbolRefs.query(text) ?: return emptyList()
        val ownerName = text.removeSuffix("()").let {
            if (it.contains('.') || it.contains('#')) it.substringBeforeLast('.').substringBeforeLast('#') else null
        }
        val contributors = (ChooseByNameContributor.CLASS_EP_NAME.extensionList +
            ChooseByNameContributor.SYMBOL_EP_NAME.extensionList).distinct()
        return contributors.flatMap { contributor ->
            try {
                if (contributor is ChooseByNameContributorEx) {
                    val items = mutableListOf<NavigationItem>()
                    contributor.processElementsWithName(name, { item -> items.add(item); true },
                        FindSymbolParameters.wrap(name, GlobalSearchScope.projectScope(project)))
                    items
                } else {
                    contributor.getItemsByName(name, name, project, false).asList()
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }.asSequence().filter { it.name == name }.mapNotNull { item ->
            val element = (item as? PsiElement)?.navigationElement ?: return@mapNotNull null
            if (!element.isValid) return@mapNotNull null
            if (ownerName != null && generateSequence(element.parent) { it.parent }
                    .filterIsInstance<PsiNamedElement>().none { it.name == ownerName }) return@mapNotNull null
            val file = element.containingFile?.virtualFile ?: return@mapNotNull null
            if (!GlobalSearchScope.projectScope(project).contains(file)) return@mapNotNull null
            val label = item.presentation?.presentableText ?: name
            Target(file, element.textOffset, label + " — " + relative(file))
        }.distinctBy { it.file.path to it.offset }.sortedWith(compareBy({ it.file.path }, { it.offset })).toList()
    }

    private fun choose(candidates: List<Target>, title: String, navigate: (Target) -> Unit) {
        when (candidates.size) {
            0 -> notice("当前项目中未找到对应位置：" + title.substringAfter('：'))
            1 -> navigate(candidates.single())
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(candidates)
                .setTitle(title)
                .setRenderer(SimpleListCellRenderer.create<Target> { label, item, _ -> label.text = item?.label ?: "" })
                .setItemChosenCallback { target -> navigate(target) }
                .createPopup().showInCenterOf(owner)
        }
    }

    /** Completed answers only: unresolved inline identifiers stay plain code, including during indexing. */
    fun decorateSymbols(html: String, onResult: (String) -> Unit) {
        val identifiers = SymbolRefs.candidatesIn(html)
        if (identifiers.isEmpty()) return
        resolve({
            identifiers.associateWith { text ->
                symbols(text).map { it.label }
            }
        }) { resolved -> onResult(SymbolRefs.decorate(html, resolved)) }
    }
}

internal object ReferenceTargets {
    fun preferMatchingSuffix(reference: String, paths: List<String>): List<String> {
        val suffix = reference.replace('\\', '/').removePrefix("./")
        val exact = paths.filter { it.replace('\\', '/').endsWith("/" + suffix, ignoreCase = File.separatorChar == '\\') }
        return exact.ifEmpty { paths }
    }

    fun lineOffsets(document: Document, ref: FileReference): Pair<Int, Int> {
        val last = (document.lineCount - 1).coerceAtLeast(0)
        val firstLine = ((ref.startLine ?: 1) - 1).coerceIn(0, last)
        val lastLine = ((ref.endLine ?: ref.startLine ?: 1) - 1).coerceIn(firstLine, last)
        return document.getLineStartOffset(firstLine) to document.getLineEndOffset(lastLine)
    }
}
