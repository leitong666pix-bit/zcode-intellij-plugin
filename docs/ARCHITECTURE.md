# ZCode IDEA 插件 — 架构与实现技术文档

> 版本：0.2.0 ｜ 对应代码：`D:\Projects\IDEA plugins\zcode-idea-plugin`
> 配套文档：[PROTOCOL.md](PROTOCOL.md)（ZCode Protocol 逆向笔记）、[README.md](../README.md)（构建/使用）

---

## 1. 项目定位与设计目标

本插件把 **ZCode agent runtime**（ZCode 桌面端内置的官方 CLI，`zcode.cjs`）接入 IntelliJ IDEA，提供类似 Claude Code 官方 IDEA 插件的体验：

- 在 IDE 侧边 ToolWindow 中与 zcode **多轮对话**（流式输出、思考过程与工具调用收进同一时间线、GFM 表格与代码块 IDE 同款语法高亮）；
- **选区/活动文件自动注入**对话上下文；右键"引用选中代码"以 `@文件:行` 记号**多处内嵌**在输入文字任意位置；
- 正文里的**文件引用可定位单行或选中行范围**；回答完成后解析行内类名、方法名等符号，命中定义才显示链接，同名目标可选择（§7.3）；
- **斜杠命令**（本地 + 服务端内置 + `.zcode/commands` 自定义命令展开）与**手动压缩上下文**（/compact，运行中自动排队）；
- zcode 的工具调用（改文件、跑命令）在 IDE 内**弹窗审批**，默认安全模式；
- zcode 写盘后 IDE **自动感知**（VFS 刷新）、被改文件**一键 diff**（IDE 原生 diff viewer）；
- 会话持久化在 zcode 侧（`~/.zcode/cli/`），支持跨插件/CLI **恢复**；运行中发送的消息自动排队、逐轮顺序发出。

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
- **IDE 上下文与呈现归插件**：选区采集、审批 UI、VFS 同步、diff 展示，以及通过 IDEA 文件索引和语言插件的类/符号索引实现正文引用导航。导航查询服务于用户点击，不向 zcode 新增代码搜索工具。
- MVP **不动 MCP、不改 zcode 的 LSP 插件配置**（后期增强见 §11）。

---

## 2. 总体架构

```
┌──────────────────────────── IntelliJ IDEA 进程 ─────────────────────────────┐
│                                                                             │
│  表现层（ui/，全部 EDT）                                                     │
│  ┌───────────────────────────────────────────────────────────────────┐     │
│  │ ZcodeToolWindowFactory → ChatPanel（注册入 ChatPanelRegistry）                               │     │
│  │  ├ 工具栏：新会话/恢复…/变更文件(N) ＋ 模型/思考强度/模式下拉        │     │
│  │  ├ 消息流：UserMessagePanel / AssistantMessagePanel / ToolCallPanel │     │
│  │  ├ 输入区：引用条/图片chips + JBTextArea + 附图/发送/停止                                   │     │
│  │  └ PermissionDialog（模态审批）                                      │     │
│  └──────────────▲──────────────────────────────┬──────────────────────┘     │
│                 │ Listener 回调(EDT)            │ send()/stop()/...         │
│  集成层（core/，项目级服务）                    ▼                            │
│  ┌───────────────────────────────────────────────────────────────────┐     │
│  │ ZcodeSessionService (@Service(Project), Disposable)               │     │
│  │  ├ 状态机 DISCONNECTED→STARTING→READY⇄RUNNING / DEAD                │     │
│  │  ├ 协议事件翻译：session/event → 语义回调                            │     │
│  │  ├ 权限审批队列（串行弹窗）＋ 会话删除(node:sqlite)                                          │     │
│  │  └ 文件追踪：首改前快照 + 变更文件表                                 │     │
│  │        │ 持有                                                       │     │
│  │        ▼                                                            │     │
│  │ AppServerClient（进程内 JSON-RPC 客户端）                            │     │
│  │  ├ reader 线程：逐行解析 stdout，分发 响应/通知/服务器请求            │     │
│  │  └ writer（同步锁）：stdin 写请求/应答                               │     │
│  └──────────────┬────────────────────────────────────────────────────┘     │
│                 │ spawn: node <zcode.cjs> app-server（cwd=项目根）    │
│  支撑层           ▼                                                        │
│  ├ runtime/RuntimeResolver：node + zcode.cjs 自动探测（桌面端优先，可覆盖）        │
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
│   ├── probe-artifacts/          多轮协议探针的原始数据（事件样本等）
│   └── ARCHITECTURE.md           本文档
├── src/main/resources/META-INF/plugin.xml   扩展点注册（§4）
├── src/main/resources/icons/                ZCode 官方图标（桌面端 icon.png 缩放）：zcodeTool.png(13)/zcode.png(24) + @2x
├── src/main/kotlin/zcode/idea/
│   ├── runtime/RuntimeResolver.kt           ★ 运行时探测（桌面端优先 → npm 回退）
│   ├── core/AppServerClient.kt              ★ JSON-RPC 客户端（传输层）
│   ├── core/ZcodeCliConfig.kt               ★ ~/.zcode 配置解析 → runtimeModel/模型能力
│   ├── core/ZcodeSessionService.kt          ★ 会话服务（领域层/粘合层，含消息排队）
│   ├── commands/SlashCommands.kt            ★ 斜杠命令：扫描/frontmatter 解析/占位符展开/路由（纯逻辑）
│   ├── context/SelectionContext.kt          IDE 上下文采集 + 上下文块拆分 + @引用记号展开
│   ├── vfs/DiffOpener.kt                    原生 diff 呈现
│   ├── ui/ZcodeToolWindowFactory.kt         ToolWindow 入口 + ChatPanelRegistry
│   ├── ui/ChatPanel.kt                      聊天面板（最大 UI 文件）
│   ├── ui/MessageComponents.kt              消息组件（用户气泡/助手时间线/工具卡片）
│   ├── ui/ChatUi.kt                         共享基础（Markdown 渲染/链接事件分流/IDE 代码着色器）
│   ├── ui/FileRefs.kt                       文件引用解析与编解码 + SymbolRefs 候选提取/链接装饰
│   ├── ui/ReferenceNavigator.kt             后台索引解析、候选选择、文件行范围/符号定义导航
│   ├── ui/PermissionDialog.kt               审批对话框
│   ├── actions/ZcodeEditorActions.kt        编辑器右键动作组（含引用选中代码）
│   └ settings/ZcodeSettings.kt / ZcodeConfigurable.kt   设置
└── src/test/kotlin/zcode/idea/…             纯逻辑单测（传输层管道假进程、Markdown/FileRefs/
                                             SlashCommands/SelectionContext 渲染与展开、
                                             ResponsiveToolbarLayout 几何），全部 headless 可跑
```

