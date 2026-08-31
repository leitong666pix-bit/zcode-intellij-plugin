# ZCode IDEA 插件 — 架构与实现技术文档

> 版本：0.1.0 ｜ 对应代码：`D:\Projects\IDEA plugins\zcode-idea-plugin`
> 配套文档：[PROTOCOL.md](PROTOCOL.md)（ZCode Protocol 逆向笔记）、[README.md](../README.md)（构建/使用）

---

## 1. 项目定位与设计目标

本插件把 **ZCode agent runtime**（ZCode 桌面端内置的官方 CLI，`zcode.cjs`）接入 IntelliJ IDEA，提供类似 Claude Code 官方 IDEA 插件的体验：

- 在 IDE 侧边 ToolWindow 中与 zcode **多轮对话**（流式输出、思考过程、工具调用可视化）；
- **选区/活动文件自动注入**对话上下文（对齐 Claude Code 的 "⧉ Selected N lines" 行为）；
- zcode 的工具调用（改文件、跑命令）在 IDE 内**弹窗审批**，默认安全模式；
- zcode 写盘后 IDE **自动感知**（VFS 刷新）、被改文件**一键 diff**（IDE 原生 diff viewer）；
- 会话持久化在 zcode 侧（`~/.zcode/cli/`），支持跨插件/CLI **恢复**。

### 1.1 与 Claude Code 官方插件的架构对比（为什么这么做）

| | Claude Code 官方 IDEA 插件 | 本插件 |
|---|---|---|
| 交互载体 | 集成终端里跑 `claude` TUI | 自建 Swing 聊天面板 |
| 通信协议 | CLI 主动连 IDE 内的 **WebSocket MCP server**（锁文件发现 + token 鉴权，Claude 专属私有协议） | IDE 作为**客户端**驱动 `zcode app-server`（stdio 上的行式 JSON-RPC，ZCode 桌面端同款协议） |
| 选区注入 | IDE→CLI 推送 `selection_changed` 通知 | 发送时**按需读取**编辑器状态拼进 prompt（无需常驻监听） |
| diff 呈现 | CLI 的 Edit 工具回调 IDE 的 `openDiff` MCP 工具（阻塞式） | zcode 直接写盘 → 插件 VFS 刷新 + 首改前快照 → 原生 DiffManager |
| 工具审批 | CLI TUI 内 | IDE 原生对话框（协议的 `interaction/requestPermission`） |

选择后者的原因：zcode CLI **没有** Claude 那套 IDE 发现/连接机制，但其 `app-server` 子命令暴露了与桌面端完全相同的**双向 JSON-RPC 协议**（会话管理 + 事件流 + 权限交互），是最稳定、功能最完整的集成通道；自建 UI 则把选区注入、审批、diff 全部收归插件掌控，不依赖终端。

### 1.2 职责边界（重要）

- **代码理解/搜索/修改归 zcode**：它用自己的 ripgrep/ugrep + Read/Edit/Write 文件级工具 + 自带 LSP 插件（jdtls/tsserver/pyright），把项目当磁盘文件操作，**不经过 IDEA 的索引/PSI**。
- **IDE 上下文与呈现归插件**：选区采集、审批 UI、VFS 同步、diff 展示。
- MVP **不动 MCP、不改 zcode 的 LSP 插件配置**（后期增强见 §11）。

---

## 2. 总体架构

