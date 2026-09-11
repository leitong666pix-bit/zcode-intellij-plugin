package zcode.idea.commands

import java.io.File

/**
 * 斜杠命令的发现、解析与展开。纯逻辑、无 IDE 依赖（headless 可测）。
 *
 * 协议事实（见 docs/PROTOCOL.md）：服务端只在 submitPrompt 里拦截 `/compact` 与 `/fork`，
 * 其余 "/" 文本按普通 prompt 处理；**自定义命令服务端不展开**，客户端须自己读
 * `.zcode/commands` 目录下的 .md 文件、展开 `$ARGUMENTS`/`$1..$9` 占位符后作为普通消息发送。
 * 本对象的行为对齐 zcode CLI 的扫描与展开规则。
 */
object SlashCommands {

    /**
     * 保留名：CLI 内置命令（含别名）+ compress/plan。自定义命令与它们重名时
     * 不进入命令列表（对齐 CLI：同名时内置优先，自定义命令从交互菜单里过滤掉）。
     */
    val RESERVED_NAMES: Set<String> = setOf(
        "help", "login", "logout", "compact", "init", "expert", "effort", "variant",
        "workflow", "workflows", "fork", "locale", "language", "mcp", "plugins", "plugin",
        "mode", "model", "new", "clear", "resume", "continue", "rewind", "skill",
        "goal", "target", "compress", "plan",
    )

    /** 命令名规则：小写字母/数字开头，后接小写字母/数字/下划线/冒号/连字符，总长 1..64。 */
    private val NAME_REGEX = Regex("^[a-z0-9][a-z0-9_:-]{0,63}$")

    /** 一个可用的斜杠命令。builtin 来自服务端快照，custom 来自本地 .md 文件，local 由插件自己处理。 */
    data class CommandDef(
        val name: String,
        val description: String,
        val inputHint: String? = null,
        val source: String, // builtin | custom | local
        val body: String? = null,
    )

    /** doSend 对一条 "/" 开头输入的路由结果。 */
    sealed class Route {
        object NotCommand : Route()
        data class Local(val name: String) : Route()
        data class Custom(val def: CommandDef, val rawArgs: String) : Route()
        data class Server(val name: String) : Route()
        data class Unknown(val name: String) : Route()
    }

    /** 插件本地处理、不发给服务端的命令。 */
    val LOCAL_COMMANDS: List<CommandDef> = listOf(
        CommandDef("new", "开始新会话（等同 /clear）", source = "local"),
        CommandDef("clear", "开始新会话（等同 /new）", source = "local"),
        CommandDef("help", "查看可用命令", source = "local"),
    )

    /**
     * 扫描自定义命令：按 [commandRoots] 的优先级先命中优先，同名去重，
     * 过滤保留名与非法名。文件 IO 失败的目录/文件跳过，不抛异常。
     */
    fun scanCustomCommands(homeDir: File, projectBase: File?): List<CommandDef> {
        val byName = LinkedHashMap<String, CommandDef>()
        for (root in commandRoots(homeDir, projectBase)) {
            if (!root.isDirectory) continue
            val files = runCatching {
                root.walkTopDown().filter { it.isFile && it.extension.equals("md", true) }.toList()
            }.getOrNull() ?: continue
            for (f in files.sortedBy { it.invariantSeparatorsPath }) {
                // 相对路径映射命令名：去掉 .md 后缀，子目录分隔符转 ':'（review/code.md -> review:code）
                val rel = f.toRelativeString(root)
                val stem = if (f.extension.isNotEmpty()) rel.removeSuffix("." + f.extension) else rel
                val name = stem.replace('\\', '/').replace('/', ':').lowercase()
                if (name in byName) continue // 先命中优先
                parseCommandFile(f, name)?.let { byName[name] = it }
            }
        }
        return byName.values.filter { it.name !in RESERVED_NAMES }
    }

    /**
     * 命令根目录（顺序即优先级，先命中优先）：
     * ~/.zcode/commands → ~/.agents/commands → 项目目录自 base 向上到 git 根每级的
     * .zcode/commands、.agents/commands（靠近项目的目录优先于更外层）。
     */
    internal fun commandRoots(homeDir: File, projectBase: File?): List<File> {
        val roots = mutableListOf(File(homeDir, ".zcode/commands"), File(homeDir, ".agents/commands"))
        if (projectBase != null) {
            var dir: File? = projectBase.absoluteFile
            while (dir != null) {
                roots.add(File(dir, ".zcode/commands"))
                roots.add(File(dir, ".agents/commands"))
                // .git 存在（目录或 worktree 的 gitlink 文件均算）说明到顶了
                dir = if (File(dir, ".git").exists()) null else dir.parentFile
            }
        }
        return roots
    }