依赖极简：**只依赖 `com.intellij.modules.platform`**（不依赖 Java/Ultimate 模块），JSON 用平台自带的 Gson（`com.google.gson` 为平台对外可用的第三方库），UI 全 Swing/JBUI，无任何额外第三方依赖。

---

## 4. 插件注册（plugin.xml）

```xml
<toolWindow id="ZCode" anchor="right" factoryClass="zcode.idea.ui.ZcodeToolWindowFactory"
            icon="icons/zcodeTool.png" canCloseContents="false"/>
<applicationConfigurable parentId="tools" displayName="ZCode"
                         instance="zcode.idea.settings.ZcodeConfigurable"/>
<notificationGroup id="ZCode" displayType="BALLOON"/>
<group id="Zcode.EditorActions" popup="true" text="ZCode">
    <action id="Zcode.AttachSelection" .../>  引用选中代码到对话...（在输入框光标处插入 @文件:行 引用记号，不直接发送）
    <action id="Zcode.ExplainSelection" .../>   解释选中的代码
    <action id="Zcode.ImproveSelection" .../>   优化选中的代码
    <action id="Zcode.WriteTests" .../>         为选中代码写测试
    <action id="Zcode.CustomPrompt" .../>       自定义指令...（多行输入对话框）
    <add-to-group group-id="EditorPopupMenu" anchor="first"/>
</group>
```

- `AttachSelectionAction` 经 `ChatPanelRegistry`（工厂创建面板时注册、Disposer 注销）把选区投递到当前项目的 ChatPanel。
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

`ensureConnected()`：双重检查锁（`startLock`）；**连接存活但 `sessionId` 为空（如刚点过"新会话"）时在现有连接上补建会话**——初版在这里直接短路返回，导致"新会话"后所有发送报"会话未就绪"且重试无效。冷启动路径：`RuntimeResolver.resolve()` → spawn → `syncModelCatalog()` → `createOrResumeSession()`：

1. **模型目录回推**（`syncModelCatalog`）：`ZcodeCliConfig.load()` 解析 `~/.zcode/cli/config.json`（provider/模型/baseURL/apiKey）并合并 `~/.zcode/v2/config.json` 的 modalities（supportsImages），对每个模型各发一次 `workspace/readState{runtimeModel}`（服务端合并逻辑每次只保留被选模型，必须逐个推），再纯读一次拿权威的可用模型/思考强度列表喂给 UI。**不回推的后果**：resume 历史会话时沿用历史里已下线的模型 → 服务端置 restoreWarning → 所有发送 -32031（"历史任务使用的模型已不可用"，根因见 PROTOCOL.md「模型目录」）。
2. 有旧 `sessionId` → 先试 `session/resume`（**params 必带 runtimeModel**，覆盖历史模型、根治 -32031；失败自动回退新建，日志记录）；
3. 否则 `session/create {workspace, mode, model}`（model = 用户偏好或 CLI 配置默认）→ 取 `result.session.sessionId`；建好后若存有思考强度偏好，补一发 `session/setThoughtLevel`（create 参数不支持该字段）；
4. `session/subscribe {sessionId, deliveryKind:"desktop-continuous"}`（**不传 afterSeq**）—— 不订阅则收不到带正文的事件（只有 telemetry）；而传 `afterSeq:0` 会让服务端回放该 seq 之后的**全部历史事件**（源码逻辑：`afterSeq === undefined` 才不回放），恢复会话时会把整段转录再推一遍、UI 重复渲染。

进程崩溃自愈：`onExited` → DEAD；下一次 `send()` 走 `ensureConnected()` 重建（resume 保会话历史）。`restartProcess()` 供 UI 主动重置。`prewarm()` 在工具窗口打开时后台拉起，消除首次 ~2s 冷启动。

### 6.2 发送链路（一次提问的完整时序）