```
┌──────────────────────────── IntelliJ IDEA 进程 ─────────────────────────────┐
│                                                                             │
│  表现层（ui/，全部 EDT）                                                     │
│  ┌───────────────────────────────────────────────────────────────────┐     │
│  │ ZcodeToolWindowFactory → ChatPanel                                │     │
│  │  ├ 工具栏：新会话 / 恢复… / 变更文件(N) / 模式下拉 / 状态标签        │     │
│  │  ├ 消息流：UserMessagePanel / AssistantMessagePanel / ToolCallPanel │     │
│  │  ├ 输入区：JBTextArea + 发送/停止                                   │     │
│  │  └ PermissionDialog（模态审批）                                      │     │
│  └──────────────▲──────────────────────────────┬──────────────────────┘     │
│                 │ Listener 回调(EDT)            │ send()/stop()/...         │
│  集成层（core/，项目级服务）                    ▼                            │
│  ┌───────────────────────────────────────────────────────────────────┐     │
│  │ ZcodeSessionService (@Service(Project), Disposable)               │     │
│  │  ├ 状态机 DISCONNECTED→STARTING→READY⇄RUNNING / DEAD                │     │
│  │  ├ 协议事件翻译：session/event → 语义回调                            │     │
│  │  ├ 权限审批队列（串行弹窗）                                          │     │
│  │  └ 文件追踪：首改前快照 + 变更文件表                                 │     │
│  │        │ 持有                                                       │     │
│  │        ▼                                                            │     │
│  │ AppServerClient（进程内 JSON-RPC 客户端）                            │     │
│  │  ├ reader 线程：逐行解析 stdout，分发 响应/通知/服务器请求            │     │
│  │  └ writer（同步锁）：stdin 写请求/应答                               │     │
│  └──────────────┬────────────────────────────────────────────────────┘     │
│                 │ spawn: node <zcode.cjs> app-server --cwd <项目根>         │
│  支撑层           ▼                                                        │
│  ├ runtime/RuntimeResolver：node + zcode.cjs 自动探测（设置页可覆盖）        │
│  ├ context/SelectionContext：发送时 ReadAction 采集选区/活动文件            │
│  ├ vfs/DiffOpener：DiffManager + DiffContentFactory 原生 diff              │
│  └ settings/ZcodeSettings(+Configurable)：应用级持久化设置                  │
└─────────────────│───────────────────────────────────────────────────────────┘
                  │ stdin（JSONL 请求）        stdout（JSONL 响应/通知/请求）
                  ▼
        ┌───────────────────────────┐
        │ zcode.cjs app-server      │  ← 与 ZCode 桌面端内置 runtime 同源
        │  ├ 会话/事件/权限协议      │     （zcode-app-cli 的 vendor/zcode.cjs
        │  ├ agent loop + 工具执行   │      或桌面端 resources/glm/zcode.cjs）
        │  │   Read/Edit/Write/Bash │
        │  ├ ripgrep/ugrep 搜索      │
        │  └ 自带 LSP 插件（可选）    │
        │        │ Anthropic 兼容 API │
        └────────▼───────────────────┘
         https://api.z.ai/api/anthropic（glm-5.3-flash，
         凭证 ~/.zcode/cli/config.json，与桌面端无关）
```

---

## 3. 目录结构与模块职责

```
zcode-idea-plugin/
├── build.gradle.kts / settings.gradle.kts / gradle.properties     构建配置（§9）
├── docs/
│   ├── PROTOCOL.md               ZCode Protocol 逆向笔记（协议权威参考）
│   ├── probe-artifacts/          三轮协议探针的原始数据（事件样本等）
│   └── ARCHITECTURE.md           本文档
├── src/main/resources/META-INF/plugin.xml   扩展点注册（§4）
├── src/main/resources/icons/zcode.svg       ToolWindow 图标
├── src/main/kotlin/zcode/idea/
│   ├── runtime/RuntimeResolver.kt           ★ 运行时探测
│   ├── core/AppServerClient.kt              ★ JSON-RPC 客户端（传输层）
│   ├── core/ZcodeSessionService.kt          ★ 会话服务（领域层/粘合层）
│   ├── context/SelectionContext.kt          IDE 上下文采集
│   ├── vfs/DiffOpener.kt                    原生 diff 呈现
│   ├── ui/ZcodeToolWindowFactory.kt         ToolWindow 入口
│   ├── ui/ChatPanel.kt                      聊天面板（最大 UI 文件）
│   ├── ui/MessageComponents.kt              三种消息组件
│   ├── ui/PermissionDialog.kt               审批对话框
│   ├── actions/ZcodeEditorActions.kt        编辑器右键动作组
│   └ settings/ZcodeSettings.kt / ZcodeConfigurable.kt   设置
└── src/test/kotlin/zcode/idea/core/AppServerClientTest.kt   传输层单测（管道假进程）
```