    /** 解析一个命令文件；名字非法 / 标记 disable-noninteractive / 既无描述也无正文 时返回 null。 */
    internal fun parseCommandFile(file: File, name: String): CommandDef? {
        if (!NAME_REGEX.matches(name)) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val (meta, body) = parseFrontmatter(text)
        if (meta.containsKey("disable-noninteractive")) return null
        val trimmedBody = body.trim()
        val description = meta["description"]?.takeIf { it.isNotBlank() }
            ?: trimmedBody.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return null
        return CommandDef(
            name = name,
            description = description,
            inputHint = meta["argument-hint"]?.takeIf { it.isNotBlank() },
            source = "custom",
            body = trimmedBody,
        )
    }

    /**
     * 平铺 frontmatter 解析：文件以 `---` 行开头、到下一个 `---` 行为止的 `key: value`。
     * 缩进/多行值不支持（与 CLI 一致：非法 frontmatter 的键直接丢弃）；
     * 没有闭合 `---` 时整体视作正文。返回 (meta, body)。
     */
    internal fun parseFrontmatter(text: String): Pair<Map<String, String>, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to text
        val meta = LinkedHashMap<String, String>()
        var i = 1
        while (i < lines.size) {
            if (lines[i].trim() == "---") return meta to lines.drop(i + 1).joinToString("\n")
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val key = lines[i].substring(0, idx).trim()
                val value = lines[i].substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) meta[key] = value
            }
            i++
        }
        // 没有闭合 ---：不是合法 frontmatter，整体当正文
        return emptyMap<String, String>() to text
    }

    /** 引号感知分词：`a "b c" 'd'` → [a, `b c`, d]。引号内的空白不切分。 */
    fun splitArgs(text: String): List<String> {
        val args = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        fun flush() {
            if (hasToken) {
                args.add(sb.toString())
                sb.clear()
                hasToken = false
            }
        }
        for (c in text) {
            when {
                quote != null -> if (c == quote) quote = null else sb.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    hasToken = true // 允许空参数 ""
                }
                c.isWhitespace() -> flush()
                else -> {
                    sb.append(c)
                    hasToken = true
                }
            }
        }
        flush()
        return args
    }

    private val POSITIONAL = Regex("\\$([1-9])(?!\\d)")

    /**
     * 展开正文占位符：`$ARGUMENTS` → 完整参数串；`$1`..`$9` → 位置参数（引号感知分词）。
     * 带了参数但正文没有任何占位符时，把参数追加到末尾（对齐 CLI）。
     */
    fun expandBody(body: String, rawArgs: String): String {
        val hasPlaceholder = body.contains("\$ARGUMENTS") || POSITIONAL.containsMatchIn(body)
        var expanded = body.replace("\$ARGUMENTS", rawArgs)
        val positional = splitArgs(rawArgs)
        expanded = POSITIONAL.replace(expanded) { m -> positional.getOrNull(m.groupValues[1].toInt() - 1) ?: "" }
        if (rawArgs.isNotBlank() && !hasPlaceholder) {
            expanded += "\n\nUser arguments:\n$rawArgs"
        }
        return expanded
    }

    /** 自定义命令最终提交给服务端的 prompt：一行指令头 + 空行 + 展开后的正文。 */
    fun buildCommandPrompt(def: CommandDef, rawArgs: String): String {
        val expanded = expandBody(def.body ?: "", rawArgs)
        return "Run custom command /${def.name}.\n\n$expanded"
    }

    /**
     * 输入文本 → 路由。本地命令（new/clear/help）插件自己处理；
     * 自定义命令客户端展开后发送；服务端内置（快照 slashCommands + /fork）原样发送
     * （服务端只拦截 /compact、/fork，其余内置作为普通 prompt 由模型理解）；未知命令拒发。
     */
    fun classify(text: String, custom: List<CommandDef>, builtinNames: Collection<String>): Route {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return Route.NotCommand
        val rest = trimmed.removePrefix("/")
        val name = rest.substringBefore(' ').lowercase()
        if (name.isEmpty()) return Route.Unknown("")
        if (name == "new" || name == "clear" || name == "help") return Route.Local(name)
        val def = custom.firstOrNull { it.name == name }
        if (def != null) {
            val rawArgs = rest.substringAfter(' ', "").trim()
            return Route.Custom(def, rawArgs)
        }
        if (name in builtinNames || name == "fork") return Route.Server(name)
        return Route.Unknown(name)
    }
}
