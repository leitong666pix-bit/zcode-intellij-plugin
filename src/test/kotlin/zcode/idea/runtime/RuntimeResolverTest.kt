package zcode.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zcode.idea.settings.ZcodeSettings
import java.io.File

/** RuntimeResolver 的路径优先级与版本比较验证（不依赖本机环境，用临时文件注入）。 */
class RuntimeResolverTest {

    @TempDir
    lateinit var tmp: File

    private fun settings(nodePath: String = "", runtimePath: String = ""): ZcodeSettings =
        ZcodeSettings().apply {
            loadState(ZcodeSettings.State(nodePath = nodePath, runtimePath = runtimePath))
        }

    @Test
    fun `configured node path wins when file exists`() {
        val node = File(tmp, "node.exe").apply { writeText("") }
        assertEquals(node.absolutePath, RuntimeResolver.findNode(settings(nodePath = node.absolutePath))?.first)
    }

    @Test
    fun `configured runtime path wins when file exists`() {
        val runtime = File(tmp, "zcode.cjs").apply { writeText("") }
        assertEquals(
            runtime.absolutePath,
            RuntimeResolver.findRuntimeScript(settings(runtimePath = runtime.absolutePath))?.first,
        )
    }

    @Test
    fun `versionAtLeast handles major minor and patch`() {
        assertTrue(RuntimeResolver.versionAtLeast("22.19.0", 22, 19))
        assertTrue(RuntimeResolver.versionAtLeast("23.0.0", 22, 19))
        assertTrue(RuntimeResolver.versionAtLeast("v22.20", 22, 19))
        assertFalse(RuntimeResolver.versionAtLeast("22.5.0", 22, 19))
        assertFalse(RuntimeResolver.versionAtLeast("21.99.9", 22, 19))
        assertFalse(RuntimeResolver.versionAtLeast("abc", 22, 19))
    }
}