依赖极简：**只依赖 `com.intellij.modules.platform`**（不依赖 Java/Ultimate 模块），JSON 用平台自带的 Gson（`com.google.gson` 为平台对外可用的第三方库），UI 全 Swing/JBUI，无任何额外第三方依赖。

---

## 4. 插件注册（plugin.xml）

```xml
<toolWindow id="ZCode" anchor="right" factoryClass="zcode.idea.ui.ZcodeToolWindowFactory"
            icon="icons/zcode.svg" canCloseContents="false"/>
<applicationConfigurable parentId="tools" displayName="ZCode"
                         instance="zcode.idea.settings.ZcodeConfigurable"/>
<notificationGroup id="ZCode" displayType="BALLOON"/>
<group id="Zcode.EditorActions" popup="true" text="ZCode">
    <action id="Zcode.ExplainSelection" .../>   解释选中的代码
    <action id="Zcode.ImproveSelection" .../>   优化选中的代码
    <action id="Zcode.WriteTests" .../>         为选中代码写测试
    <action id="Zcode.CustomPrompt" .../>       自定义指令...（多行输入对话框）
    <add-to-group group-id="EditorPopupMenu" anchor="first"/>
</group>
```

- 服务不注册在 XML：`ZcodeSessionService` 用 `@Service(Service.Level.PROJECT)` 注解（Kotlin 构造注入 `Project`），实现 `com.intellij.openapi.Disposable`，随项目关闭自动 `dispose()` → 杀子进程。
- `ZcodeSettings` 用 `@Service(Service.Level.APP)` + `@State(storages=[Storage("zcode-idea.xml")])`（运行时路径是机器属性，故为应用级）。

---

## 5. 传输层：`core/AppServerClient.kt`

与 `node zcode.cjs app-server` 之间**每行一个 JSON** 的全双工通道（协议细节见 PROTOCOL.md §信封）。

### 5.1 消息分拣（reader 线程）

```
stdout 一行 JSON
 ├─ 有 method + id，无 result/error ──► 服务器请求（id 形如 "server-1"）
 │      转交 Listener.onRequest(id, method, params, responder)
 │      responder(result?) 稍后在任意线程回写 {"id":..,"result":..}
 │      （协议要求必须应答；无 Listener 时回 -32601）
 ├─ 有 method 无 id ───────────────────► 通知（session/event、state.updated）
 │      转交 Listener.onNotification(method, params)
 └─ 有 id 无 method ───────────────────► 我的请求的响应
        pending.remove(id) → error ? completeExceptionally(RpcException) : complete(result)
```

关键实现点：

- **请求表**：`ConcurrentHashMap<Int, CompletableFuture<JsonObject>>` + `AtomicInteger nextId`；`request()` 写行后返回 future，60~90s 超时由调用方 `get(n, SECONDS)` 控制。
- **写互斥**：`writeLine()` 用 `writerLock` 同步（请求与权限应答可能来自不同线程）。
- **两个守护线程**：`zcode-app-server-reader`（分拣 stdout）、`zcode-app-server-stderr`（stderr 逐行写 IDEA 日志，前缀 `[app-server]`）。
- **进程退出**：reader 读到 EOF → `failAllPending()` → 若非主动 close 则回调 `onExited(code)`（服务层据此置 DEAD 并提示重启）。
- **close()**：先 `closed` CAS 防重入 → 失败化所有 pending → 关 writer → `process.descendants().destroy()` + `destroy()`（**杀整棵进程树**，jdtls 等子进程不残留）→ 关输入流唤醒阻塞读 → 兜底 `destroyForcibly()`。
- **进程创建**：`ProcessBuilder(node, zcode.cjs, "app-server")`，`cwd=项目根`，注入环境 `NO_COLOR=1`、`ZCODE_DISABLE_UPDATE_CHECK=1`；**不需要 initialize 握手**（探针实测 `initialize`/`ping` 均 -32601，直接发请求即可）。