```
[EDT] ChatPanel.doSend()
        ├─ "/" 开头 → 斜杠路由（见 ADR #14）：本地命令插件内消化；自定义命令客户端展开；
        │             服务端内置原样 → service.send（内部命中斜杠分支：不注入上下文/图片，逐字发送）
        ├─ 文本含已登记的 @引用记号 → substituteRefs 展开成内联引用块
        │     → service.sendWithRefs(display=原文, content=展开后文本, images)   ← 不再自动采集上下文
        └─ 普通消息 → service.send(text, images)
              ① 上下文块：自动采集 ReadAction.compute { SelectionContext.capture(project) }  ← 必须在 EDT
              ② 图片：每张追加内嵌 Markdown 引用 "\n\n![name](file:///C:/...png)"
                   （attachments 字段不适用，见 PROTOCOL.md「图片输入」；当前模型不支持图像则拦截提示）
        三者最终都走 enqueueOrDispatch(QueuedSend(prompt=回显, content=实发, displayBlock)):
        占坑(sendInFlight CAS)失败 / turnActive / RUNNING → 入队（气泡延迟回显），否则立即派发
[派发] ③ fire { onUserEcho }（入队消息在真正派发时才回显——问答严格成对相邻）
[session worker] ④ ensureConnected()（存活但无会话则补建；冷启动则 spawn+回推目录+create+subscribe）
        ⑤ state=RUNNING、turnActive=true；session/send {sessionId, content}
              └─ 返回 {accepted:true} 即返回，后续走通知流（图片文件不能删——runtime 回合内才读取）
[reader → session worker] ⑥ 校验连接、会话代次与 sessionId（见 6.3）→ fire(onEdt) → ChatPanel 渲染；turn.completed/idle 后 drainQueue 逐条消化排队
```

发送失败分两类处理：明确 RPC 拒绝会释放本轮占位并继续队列；超时或 -32010 先查询服务端快照，只有确认 idle 才继续发送，否则保持运行状态并允许停止，不自动重发或中断可能已被接受的消息。-32031 仍通过 fork 继承历史兜底。停止、断连或切换会话清空原队列；停止/切换会话会使旧等待与 UI 回调失效。

### 6.3 协议事件 → 语义回调对照表

| session/event type / payload kind | 服务层动作 | UI 回调 |
|---|---|---|
| `model.streaming` kind=`text_delta` | — | `onAssistantDelta(TEXT, delta)` 流式追加正文 |
| `model.streaming` kind=`reasoning_delta` | — | `onAssistantDelta(REASONING, delta)` 思考流 |
| `model.streaming` kind=`tool_call` | 记录 `ToolCallInfo(id,name,input)`；若是文件工具且能取到路径 → **立即快照磁盘旧内容**（见 6.5） | `onToolCall` |
| `tool.updated` kind=`scheduled/started` | 状态置 PENDING/RUNNING | `onToolUpdate` |
| `tool.updated` kind=`result` | success? → DONE/FAILED；**success 且文件工具 → 登记变更文件 + VFS 刷新该文件** | `onToolUpdate` |
| `turn.completed` | **轮次权威结束信号**：turnActive=false → READY；拼 "tokens: N · x.xs"；重取上下文占用快照；drainQueue | `onAssistantDone(null)` + `onTurnCompleted` |
| `state.updated` patch.status=`running` | 状态机 → RUNNING | `onStateChanged` |
| `state.updated` patch.status=`idle`（reason=prompt_completed） | 状态机 → READY；**异步刷新项目根 VFS**（兜底外部改动）；兜底 drainQueue。注意 `prompt_completed` 实测可能提前到（ADR #13），轮次结束只认 `turn.completed`/`idle` | `onAssistantDone(null)` 终结当前消息 |
| `state.updated` reason=`compact_started` | /compact 压缩轮启动（走正常 turn 事件流） | `onNotice("正在压缩上下文…")` |
| create/resume/subscribe 回复含 `slashCommands` | 解析内置命令列表（goal/compact/init/plan）供 "/" 补全与未知命令判定 | `onSlashCommands` |
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

FileSnapshots 统一管理待执行工具和已完成修改的首次快照。reader 收到文件工具的 tool_call 时立即尝试采集，避免后台会话队列等待其他 RPC 时错过修改前内容；工具结果再由会话队列确认保留或释放。同一路径的多个工具共享首次快照，失败工具释放引用。

文本载荷按 UTF-16 每字符 2 字节计费：单份最多 16 MiB，整个会话最多 64 MiB，同时统计 pending 和已完成快照。读取过程有界；历史记录、文件过大、预算耗尽或读取失败都标记为不可用。每次切换会话创建独立存储，旧 reader 不能污染新会话。

BeforeContent 明确区分 Captured、NewFile、Unavailable。只有确认原文件不存在时 diff 左侧才为空；Unavailable 显示原因。文件工具的相对路径按项目根解析。工具成功后异步刷新对应文件 VFS。


## 7. 表现层（ui/）

### 7.1 ChatPanel（SimpleToolWindowPanel，vertical）

