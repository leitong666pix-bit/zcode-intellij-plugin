package zcode.idea.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * SlashCommands 纯逻辑验证：目录扫描与优先级、frontmatter 解析、保留名/非法名过滤、
 * 占位符展开与引号感知分词、classify 路由。全部走临时目录，无 IDE 依赖。
 */
class SlashCommandsTest {

    @TempDir
    lateinit var home: File

    @TempDir
    lateinit var project: File

    private fun write(dir: File, relPath: String, content: String): File =
        File(dir, relPath).apply { parentFile.mkdirs(); writeText(content) }

    @Test
    fun `scan finds commands from user and project roots`() {
        write(home, ".zcode/commands/explain.md", "---\ndescription: 解释代码\n---\n请解释这段代码。")
        write(home, ".agents/commands/other.md", "另一个命令的第一行说明\n正文")
        write(project, ".zcode/commands/review/code.md", "---\ndescription: 审查代码\n---\n审查 $1")

        val cmds = SlashCommands.scanCustomCommands(home, project)
        assertEquals(listOf("explain", "other", "review:code"), cmds.map { it.name }.sorted())
        val explain = cmds.first { it.name == "explain" }
        assertEquals("解释代码", explain.description)
        assertEquals("请解释这段代码。", explain.body)
        // 无 frontmatter：description 回退为正文第一个非空行
        assertEquals("另一个命令的第一行说明", cmds.first { it.name == "other" }.description)
    }

    @Test
    fun `user scope wins over project for same name`() {
        write(home, ".zcode/commands/fix.md", "---\ndescription: 用户级\n---\nHOME")
        write(project, ".zcode/commands/fix.md", "---\ndescription: 项目级\n---\nPROJECT")

        val cmds = SlashCommands.scanCustomCommands(home, project)
        assertEquals(1, cmds.size)
        assertEquals("用户级", cmds[0].description)
    }

    @Test
    fun `reserved and invalid names are filtered`() {
        write(home, ".zcode/commands/compact.md", "---\ndescription: 占用内置名\n---\nbody")
        write(home, ".zcode/commands/model.md", "---\ndescription: 占用内置名\n---\nbody")
        write(home, ".zcode/commands/a b.md", "名字带空格，非法")
        write(home, ".zcode/commands/ok.md", "---\ndescription: 合法\n---\nbody")

        val names = SlashCommands.scanCustomCommands(home, project).map { it.name }
        assertEquals(listOf("ok"), names)
    }

    @Test
    fun `disable-noninteractive and empty commands are dropped`() {
        write(home, ".zcode/commands/hidden.md", "---\ndescription: x\ndisable-noninteractive: true\n---\nbody")
        write(home, ".zcode/commands/empty.md", "   \n\n")

        assertTrue(SlashCommands.scanCustomCommands(home, project).isEmpty())
    }

    @Test
    fun `frontmatter unterminated block is treated as body`() {
        val (meta, body) = SlashCommands.parseFrontmatter("---\ndescription: 没有闭合\n正文行")
        assertTrue(meta.isEmpty())
        assertTrue(body.startsWith("---"))
    }

    @Test
    fun `splitArgs respects quotes`() {
        assertEquals(listOf("a", "b c", "d"), SlashCommands.splitArgs("a \"b c\" d"))
        assertEquals(listOf("fix", "42 43"), SlashCommands.splitArgs("fix '42 43'"))
        assertEquals(emptyList<String>(), SlashCommands.splitArgs("   "))
    }

    @Test
    fun `expandBody replaces placeholders`() {
        val body = "审查 $1 和 \$2，全部：\$ARGUMENTS，越界：\$3"
        val expanded = SlashCommands.expandBody(body, "a \"b c\"")
        assertEquals("审查 a 和 b c，全部：a \"b c\"，越界：", expanded)
    }

    @Test
    fun `expandBody appends args when no placeholder`() {
        val expanded = SlashCommands.expandBody("没有占位符的正文", "foo bar")
        assertEquals("没有占位符的正文\n\nUser arguments:\nfoo bar", expanded)
        // 没有参数时不追加
        assertEquals("没有占位符的正文", SlashCommands.expandBody("没有占位符的正文", ""))
    }

    @Test
    fun `buildCommandPrompt wraps body with command header`() {
        val def = SlashCommands.CommandDef("review:code", "审查", null, "custom", "审查 \$ARGUMENTS")
        assertEquals("Run custom command /review:code.\n\n审查 fix 42", SlashCommands.buildCommandPrompt(def, "fix 42"))
    }

    @Test
    fun `classify routes local custom server and unknown`() {
        val custom = listOf(SlashCommands.CommandDef("review:code", "审查", null, "custom", "body"))
        val builtins = setOf("goal", "compact", "init", "plan")

        assertTrue(SlashCommands.classify("普通消息", custom, builtins) is SlashCommands.Route.NotCommand)
        assertEquals("new", (SlashCommands.classify("/new", custom, builtins) as SlashCommands.Route.Local).name)
        assertEquals("clear", (SlashCommands.classify("/clear ", custom, builtins) as SlashCommands.Route.Local).name)
        assertEquals("help", (SlashCommands.classify("/help", custom, builtins) as SlashCommands.Route.Local).name)

        val customRoute = SlashCommands.classify("/review:code fix 42", custom, builtins) as SlashCommands.Route.Custom
        assertEquals("review:code", customRoute.def.name)
        assertEquals("fix 42", customRoute.rawArgs)

        assertEquals("compact", (SlashCommands.classify("/compact 保留 API 设计", custom, builtins) as SlashCommands.Route.Server).name)
        assertEquals("fork", (SlashCommands.classify("/fork latest", custom, builtins) as SlashCommands.Route.Server).name)

        assertEquals("nope", (SlashCommands.classify("/nope", custom, builtins) as SlashCommands.Route.Unknown).name)
    }

    @Test
    fun `commandRoots walks project up to git root`() {
        val base = File(project, "a/b/c").apply { mkdirs() }
        write(project, ".git", "") // git 根（文件形式，worktree gitlink 也算）
        val roots = SlashCommands.commandRoots(home, base).map { it.invariantSeparatorsPath }

        val homeZcode = File(home, ".zcode/commands").invariantSeparatorsPath
        assertEquals(homeZcode, roots.first())
        // 项目链：c → b → a → project（.git 处停），每级 .zcode/.agents 各一个，之后不再向上
        val chain = roots.drop(2)
        assertEquals(8, chain.size)
        assertTrue(chain.contains(File(project, ".zcode/commands").invariantSeparatorsPath))
        val parentOfProject = project.parentFile
        if (parentOfProject != null) {
            assertFalse(chain.contains(File(parentOfProject, ".zcode/commands").invariantSeparatorsPath))
        }
    }

    @Test
    fun `null project base only yields home roots`() {
        assertEquals(2, SlashCommands.commandRoots(home, null).size)
    }

    @Test
    fun `parseCommandFile rejects bad name and keeps argument hint`() {
        val f = write(home, ".zcode/commands/tmp.md", "---\ndescription: d\nargument-hint: /tmp [x]\n---\nbody")
        val def = SlashCommands.parseCommandFile(f, "good-name")!!
        assertEquals("d", def.description)
        assertEquals("/tmp [x]", def.inputHint)
        assertNull(SlashCommands.parseCommandFile(f, "Bad Name"))
    }
}