### 5.2 错误模型

`RpcException(code, message, data)`：`-32601` 方法不存在、`-32602` 参数不合法（Zod 校验错误在 `data.message` 里，含期望值——调试利器）、`-32010` 同会话并发 prompt。服务层 `describeError()` 会解包 `ExecutionException` 并把 `data.message` 拼进用户提示。

---

## 6. 领域层：`core/ZcodeSessionService.kt`

项目级单例，插件的心脏。对上暴露**语义化 Listener 回调**（全部在 EDT 派发），对下消费协议事件。

### 6.1 连接与状态机

```
DISCONNECTED ──ensureConnected()──► STARTING ──创建/恢复会话+订阅──► READY
      ▲                                                              │ ⇅ session/send
      │                    进程退出/启动失败                           ▼
      └──────────────────────── DEAD ◄─────────────── RUNNING ────────┘
                                    （下次 send 自动重启进程并 resume 会话）
```

`ensureConnected()`：双重检查锁（`startLock`）；已活复用；否则 `RuntimeResolver.resolve()` → spawn → `createOrResumeSession()`：

1. 有旧 `sessionId` → 先试 `session/resume`（参数形状已对照 zcode.cjs 源码确认；失败自动回退新建，日志记录）；
2. 否则 `session/create {workspace:{workspaceKey,workspacePath}, mode}` → 取 `result.session.sessionId`（resume 成功但响应缺 sessionId 时回退用原 id）；
3. `session/subscribe {sessionId, deliveryKind:"desktop-continuous"}`（**不传 afterSeq**）—— 不订阅则收不到带正文的事件（只有 telemetry）；而传 `afterSeq:0` 会让服务端回放该 seq 之后的**全部历史事件**（源码逻辑：`afterSeq === undefined` 才不回放），恢复会话时会把整段转录再推一遍、UI 重复渲染。

进程崩溃自愈：`onExited` → DEAD；下一次 `send()` 走 `ensureConnected()` 重建（resume 保会话历史）。`restartProcess()` 供 UI 主动重置。

### 6.2 发送链路（一次提问的完整时序）

```
[EDT] ChatPanel.doSend() ─► service.send(text)
        ① ReadAction.compute { SelectionContext.capture(project) }   ← 必须在 EDT 读编辑器
        ② contextBlock = buildBlock(选区/活动文件/打开文件, maxChars)     可为 null（设置关/无上下文）
        ③ fire { onUserEcho(text, withContext) }                       ← 同步回显，先于一切异步事件
[pooled] ④ ensureConnected()（必要时 spawn+create+subscribe）
        ⑤ state=RUNNING；session/send {sessionId, content = text + contextBlock}
              └─ 返回 {accepted:true} 即返回，后续走通知流
[reader] ⑥ 逐事件到达（见 6.3）→ fire(onEdt) → ChatPanel 渲染
```

`send()` 在 RUNNING 时直接拒绝并提示（协议 -32010 亦会拒绝并发 prompt；UI 同时禁用发送按钮，双保险）。

### 6.3 协议事件 → 语义回调对照表

| session/event type / payload kind | 服务层动作 | UI 回调 |
|---|---|---|
| `model.streaming` kind=`text_delta` | — | `onAssistantDelta(TEXT, delta)` 流式追加正文 |
| `model.streaming` kind=`reasoning_delta` | — | `onAssistantDelta(REASONING, delta)` 思考流 |
| `model.streaming` kind=`tool_call` | 记录 `ToolCallInfo(id,name,input)`；若是文件工具且能取到路径 → **立即快照磁盘旧内容**（见 6.5） | `onToolCall` |
| `tool.updated` kind=`scheduled/started` | 状态置 PENDING/RUNNING | `onToolUpdate` |
| `tool.updated` kind=`result` | success? → DONE/FAILED；**success 且文件工具 → 登记变更文件 + VFS 刷新该文件** | `onToolUpdate` |
| `turn.completed` | 拼 "tokens: N · x.xs" | `onTurnCompleted` |
| `state.updated` patch.status=`running` | 状态机 → RUNNING | `onStateChanged` |
| `state.updated` patch.status=`idle`（reason=prompt_completed） | 状态机 → READY；**异步刷新项目根 VFS**（兜底外部改动） | `onAssistantDone(null)` 终结当前消息 |
| `session.titleUpdated` | 忽略（预留） | — |
| 服务器请求 `interaction/requestPermission` | 入审批队列（见 6.4） | — |
| 服务器请求 `session/requestRuntimePreferences`、`interaction/requestOfficialMcpAuthHeaders` 等 | **回 -32601**（协议官方容错路径，runtime 用默认值继续，探针验证过） | — |