- **结构**：toolbar（左：新会话/恢复…/变更文件(N)/压缩；右：状态标签 + 上下文占用标签（可点击=压缩）+ 模型下拉(128px) + 思考强度下拉(84px, 中文映射 低/中/高/最高) + 模式下拉(104px)）+ 中央消息滚动区（`BoxLayout.PAGE_AXIS`，用户气泡靠右）+ 底部输入卡片（北侧：图片 chips 行；圆角描边卡片内嵌 JBTextArea 3 行 + 附图/停止/发送）。
- **模式下拉文案**：逐字取自 ZCode 桌面端 i18n（app.asar 的 `mode.label.glm.*`/`mode.description.glm.*`）——build=变更前确认、edit=自动编辑、plan=计划模式、yolo=完全访问；tooltip = 官方中文名 + 官方一句说明。协议 id（build/edit/plan/yolo）只作内部存储与 `session/setMode` 参数，不直接显示。
- **上下文占用标签**：`session/subscribe` 带 `includeSnapshot:true`，从回复 `snapshot.runtime.contextUsage.{used,size}` 驱动（如 `上下文 14k/1.0M`，tooltip 给精确值与百分比，≥80% 变红）。订阅时与每轮 `turn.completed` 后（重复 subscribe 取新快照，无事件回放副作用）各刷新一次；空会话/新会话无该字段则隐藏。
- **工具栏自适应（ResponsiveToolbarLayout）**：子组件按加入顺序排成一条流，宽度不够时整条流换行、每行从左铺满（像文字折行，无死区）；单行放得下时前 4 个（按钮）贴左、其余（标签+下拉）贴右，即宽面板经典外观。**替代方案都有缺陷**：BorderLayout+EAST 窄面板下左组被压成 0 宽（按钮"消失"）；左右组各占一行的两行布局在中等宽度下上行右侧/下行左侧各留大片空隙。preferred 高度随当前宽度（折行数）变化，面板挂 componentResized→revalidate 兜底收敛高度差一拍的问题；几何行为有确定性单元测试（`ResponsiveToolbarLayoutTest`，显式 preferredSize，headless 可跑，含"每个非末行塞满"的反死区断言）。
- **空状态欢迎页**：未发消息时显示居中的官方图标 + 标题 + 说明；首条消息到达时整体移除（`chatStarted` 标志），新会话/恢复空列表时重新出现。
- **监听器生命周期**：构造时 `service.addListener(this)`；`Content.setDisposer { panel.dispose() }` 保证工具窗口关闭时注销（同时从 `ChatPanelRegistry` 摘除）。
- **流式渲染**：`ensureAssistantPanel()` 惰性创建当前 `AssistantMessagePanel`（首个 delta 或首个工具调用触发）；思考文本与工具行**按到达顺序交错**追加进思考区时间线（**默认折叠**、标题实时报"思考中 · N 字 · N 个工具"进度，点击可展开，用户展开过则 `done()` 不强收）；正文 Markdown 累积 + 120ms `javax.swing.Timer` 合并刷新（避免逐 token 重建 HTML）；每次追加后 `scrollToBottom()`。正文用 `WrappingHtmlPane` 按父容器实际宽度重排高度——JEditorPane 在纵向 BoxLayout 中首选高度不可靠，会因高度塌陷导致正文被裁剪甚至完全不可见。
- **贴底跟随（StickyBottomTracker）**：流式期间是否跟随滚动由滚动条事件驱动的状态机决定，而非按"当前离底距离"事后判断——用户滚轮/拖动离开底部即停跟随（之后内容再涨也停在原地），拉回底部自动恢复，用户发消息/恢复会话强制贴底。只在 value 变化时重判（内容增高只动 maximum 不动 value，不会误判成用户上翻）；拖动未松手期间一律暂停吸附，避免与程序贴底互相打架。**两次实测教训**：① 按"gap < 视口高度/3 才跟随"的事后判断，用户每次上滚都超不过阈值就被下一个 delta 拽回底部，等于永远逃不出吸附区；② 贴底操作是两跳 invokeLater 异步执行，只在**入队时**检查贴底态（TOCTOU）——密集流式期间已入队的贴底操作会压过用户的滚轮事件执行、把视图拽回底部并再次把状态刷回"贴底"，用户根本翻不上去，必须在**执行前复查**。另：流式只读文本区（`readOnlyArea`）把 caret 置 `NEVER_UPDATE`，防文档更新触发 `DefaultCaret.adjustVisibility → scrollRectToVisible` 绕过贴底守卫直接劫持视口。
- **斜杠补全弹层**：输入 "/" 开头且无空白时在输入框上方弹非焦点列表（本地 + 服务端内置 + 自定义命令，后台线程限频扫描）；↑↓/Enter/Tab/Esc 由输入框 KeyAdapter 转发；IME 组合态与参数输入阶段自动关闭。
- **附图**：Ctrl+V（剪贴板 imageFlavor → BufferedImage → `%TEMP%/zcode-idea-images/paste-*.png`）或「附图」按钮（FileChooser，png/jpg/jpeg/gif/webp/bmp）；chips = 32px 缩略图 + 文件名 + 移除；发送后清 chip 但**不删临时文件**（send 提前返回、runtime 回合内才读文件）。入口按当前模型 `supportsImages` 启停；`addImage` 时若不支持弹提示。
- **恢复下拉**：自绘行列表（非 PopupChooserBuilder）——每行 `[时间] 标题 · 模式` + 🗑；整行点击恢复（监听同时挂 row/label/删除键，鼠标事件只派发最深层组件）；删除走确认对话框 → `deleteSession` → 原地重拉列表重绘。**悬停高亮必须用不透明纯色**（列表底色↔选中色）——半透明色在 opaque 切换时不清底，反复悬停会叠加残影/花字（初版实测翻车点）。
- 状态标签带彩色圆点映射五态：未连接（灰）/启动 zcode…（灰）/就绪（绿）/运行中…（蓝）/DEAD 详情（红）。

### 7.2 消息组件（MessageComponents + ChatUi 共享基础）

