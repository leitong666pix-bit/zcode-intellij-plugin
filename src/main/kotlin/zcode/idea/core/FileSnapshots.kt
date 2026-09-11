package zcode.idea.core

import java.io.File
import java.nio.file.Files

sealed interface BeforeContent {
    data class Captured(val text: String) : BeforeContent
    data object NewFile : BeforeContent
    data class Unavailable(val reason: String) : BeforeContent
}

/** Retains the first pre-write snapshot, sharing it between pending tools and completed changes. */
internal class FileSnapshots(
    private val singleLimitBytes: Long = 16L * 1024 * 1024,
    private val totalLimitBytes: Long = 64L * 1024 * 1024,
) {
    private data class Pending(val path: String, val before: BeforeContent)
    private val pending = LinkedHashMap<String, Pending>()
    private val changed = LinkedHashMap<String, ChangedFile>()

    private fun key(path: String): String = File(path).absoluteFile.normalize().path.let {
        if (File.separatorChar == '\\') it.lowercase(java.util.Locale.ROOT) else it
    }

    /** Conservative UTF-16 text payload accounting; metadata is not included in this budget. */
    @Synchronized fun retainedBytes(): Long {
        val snapshots = pending.values.associate { key(it.path) to it.before }.toMutableMap()
        changed.forEach { (path, file) -> snapshots[path] = file.before }
        return snapshots.values.sumOf { (it as? BeforeContent.Captured)?.text?.length?.toLong()?.times(2) ?: 0L }
    }

    @Synchronized fun capture(toolId: String, file: File) {
        if (pending.containsKey(toolId)) return
        val path = file.absoluteFile.normalize().path
        val pathKey = key(path)
        val existing = changed[pathKey]?.before
            ?: pending.values.firstOrNull { key(it.path) == pathKey }?.before
        val before = existing ?: readBefore(file)
        pending[toolId] = Pending(path, before)
    }

    private fun readBefore(file: File): BeforeContent {
        if (Files.notExists(file.toPath())) return BeforeContent.NewFile
        if (!file.isFile) return BeforeContent.Unavailable("无法读取修改前文件")
        val remaining = (totalLimitBytes - retainedBytes()).coerceAtLeast(0)
        val limit = minOf(singleLimitBytes, remaining).coerceAtLeast(0) / 2
        val reason = if (remaining < singleLimitBytes) "会话快照内存预算已用尽" else "文件超过单个快照上限"
        return runCatching {
            file.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer, 0, minOf(buffer.size.toLong(), limit - text.length + 1).toInt())
                    if (count < 0) break
                    if (text.length.toLong() + count > limit) return BeforeContent.Unavailable(reason)
                    text.append(buffer, 0, count)
                }
                BeforeContent.Captured(text.toString())
            }
        }.getOrElse { BeforeContent.Unavailable("无法读取修改前内容：${it.message ?: "读取失败"}") }
    }

    @Synchronized fun complete(toolId: String, path: String, toolName: String, success: Boolean) {
        val snapshot = pending.remove(toolId)
        if (!success) return
        val resolved = snapshot?.path ?: File(path).absoluteFile.normalize().path
        changed.putIfAbsent(key(resolved), ChangedFile(
            resolved, snapshot?.before ?: BeforeContent.Unavailable("未捕获修改前快照"),
            toolName, System.currentTimeMillis(),
        ))
    }

    @Synchronized fun registerHistory(path: String, toolName: String) {
        val resolved = File(path).absoluteFile.normalize().path
        changed.putIfAbsent(key(resolved), ChangedFile(
            resolved, BeforeContent.Unavailable("历史会话没有修改前的内容快照"),
            toolName, System.currentTimeMillis(), fromHistory = true,
        ))
    }

    @Synchronized fun files(): List<ChangedFile> = changed.values.toList()
    @Synchronized fun clearPending() = pending.clear()
    @Synchronized fun clear() { pending.clear(); changed.clear() }
}