### 6.4 权限审批（安全核心）

服务器请求 `interaction/requestPermission` 带 `options[]`，每个 option 含 `optionId/name/kind/**response**`。协议约定：客户端把用户选中的 option 的 **`response` 对象原样返回**即完成审批（探针实测 allow/deny 均生效）。

```
reader 线程                      EDT                          zcode 进程
onRequest ─► permissionQueue.add ─► pumpPermissions()
                                   若无弹窗正在显示：
                                   invokeLater {
                                     PermissionDialog(params)      ← 模态
                                     chosen = options[i].response   （取消→deny 兜底）
                                   }                                 │
                                   permissionDialogShowing=false     │ responder(result)
                                   responder(chosen) ────────────────► {"id":"server-n","result":{decision:...}}
                                   pumpPermissions()  ← 队列串行，防多工具并行审批时弹窗打架
```

- **队列串行化**：并行工具调用可能同时请求审批；同一时刻只显示一个对话框。
- **默认拒绝兜底**：对话框取消/ESC → 找 deny option 的 response，找不到则回 `{decision:"deny"}`。
- `PermissionDialog`（`DialogWrapper` 子类）：标题/风险等级/原因 + 参数 JSON pretty 预览；`createActions()` 返回 `Array<javax.swing.Action>`（每个 option 一个按钮 + "拒绝"）。
- 默认模式 **edit**：Write/Edit/Bash 等有副作用工具都会请求审批；`yolo` 则全自动（UI 注明谨慎）。

### 6.5 文件变更追踪与 diff 数据

目标：把"会话内被 zcode 改过的文件"做成可点开 diff 的列表。

```
tool_call(Edit/Write/..., input.file_path)          ← 工具尚未执行
   └─ snapshotBeforeWrite(toolCallId, path)：文件存在且 <16MB → 读旧内容入 pendingSnapshots
tool.updated/result(success=true)
   └─ registerChangedFile(path, toolName, oldContent)
        changedFiles = LinkedHashMap<path, ChangedFile>   ← 同文件多轮修改只保留【最早】快照
        diff 即「首次修改前」 vs 「当前磁盘」
   └─ refreshVfsFile(path)：LocalFileSystem.refreshIoFiles(异步)
turn 结束
   └─ refreshVfsAsync()：项目根整体刷新兜底（防事件遗漏）
```

- 文件工具集合：`Edit / Write / MultiEdit / ApplyPatch / NotebookEdit`；路径键依次尝试 `file_path → path → notebook_path`（ApplyPatch 的字符串补丁输入暂不解析）。
- `session/read` 恢复历史时也会扫 `tool` parts 回填变更文件表（无旧内容，diff 退化为空左侧）。
- 呈现：`DiffOpener.show()` → `DiffContentFactory.create(oldContent字符串)` vs `create(project, virtualFile)` → `SimpleDiffRequest` → `DiffManager.showDiff`（原生 diff 窗口，标题含相对路径与工具名）。

### 6.6 其他 API

`stopCurrentTurn()`（session/stop）、`newSession()`（清空追踪+session/close 旧会话+懒重建）、`resumeSession(id)`（先 `activateSession`：`session/resume` 激活 → `subscribe` → **两步都成功后才设 `sessionId`**，部分失败可整体重试；再 `session/read` 转录渲染：user=text parts；assistant=step-start/reasoning/tool/text/step-finish 逐条映射。注意不能依赖 `ensureConnected()` 顺带激活——客户端已存活时它会短路返回，目标会话从未 resume，`session/read` 直接抛 `Session is not active`）、`listSessions()`（session/list {workspace}，倒序含标题/模式/时间）、`setMode()`（记内存 + session/setMode 尽力而为，失败静默）。