共享基础（`ChatUi.kt`）：`ChatColors`（亮/暗主题命名色）、`BubblePanel`（圆角底色卡片，可选描边）、`WrappingTextArea`（按父容器实际宽度重排版算高度——原生 JTextArea 在纵向 BoxLayout 里换行高度不可靠，这是初版"蓝条拉长"问题的根治点）、`WrappingHtmlPane`（同思路的 JBHtmlPane 子类，修复 HTML 正文在纵向 BoxLayout 中高度塌陷不可见的问题）、`CollapsibleSection`（标题行点击折叠/展开）、`StickyBottomTracker`（消息区贴底跟随状态机，见 §7.1）、`ideCodeHighlighter`（围栏代码块 IDE 词法器着色）、`Markdown`（轻量 MD→Swing HTML：围栏代码块（着色回调注入）/行内代码/标题/列表/任务列表/引用/粗斜体/删除线/GFM 表格/链接/分隔线）、`createChatHtmlPane`（样式表固定正文前景色 + GitHub 风格表格样式——无竖线边框、横向细线分隔、表头浅底加粗下划、偶数行斑马纹，`cellspacing=0` 保证横线连贯；链接监听分流：`zcodefile:` → IDE 内跳转，`zcodesymbol:` → IDE 符号定义选择，仅 HTTP(S) → 浏览器）。

| 组件 | 视觉 | 行为 |
|---|---|---|
| `UserMessagePanel` | 浅蓝圆角气泡（`userBubble`）靠右对齐，宽度按内容自适应（上限约 70% 视口宽，按最宽一行测宽）；有上下文时气泡下方整行宽的折叠"IDE 上下文 · N 字"（默认收起，点击展开灰字小号正文） | 纯展示；`alignmentX=RIGHT` + 最大宽度=首选宽度，纵向 BoxLayout 才不会把气泡拉满整行；上下文折叠区复用 `CollapsibleSection`；内嵌引用的消息回显保留紧凑 `@文件:行` 记号（展开后的引用块在实发内容里） |
| `AssistantMessagePanel` | 粗体 ZCode 头部 + 可折叠思考区时间线（思考文本段灰字左竖线 + 工具行交错，标题"已深度思考 · N 字 · N 个工具"）+ Markdown 正文（表格/代码高亮）+ 灰色小字脚注；正文结论永远在思考区之后 | `appendReasoning/appendText/addToolCall/done(summary)`；正文 flush 走 120ms 节流；`onOpenFileRef`/`highlightCode` 构造注入 |
| `ToolCallPanel` | 圆角卡片（排在思考区时间线里，缩进）：状态图标（动画/✔/✘/⊘）+ 粗体工具名 + 灰色目标摘要 | tooltip=入参 JSON；点击若有 filePath 则在编辑器打开 |

所有消息组件都覆写 `getMaximumSize = (MAX, preferred.height)`——纵向 BoxLayout 会向最大高度无界的组件分发多余空间，这是初版消息被垂直拉伸成"长条"的直接原因。

### 7.3 正文代码引用导航

`FileRefs.kt` 中的 `FileRefs` 负责文件引用解析和 `zcodefile:` 链接编解码，`SymbolRefs` 负责行内符号候选提取和 `zcodesymbol:` 链接装饰；`ReferenceNavigator` 负责目标解析及 IDE 导航。`ChatPanel` 为实时回答和恢复的历史回答注入同一组回调。

- **文件引用**：行内代码支持裸文件名、绝对/相对路径及可选行号，行范围支持 `Foo.java:336-347`、`Foo.java#L12-L20`；本地 Markdown 链接也转换为内部链接。纯文本中的裸文件名必须带行号，带目录的路径可不带行号。无效行号（零、溢出或倒序范围）不创建链接。
- **目标查找**：依次检查绝对路径、项目根相对路径，再用 `FilenameIndex` 查询当前项目内的同名文件；有路径后缀匹配时优先采用。一个结果直接打开，多个结果展示路径列表，无结果给出提示。
- **定位**：`OpenFileDescriptor` 打开文件并移动光标；显式行范围选中起始行开头至结束行末尾。引用行数超出当前文件时收缩到可用范围并提示文件行数已变化。
- **符号引用**：回答完成后，仅从尚未链接的行内代码提取类名、驼峰标识符或带空括号的方法名，如 `HealthProperties`、`scheduleReminderPatientWhitelistFilter`、`execute()`、`Handler.execute()`。查询语言插件提供的类/符号贡献器，兼容 `ChooseByNameContributor` 与 `ChooseByNameContributorEx`，只保留当前项目中可导航的 PSI 定义。仅命中的候选变为链接，多定义时提供选择；点击时重新解析目标。
- **呈现与生命周期**：链接使用主题对应颜色、下划线、手形光标和悬停提示；符号提示显示目标或候选数量。解析使用 `ReadAction.nonBlocking` 等待智能模式，结果回 EDT；项目或面板释放后停止回调，消息渲染版本检查防止旧结果覆盖新正文。索引期间及未命中的内容保持普通代码样式。