---

## 7. 表现层（ui/）

### 7.1 ChatPanel（SimpleToolWindowPanel，vertical）

- **结构**：toolbar（新会话/恢复…/变更文件(N)/紧凑模式下拉——只显示简短模式名、完整说明放 tooltip，左侧用 FlowLayout 摆放避免组件被拉伸 + 右侧状态标签）+ 中央消息滚动区（`BoxLayout.PAGE_AXIS` 的动态面板，用户消息右对齐）+ 底部输入卡片（圆角描边卡片内嵌 JBTextArea 3 行 + 停止/发送按钮，占位文案"询问 ZCode…"）。
- **空状态欢迎页**：未发消息时显示居中的图标 + 标题 + 说明；首条消息到达时整体移除（`chatStarted` 标志），新会话/恢复空列表时重新出现。
- **监听器生命周期**：构造时 `service.addListener(this)`；`Content.setDisposer { panel.dispose() }` 保证工具窗口关闭时注销。
- **流式渲染**：`onAssistantDelta` 惰性创建当前 `AssistantMessagePanel`（思考区可折叠，正文 Markdown 累积 + 120ms `javax.swing.Timer` 合并刷新，避免逐 token 重建 HTML）；每次追加后 `scrollToBottom()`。正文用 `WrappingHtmlPane` 按父容器实际宽度重排高度——JEditorPane 在纵向 BoxLayout 中首选高度不可靠，会因高度塌陷导致正文被裁剪甚至完全不可见。
- **交互细节**：Enter 发送 / Shift+Enter 换行（KeyListener 拦截）；RUNNING 时禁发送、启停止；恢复/变更文件用 `JBPopupFactory.createPopupChooserBuilder`（回调收**列表项字符串**，用 label→对象反查）。
- 状态标签带彩色圆点映射五态：未连接（灰）/启动 zcode…（灰）/就绪（绿）/运行中…（蓝）/DEAD 详情（红）。

### 7.2 消息组件（MessageComponents + ChatUi 共享基础）

共享基础（`ChatUi.kt`）：`ChatColors`（亮/暗主题命名色）、`BubblePanel`（圆角底色卡片，可选描边）、`WrappingTextArea`（按父容器实际宽度重排版算高度——原生 JTextArea 在纵向 BoxLayout 里换行高度不可靠，这是初版"蓝条拉长"问题的根治点）、`WrappingHtmlPane`（同思路的 JBHtmlPane 子类，修复 HTML 正文在纵向 BoxLayout 中高度塌陷不可见的问题）、`CollapsibleSection`（标题行点击折叠/展开）、`Markdown`（轻量 MD→Swing HTML：代码块/行内代码/标题/列表/引用/粗斜体/链接）、`createChatHtmlPane`（样式表显式固定正文为正常前景色，与思考区灰字区分 + 可点击链接）。

| 组件 | 视觉 | 行为 |
|---|---|---|
| `UserMessagePanel` | 浅蓝圆角气泡（`userBubble`）靠右对齐，宽度按内容自适应（上限约 78% 容器宽）+ 灰字"⧉ 已附带 IDE 上下文"标记 | 纯展示；`alignmentX=RIGHT` + 最大宽度=首选宽度，纵向 BoxLayout 才不会把气泡拉满整行 |
| `AssistantMessagePanel` | 粗体 ZCode 头部 + 可折叠"思考过程"（灰字 + 左侧竖线引用样式；流式时展开、结束时仅思考区收起并显示字数）+ 正常前景色 Markdown 正文（代码块带底色）+ 灰色小字脚注 | `appendReasoning/appendText/done(summary)`；正文 flush 走 120ms 节流 |
| `ToolCallPanel` | 圆角卡片：状态图标（动画/✔/✘/⊘）+ 粗体工具名 + 灰色目标摘要 | tooltip=入参 JSON；点击若有 filePath 则在编辑器打开 |

所有消息组件都覆写 `getMaximumSize = (MAX, preferred.height)`——纵向 BoxLayout 会向最大高度无界的组件分发多余空间，这是初版消息被垂直拉伸成"长条"的直接原因。

---

## 8. 线程模型与并发规则（平台规范落地）

| 线程 | 做什么 | 绝不做 |
|---|---|---|
| **EDT** | 所有 Swing 渲染、编辑器读取（ReadAction）、对话框、`invokeLater` 派发回调 | 进程 IO、磁盘读、网络 |
| **reader 守护线程**（客户端自有） | 读 stdout、分拣、调 `Listener`（服务层随即 `invokeLater` 转 EDT） | 直接碰 Swing |
| **pooled thread**（`executeOnPooledThread`） | spawn 进程、session/send、`session/read`、VFS refresh、RuntimeResolver 的 `where` 子进程 | 直接碰 Swing |
| writer（调用方线程） | `writeLine`（`writerLock` 同步） | — |

- **事件顺序保证**：所有对 UI 的 fire 都经 `Application.invokeLater`，EDT 队列 FIFO → 协议事件顺序即渲染顺序。
- **并发容器**：`pending`（CMA HashMap）、`toolCalls`（CHM）、`changedFiles`（synchronized LinkedHashMap）、`permissionQueue`（ConcurrentLinkedQueue）+ `@Volatile` 状态位。
- 审批 `responder` 在 EDT 回写 writer（锁保护，跨线程安全）。

---

## 9. 构建体系

### 9.1 Gradle 配置（IntelliJ Platform Gradle Plugin 2.18.1）

```kotlin
// settings.gradle.kts —— settings 插件统一管理主插件版本
plugins { id("org.jetbrains.intellij.platform.settings") version "2.18.1" }

// build.gradle.kts —— 主插件【不带版本】（否则报 "already on the classpath"）
plugins { id("java"); id("org.jetbrains.kotlin.jvm") version "2.2.10"; id("org.jetbrains.intellij.platform") }

dependencies {
    intellijPlatform {
        local("D:/IntelliJ/IntelliJ IDEA 2025.2.4")   // 本机 IDE，零下载
        // intellijIdea("2024.3")                       // 换机器：远程拉取（1-2GB）
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")   // ★ 平台 JUnit5 初始化器内部引用 JUnit4 类，缺了直接 NoClassDefFound
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform { pluginConfiguration { ideaVersion { sinceBuild = "243"; untilBuild = null } } }
```

- `kotlin.stdlib.default.dependency=false`：平台自带 Kotlin stdlib，插件不重复打包。
- 国内网络：仓库加阿里云镜像（gradle-plugin/public）；**wrapper 发行版用腾讯镜像**（`services.gradle.org` 会 302 到不可达的 github.com）。
- **兼容面**：编译基于本机 2025.2.4（IU-252），`sinceBuild=243`（2024.3+）；所用 API 均为 243 期已有的稳定面（EditorFactory/WriteCommandAction 未用、DiffManager、JCEF 未用）。严格校验可后续跑 `runPluginVerifier`。

### 9.2 构建用 JDK 的坑（本机实测）

| JDK | 结果 | 原因 |
|---|---|---|
| Microsoft OpenJDK 21（~/.jdks/ms-21.0.9） | `instrumentCode` 失败 | 插桩器找 `JAVA_HOME\Packages` 目录，MS 布局没有 |
| 本机 IDEA 的 JBR 21（带激活补丁） | 插桩过、`test` 失败 | 注入的 javaagent 干扰 Gradle 测试执行器类加载 |
| **Amazon Corretto 21（corretto-21.0.12.1）** | **全绿** | 标准布局 + 无注入 |

结论（已写入 README）：**用标准布局的原版 JDK 21 构建**；产物字节码必须是 21（目标 IDE 运行时为 JBR 21，更高版本字节码无法加载——这也是不选 JDK 26 的原因）。