这些链接只存在于插件生成的 HTML 中；服务端仍返回原始 Markdown 文本，见 [PROTOCOL.md](PROTOCOL.md#正文引用与客户端导航)。

---

## 8. 线程模型与并发规则（平台规范落地）

| 线程 | 职责 |
|---|---|
| EDT | 上下文采集、界面渲染和审批弹窗；UI 回调与历史分批渲染均校验会话代次 |
| reader | JSON-RPC 分发、文件工具首次快照采集、审批入队；不得同步等待另一个 RPC |
| zcode-session-worker | SessionTaskQueue 串行执行会话、模型、权限模式和消息状态变更 |
| IDE pooled thread | VFS 刷新、界面辅助 IO，以及智能模式下的非阻塞引用索引查询；导航和 HTML 更新回到 EDT |
| writer 调用线程 | 用 writerLock 保护请求与审批应答写入 |

- 会话切换和停止立即推进代次；旧任务在下一次等待检查（最长约 100 ms）时退出，后续 RPC 和迟到的 UI 回调被丢弃。项目释放关闭队列和进程。
- 通知按连接实例和 sessionId 过滤；审批额外绑定代次，过期请求自动拒绝，旧弹窗不允许批准新会话的操作。
- 模型和思考强度切换在 worker 上等待，不阻塞 reader。权限模式收到确认后才更新显示，等待期间暂停新消息；结果不明时查询快照，无法确认则继续暂停。
- 恢复会话从服务端快照同步实际权限模式和运行状态。


## 9. 构建体系

### 9.1 Gradle 配置（IntelliJ Platform Gradle Plugin 2.18.1）

```kotlin
// settings.gradle.kts —— settings 插件统一管理主插件版本
plugins { id("org.jetbrains.intellij.platform.settings") version "2.18.1" }

// build.gradle.kts —— 主插件【不带版本】（否则报 "already on the classpath"）
plugins { id("java"); id("org.jetbrains.kotlin.jvm") version "2.2.10"; id("org.jetbrains.intellij.platform") }

dependencies {
    intellijPlatform {
        // 属性化本地 IDE（gradle.properties: localIdePath=...），未配置/无效时回落远程 2024.2 基线
        val localIde = providers.gradleProperty("localIdePath").orNull?.trim()?.takeIf { File(it).isDirectory }
        if (localIde != null) local(localIde) else intellijIdea("2024.2")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")   // ★ 平台 JUnit5 初始化器内部引用 JUnit4 类，缺了直接 NoClassDefFound
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform { pluginConfiguration { ideaVersion { sinceBuild = "242"; untilBuild = null } } }
```

- `kotlin.stdlib.default.dependency=false`：平台自带 Kotlin stdlib，插件不重复打包。
- 国内网络：仓库加阿里云镜像（gradle-plugin/public）；**wrapper 发行版用腾讯镜像**（`services.gradle.org` 会 302 到不可达的 github.com）。
- **兼容面**：`sinceBuild=242`（2024.2+），CI 以 2024.2 为编译基线，本地用 2025.2.4 双端编译验证。版本敏感点两处，均按"最小公共 API"改写：
  1. `JBHtmlPane`：无参构造是 252 新增（CI 曾以 2024.3 编译报 `No value passed for parameter 'myStyleConfiguration'`），改用 `JBHtmlPane(JBHtmlPaneStyleConfiguration(), JBHtmlPaneConfiguration())` 两参构造——242/243 官方源码核对与 243/252 jar javap 四点一致；内置 kit 由 `HTMLEditorKitBuilder.build()` 产出，返回 Swing 原生 `HTMLEditorKit`，样式注入路径各版本一致。241 及更早平台没有 `JBHtmlPane`，不支持。
  2. UI DSL `Row.textFieldWithBrowseButton`：242 仅有 `(String, Project, FileChooserDescriptor)` 老签名（243 起新增 descriptor-first 重载），252 将老签名标为 ERROR 级弃用但字节码仍在——统一用老签名 + `@Suppress("DEPRECATION_ERROR")` 跨版本共存。
  严格校验可后续跑 `runPluginVerifier`（242–252 各档）。

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


引用导航的自动化覆盖：`FileRefsTest` 验证文件行范围、#L 锚点、无效位置、Unicode/空格/转义路径、本地 Markdown 链接、已存在链接保护，以及符号候选和多定义提示；`ReferenceTargetsTest` 验证同名文件/路径后缀选择与越界行范围裁剪。本次 `test buildPlugin --offline` 通过 108 项测试并生成安装包；尚未在真实 IDEA 中完成导航点击验收。

安装包的人工验收项目：

1. 单行和 `Foo.java:336-347` 引用分别定位到行、选中完整范围；过期行号出现范围调整提示。
2. 两个模块中的同名文件弹出路径列表，带模块路径的引用优先定位对应文件。
3. 类名和 `execute()` 等符号在索引完成后出现链接，多定义可选择；未解析的符号及配置值保持普通代码。
4. 流式输出、历史恢复、索引构建及关闭面板期间没有旧异步结果覆盖新正文；亮/暗主题均能分辨链接并显示悬停提示。

---

## 10. 关键设计决策记录（ADR 摘要）

1. **app-server 协议而非 `--prompt` headless**：后者每条消息一个进程（~2s 启动开销）且无权限交互；app-server 长驻 + 事件流 + 双向请求，是桌面端同款通道。降级路线保留（PROTOCOL.md）。
2. **自建 UI 而非终端 TUI**：zcode 无 Claude 的 IDE 发现机制，终端路线需自建桥接；自建 UI 把上下文注入/审批/diff 全部确定性掌控。
3. **选区按需采集而非常驻监听**：发送瞬间 ReadAction 读一次即可满足需求，避免监听器生命周期与泄漏风险。
4. **插件不分发 runtime**：只做发现（zcode-app-cli → 桌面端 → 设置覆盖），规避第三方包重分发与版本耦合问题。
5. **`-32601` 回绝 runtimePreferences**：协议源码中该错误码触发官方默认值兜底（探针验证），是官方预留的"客户端不支持"路径。
6. **审批回传 option.response 原样透传**：不自行构造 decision 结构，最大化兼容未来 option 语义（如 modify/always）。
7. **模型目录由客户端回推**（`syncModelCatalog`）：app-server 进程内目录初始为空是协议设计（CLI/桌面端都会推），不推则 resume 必炸 -32031、setModel 受限。逐模型推 `workspace/readState{runtimeModel}` 而非 `updateProviderRegistry`：后者只收 apiKeyRef（无 inline key）会破坏鉴权。**provider 必须带 baseURL**，否则目录 overlay 直接抛错（probe 实测）。
8. **resume 显式带 runtimeModel**：覆盖服务端从历史扫出的旧模型，从源头杜绝 restoreWarning；-32031→fork 仅作兜底保留。
9. **图片走消息文本内嵌 Markdown 引用**：`attachments` 只认服务端 artifact 引用且无上传 RPC（裸路径被静默丢弃，probe 实测）；`![name](file:///…)` 由 runtime 物化成图像块，多模态可见、非多模态优雅降级。能力标识从 v2 配置 modalities 合并随目录推送。
10. **删除会话直写 sqlite**：协议无删除 RPC；`session` 表外键级联 + node 内置 `node:sqlite`（无需给插件引入 JDBC 依赖），busy_timeout 兼容 WAL 并发。
11. **runtime 探测桌面端优先**：官方桌面端与 npm 包同源可互换（探针全链路验证），优先桌面端可摆脱第三方依赖；npm 作回退兼顾未装桌面端的机器。
12. **悬停高亮用不透明纯色**：半透明色 + opaque 切换在 Swing 下不清底，反复悬停叠加残影（会话列表初版翻车点）。
13. **消息排队 + `turn.completed` 为轮次结束的权威信号**：运行中发送的消息入队（气泡延迟到真正发出时回显，问答相邻成对）；派发/出队由本端 `turnActive` 门控——从派发置位到收到 `turn.completed`/`idle` 才清除——不信任 `state.updated` 的时序（实测用户环境的 runtime 会在轮次中途提前推送 `prompt_completed`，曾把第二条消息放进正在流式的轮次：回答文本被劈到两个气泡、第二条消息不被回答）。停止/断连/切换会话清空队列。
14. **斜杠命令三分路由 + 压缩走 `/compact` 文本**：① 插件本地命令（/new /clear /help）客户端消化；② 自定义命令客户端展开（服务端不展开，bundle 实证只有 CLI 内有 `expandCliCustomCommandPrompt`）——扫描 `.zcode/commands` 等根目录、`$ARGUMENTS`/`$1..$9` 占位符替换后作为普通消息发送，回显仍显示 `/name args`；③ 服务端内置（快照 `slashCommands` + `/fork`）原样发送——`submitPrompt` 只拦截 `/compact`/`/fork`，**逐字匹配**，所以命令消息一律不注入 IDE 上下文/图片。手动压缩按钮 = 发送 `/compact` 文本而非 `session/compact` RPC：后者轮次运行中会抛错且无排队能力，前者免费获得排队语义（当前轮结束自动压缩）且压缩走正常 turn 事件流（总结流式可见、`turn.completed` 后 `pollContextUsage` 自动刷新占用），与 CLI 行为一致。未知命令拒发提示（对齐 CLI TUI），避免字面发给模型。
15. **思考区时间线收纳工具行 + `zcodefile:` 引用链接**：一轮回答重构为 [折叠思考区 → 正文 → footer]，正文结论永远在最后（此前十几条 Read/Bash 平铺在回答之后，恢复历史时甚至是纯文本灰字）。思考区内容是**垂直时间线**：思考文本段与工具行按真实到达顺序交错（工具行插入后，后续思考另起一段），思考区默认折叠、标题实时报"思考中 · N 字 · N 个工具"进度（最初实现在流式期间自动展开，实测会挤掉正文首屏，改为默认折叠、点击可展开；用户展开过则收尾不强收）；历史渲染与实时同构（`TranscriptEntry.toolTarget` 携带工具目标，渲染为"✓ Read · path"进思考区）。正文引用导航统一由 `FileRefs` / `SymbolRefs` 和 `ReferenceNavigator` 处理，支持文件单行/行范围、同名候选选择，以及索引命中的符号定义跳转（§7.3）。
16. **"引用选中代码"用输入框内嵌记号 + 发送时展开**：旧实现是输入框上方的单份待发送上下文条（新引用覆盖旧引用、只能拼在消息末尾）。新实现把 `@项目相对路径:起-止行` 记号插到输入文本光标处（记号→选区信息的 map 登记在面板上），可多处引用、可放在文字任意位置——JBTextArea 无法像 zcode 富文本输入框那样内嵌 chip，可编辑的文本记号是等价物。发送时 `substituteRefs` 按记号长度降序把已登记记号替换成内联引用块（长记号先替换防前缀互含）；回显气泡保留紧凑记号。内联引用块**不带 CONTEXT_MARKER**（它长在正文中间，带标记会破坏 splitContext 的"首个标记即上下文块"约定，历史恢复也不会误折叠）。带显式引用的消息跳过自动上下文注入（与旧 explicitContext 行为一致）；用户手改记号导致查表未命中时按字面发送（记号本身仍含文件名+行号，模型可自行读文件）。
17. **Markdown 渲染继续自研扩展（表格 + IDE 词法器着色），不引入 commonmark-java**：模型输出的表格/代码都是 Markdown 源码，CLI 里的"表格样子/五颜六色"是客户端渲染（zcode.cjs 打包完整 marked 解析器实证）——插件补客户端渲染即对齐业界做法。表格 = toHtml 新增 GFM 分支（含 `\|` 转义与冒号对齐），样式为 GitHub 风格——无竖线边框、仅 border-bottom 横线分隔 + 表头浅底下划 + 偶数行斑马纹（HTMLEditorKit 无 border-collapse，四边框会 1px 重叠显厚重；`border-bottom` 单侧边框与 `td.alt` class 选择器两个非显而易见的能力由 `TableRenderCapabilityTest` 像素级守护，防止平台行为变化导致样式静默退化；暗色主题的线/底色必须与面板底色拉开亮度档位——初版暗值与 #1E1F22~#2B2D30 的面板底几乎同色，"深上加深"整表看不清，实测翻车点）；代码着色 = **借 IntelliJ 自己的词法器**（语言名→Language→SyntaxHighlighter 的 Lexer 切 token→全局配色方案取前景色→相邻同色合并包 span），零第三方依赖、与编辑器同款配色并自动跟随主题，`Markdown.toHtml` 通过可选 highlight 回调注入实现，保持 headless 纯逻辑可测；不认识的语言、>20KB、Lexer 异常一律降级纯文本。不换 commonmark-java 的原因：新依赖收益边际小（Swing HTMLEditorKit 对复杂 HTML 支持有限），且要重接 zcodefile: 链接与转义约定。

---

## 11. 已知限制与路线图

**当前限制**
- 符号导航依赖已安装语言插件的索引，只覆盖当前项目内可导航的定义；不是对任意代码片段进行语义解析。带参数的方法表达式、完整包限定名、未被贡献器收录的局部变量等可能保持普通文本；索引构建期间延后解析。文件链接按格式生成，目标是否存在在点击时检查。
- Markdown 渲染为轻量自研：已覆盖围栏代码块（IDE 词法器着色，跟随主题，>20KB 跳过）、行内代码、GFM 表格、任务列表、删除线等；嵌套列表仅平铺、工具参数 tooltip 为原始 JSON。
- 运行中发送的消息自动排队、逐轮顺序发出（气泡在真正发出时才回显，保证问答相邻；停止/断连/切换会话时清空队列）；每项目一个会话进程。
- 斜杠命令：自定义命令 frontmatter 的 `model`/`allowed-tools`/`skills` 键本版忽略（只消费 description/argument-hint/disable-noninteractive）；补全弹层只在命令名阶段（"/" 开头且无空白）出现，参数阶段不提示 inputHint。
- ApplyPatch 输入不解析路径（不进变更文件表）；恢复历史回填的变更只登记写工具，且无修改前快照时会提示"无法 diff"（不再误显示为从空文件创建）。
- 图片临时文件（`%TEMP%/zcode-idea-images/`）发送后不清理（runtime 异步读取），依赖系统清理临时目录。
- 设置为应用级（跨项目共享运行时路径）；无国际化文件（硬编码中文）。
- Settings → Plugins 列表里的插件条目图标（`pluginIcon.svg`）缺失：平台只收 SVG，官方只有 PNG，暂用 IDE 默认图标。

**路线图**
1. **Phase 6a（协议已支持）**：IDE 内起 http/sse MCP server 暴露 `getDiagnostics`（IDEA 诊断）等工具，注册到项目级 `.zcode/config.json` 的 `mcp.servers`（bundle 静态分析已确认支持 `type:"http"|"sse"` + url 配置）。
2. **Phase 6b**：设置项"IDE 会话禁用 zcode LSP 插件"（项目级配置覆盖 `plugins`），消除 jdtls/tsserver 双份开销。
3. 终端 TUI 模式（复用同一集成层）、Markdown 渲染、变更文件实时侧栏、`session/fork`/`rewind`、多会话 tab。

---

## 12. 附录：设置项与探测顺序速查

**ZcodeSettings（`%APPDATA%\JetBrains\<IDE>\options\zcode-idea.xml`）**

| 键 | 默认 | 说明 |
|---|---|---|
| `nodePath` | ""（自动） | node.exe 完整路径；空→ `where node` → 常见安装位置（Windows `C:\Program Files\nodejs`、Unix `/usr/local/bin`、`/opt/homebrew/bin`）。找到后实跑 `node --version` 校验 >= 22.19，不达标报错 |
| `runtimePath` | ""（自动） | zcode.cjs 路径；空→ ① ZCode 桌面端常见路径（`%LOCALAPPDATA%\Programs\ZCode`、`C:\Program Files\ZCode`、macOS `/Applications/ZCode.app` 的 `Contents/Resources/glm/zcode.cjs`）② npm 全局 `zcode-app-cli\vendor\zcode.cjs` ③ `where zcode` shim 反推 |
| `defaultMode` | `edit` | build/edit/plan/yolo（create 时传入；运行中可切，session/setMode 尽力同步） |
| `injectSelectionContext` | true | 发送时自动附 IDE 上下文 |
| `maxSelectionChars` | 8000 | 选区注入截断上限 |
| `preferredModelProvider` / `preferredModelId` | ""（CLI 默认） | 工具栏模型下拉的选择；create 的 `model` 参数与 resume 的 runtimeModel 用它 |
| `preferredThoughtLevel` | ""（服务端默认） | 思考强度下拉的选择；建会话后 setThoughtLevel 应用、resume 参数下发 |

**环境自检**：Settings → Tools → ZCode → 「检测环境」→ 后台跑探测 → 气泡报告 Node/Runtime 及来源。