### 9.3 测试

`AppServerClientTest`：以 `PipedInput/OutputStream` 构造**假 Process**，反射调私有构造器实例化 client，验证：① 请求-响应按 id 配对与信封格式；② 通知/服务器请求分拣与应答回写。两个工程化细节：
- `FakeProcess.destroy()` 必须**关管道写端**（`PipedInputStream` 只有 `closedByWriter` 才会让阻塞的 read 返回 EOF，关读端叫不醒它）；
- `awaitReaderThreadsGone()` 等后台线程退出，避免平台 ThreadLeakTracker 误报。

---

## 10. 关键设计决策记录（ADR 摘要）

1. **app-server 协议而非 `--prompt` headless**：后者每条消息一个进程（~2s 启动开销）且无权限交互；app-server 长驻 + 事件流 + 双向请求，是桌面端同款通道。降级路线保留（PROTOCOL.md）。
2. **自建 UI 而非终端 TUI**：zcode 无 Claude 的 IDE 发现机制，终端路线需自建桥接；自建 UI 把上下文注入/审批/diff 全部确定性掌控。
3. **选区按需采集而非常驻监听**：发送瞬间 ReadAction 读一次即可满足需求，避免监听器生命周期与泄漏风险。
4. **插件不分发 runtime**：只做发现（zcode-app-cli → 桌面端 → 设置覆盖），规避第三方包重分发与版本耦合问题。
5. **`-32601` 回绝 runtimePreferences**：协议源码中该错误码触发官方默认值兜底（探针验证），是官方预留的"客户端不支持"路径。
6. **审批回传 option.response 原样透传**：不自行构造 decision 结构，最大化兼容未来 option 语义（如 modify/always）。

---

## 11. 已知限制与路线图

**当前限制**
- Markdown 渲染为轻量自研（代码块无语法高亮）；工具参数 tooltip 为原始 JSON。
- 运行中不支持排队消息；每项目一个会话进程。
- ApplyPatch 输入不解析路径（不进变更文件表）；恢复历史回填的文件无旧内容（diff 左侧为空）。
- 设置为应用级（跨项目共享运行时路径）；无国际化文件（硬编码中文）。

**路线图**
1. **Phase 6a（协议已支持）**：IDE 内起 http/sse MCP server 暴露 `getDiagnostics`（IDEA 诊断）等工具，注册到项目级 `.zcode/config.json` 的 `mcp.servers`（bundle 静态分析已确认支持 `type:"http"|"sse"` + url 配置）。
2. **Phase 6b**：设置项"IDE 会话禁用 zcode LSP 插件"（项目级配置覆盖 `plugins`），消除 jdtls/tsserver 双份开销。
3. 终端 TUI 模式（复用同一集成层）、Markdown 渲染、变更文件实时侧栏、消息排队、`session/fork`/`rewind`、多会话 tab。

---

## 12. 附录：设置项与探测顺序速查

**ZcodeSettings（`%APPDATA%\JetBrains\<IDE>\options\zcode-idea.xml`）**

| 键 | 默认 | 说明 |
|---|---|---|
| `nodePath` | ""（自动） | node.exe 完整路径；空→ `where node` → 常见安装位置 |
| `runtimePath` | ""（自动） | zcode.cjs 路径；空→ ① `%APPDATA%\npm\node_modules\zcode-app-cli\vendor\zcode.cjs` ② `where zcode` shim 反推同级 node_modules ③ 桌面端常见路径（含 `D:\Tools\ZCode\resources\glm\zcode.cjs`） |
| `defaultMode` | `edit` | build/edit/plan/yolo（create 时传入；运行中可切，session/setMode 尽力同步） |
| `injectSelectionContext` | true | 发送时自动附 IDE 上下文 |
| `maxSelectionChars` | 8000 | 选区注入截断上限 |

**环境自检**：Settings → Tools → ZCode → 「检测环境」→ 后台跑探测 → 气泡报告 Node/Runtime 及来源。
