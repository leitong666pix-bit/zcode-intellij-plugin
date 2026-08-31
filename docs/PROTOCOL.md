# ZCode Protocol（app-server stdio JSON-RPC）实现笔记

来源：对 `zcode.cjs`（runtime 0.16.5，ZCode Desktop 3.9.2 同源）的静态逆向 + 多轮活体探针（见 `probe-artifacts/` 与本文"关键机制"小节的实测记录）。
通道：`node <zcode.cjs> app-server`，stdin/stdout 每行一个 JSON 对象（newline-delimited JSON-RPC，信封无 `jsonrpc` 字段）。

## 请求（client → server）

写入一行 `{"id":<number>,"method":"...","params":{...}}`，stdout 回 `{"id":<number>,"result":...}` 或 `{"id":<number>,"error":{"code":<int>,"message":"...","data":...}}`。

**不需要 initialize 握手**（`initialize`/`ping` 均返回 -32601 Method not found）。探针已验证的请求：

| 方法 | params | result | 备注 |
|---|---|---|---|
| `session/create` | `{workspace:{workspaceKey,workspacePath}, mode?:"build\|edit\|plan\|yolo", model?:{providerId,modelId}, persistence?}` | `{session:{sessionId,mode,model,status,...}, projection, runtime:{eventSeq,...}, settings, messages:[]}` | sessionId 在 `result.session.sessionId`；`model` 显式指定初始模型（须在 workspace 模型目录中，见下文「模型目录」）；首次 create 会先发两个 server 请求（见下） |
| `session/subscribe` | `{sessionId, deliveryKind:"desktop-continuous"\|"web-remote-replayable", afterSeq?:int, includeSnapshot?:bool}` | `{eventSeq, events:[...], sessionId, snapshot?}` | **订阅后正文事件才通过 `session/event` 通知推送**；`afterSeq` **不传只推订阅后的新事件**，传了则回放该 seq 之后的历史事件（断线补播用——恢复会话场景传 0 会重放全部历史，UI 若再渲染转录就重复一遍）。`includeSnapshot:true` 时回复带完整快照，**`snapshot.runtime.contextUsage = {used, size, cost?, cache?, breakdown?}` 是当前上下文占用**（`size` = 模型上下文窗口，如 glm-5.3 的 1000000；空会话/首轮前该字段缺省）。重复 subscribe 无副作用（不回放事件），可当快照轮询用——工具栏"上下文 x/y"即每轮 turn.completed 后再 subscribe 一次取新值（实测 `used` 与 turn.completed 的 `usage.totalTokens` 一致） |
| `session/send` | `{sessionId, content:<string>, inputId?}` | `{accepted:true, sessionId, stateRevision, modelRuntimeRevision}` | 发出即返回；后续走通知流。同 session 并发第二条会被拒（-32010）。**图片输入不走 `attachments` 字段**（见「图片输入」） |
| `session/stop` | `{sessionId}` | — | 中止当前 turn |
| `session/read` | `{sessionId}` | `{messages:[{info:{role,messageId,model,time,tokens,finish,...}, parts:[...]}]}` | 完整转录（恢复会话渲染用）。只对进程内活跃会话可用 |
| `session/list` | `{workspace:{workspaceKey,workspacePath}, includeArchived?, limit?}` | `{sessions:[{sessionId,title,mode,status,createdAt,updatedAt,workspace}]}` | 按时间倒序。**没有 session/delete RPC**——删除须直删 sqlite（见「会话存储与删除」） |
| `session/events` | `{sessionId, afterSeq:int, limit?:int}` | `{events:[...]}` | 按 seq 补播事件行（与通知同构） |
| `session/resume` | `{sessionId, workspace:{workspaceKey,workspacePath}, runtimeModel?, thoughtLevel?, mcpServers?, toolAllowlist?, toolDenylist?}` | 同 create | 把既有会话变为**当前 app-server 进程内活跃**。strict schema（多余字段 -32602）。**必须带 `runtimeModel`**：服务端会从历史消息恢复"上次使用的模型"，该模型已下线时置 `restoreWarning`，此后所有 send 被 -32031 拒绝；`runtimeModel` 可整体覆盖（实测根治）。见「模型目录」 |
| `session/setModel` | `{sessionId, model:{providerId,modelId}, runtimeModel?, expectedRevision?, persistAsWorkspaceLastUsed?}` | — | 切会话模型。仅传 `model` 只切引用、**不清 restoreWarning**；带 `runtimeModel`（完整 provider 定义）才会清除并落注册表 |
| `session/setThoughtLevel` | `{sessionId, thoughtLevel:"low"\|"high"\|"max"\|..., runtimeModel?, expectedRevision?}` | — | 思考强度；可用值随模型（readState 的 `thoughtLevel.available`） |
| `session/setMode` | `{sessionId, mode, expectedRevision?}` | — | 权限模式切换 |
| `session/close` | `{sessionId}` | `{closed?}` | 结束进程内活跃状态（非活跃会话调用会报错，忽略即可） |
| `session/fork` | `{sessionId, target?:{kind:"latestCheckpoint"\|"checkpoint"\|"message", ...}, expectedRevision?}` | 同 create | 派生继承历史的新会话；插件作为 -32031 的兜底路径保留 |
| `workspace/readState` | `{workspace, runtimeModel?, preferWorkspaceDefaults?}` | `{modelCatalog:{available,providers,revision,...}, settings:{model:{available, current}, thoughtLevel:{available,current}, mode}, slashCommands, workspace}` | 读工作区状态。**带 `runtimeModel` 调用即把该 provider/model 写入服务端 workspace 模型目录**（i5 合并）——这是客户端向服务端"回推"模型目录的主通道，也是 resume 不炸 -32031 的前提。不带 runtimeModel 的纯读用于拉取可用模型/思考强度列表 |

## 通知（server → client，无 id）

- `state.updated` `{patch:{status:"running"|"idle", mode?, model?}, reason:"prompt_started"|"prompt_completed"|..., revision, sessionId}` — turn 开始/结束的权威信号（`status==idle` 即空闲）
- `session/event` `{type, seq, sessionId, turnId, timestamp, eventId, deliveryKind, payload}` — **订阅后才有**，正文全在这里
- `v4/telemetry/event`、`computer-use/operation-event` — 遥测，可忽略

### session/event 的 type（探针实测）

| type | payload 要点 |
|---|---|
| `session.titleUpdated` | `{title, previousTitle, source}` |
| `turn.started` | `{turnNumber, input, messageId, queryId}` |
| `model.streaming` | `{assistantMessageId, delta, done, kind}`，kind ∈ `text_delta`（助手正文增量）/ `reasoning_delta`（思考增量）/ `tool_input_start`{toolCallId,toolName} / `tool_input_delta`{toolCallId,delta=JSON片段} / `tool_input_end` / `tool_call`{toolCallId,toolName,input=object} |
| `tool.updated` | `{toolCallId, toolName, kind}`，kind ∈ `scheduled` → `started` → `result`（`result:{success, content, perf, truncated}`）→ `batch` |
| `checkpoint.created` | `{checkpointId, scope, fileCount, diffRef}` — 伴随产生文件改动的工具出现 |
| `turn.completed` | `{response, tokenCount, usage, toolCallCount, duration, resultType}` |
| `session.updated` | `{messageCount, model, toolCount, iteration}` |

## server → client 请求（带 string id，如 "server-1"，必须回响应）

| 方法 | params | 建议响应 |
|---|---|---|
| `interaction/requestPermission` | `{toolName, input, reason, riskLevel, requestId, sessionId, options:[{optionId("allow_once"/"deny"...), name, kind, response}]}` | 取用户选择的 `options[i].response` 原样返回（`{decision:"allow"\|"deny"\|"modify", ...}`）；不响应会挂起直至超时 |
| `session/requestRuntimePreferences` | `{sessionId, scope}` | **回 `-32601` 错误是官方容错路径**，运行时用默认值继续 |
| `interaction/requestOfficialMcpAuthHeaders` | `{mcpKey, pluginId, ...}` | 回 `-32601`（官方插件 MCP 鉴权头，IDE 场景不需要） |

## 已验证的端到端流程

```
spawn node zcode.cjs app-server (cwd=项目根)
→ workspace/readState {workspace, runtimeModel} × N     ← 逐模型回推，填充服务端模型目录
→ workspace/readState {workspace}                        ← 拉取权威模型/思考强度列表
→ session/create {workspace, mode, model}                （期间对 server 请求回 -32601）
→ session/subscribe {sessionId, deliveryKind:"desktop-continuous"}    ← 不传 afterSeq
→ session/send {sessionId, content}
← state.updated(status=running)
← session/event: turn.started / model.streaming(text_delta...) / tool.updated / turn.completed
← state.updated(status=idle, reason=prompt_completed)
（工具写盘直接落磁盘文件；Edit/Write 在 build/edit 模式触发 interaction/requestPermission）
```

## 关键机制（逆向 + 探针实证）

### workspace 模型目录与 runtimeModel（-32031 根因）

- 服务端每个进程内维护 `workspaceModelCatalogs[workspaceKey]`，**初始为空**，只能由客户端回推填充：`workspace/readState{runtimeModel}`（合并单 provider）或 `workspace/updateProviderRegistry`（整体注册表）。
- `runtimeModel`（Mf）结构：`{revision:string, generatedAt:number, model:{providerId,modelId}, provider:_Le, thoughtLevel?}`；`_Le` provider 必须含 `providerId/kind/source/models[≥1]`，**`baseURL` 必带**（缺失会在目录 overlay 时炸 `Model provider zai is missing baseURL`），apiKey 可 inline `{source:"inline", value}`。
- 回推合并语义（源码 `i5/HRi`）：**每次只保留被选中的那个模型**（catalog 为空时）——所以要把全部模型推齐需要逐模型各推一次。
- `session/resume` 的行为：从历史消息扫出"上次使用的模型"；不在目录中 → 置 `restoreWarning` → 所有 `session/send` 抛 `-32031 ZCODE_RUNTIME_MODEL_UNAVAILABLE`（"历史任务使用的模型已不可用"）。**resume 参数带 `runtimeModel` 即整体覆盖、不设 restoreWarning**（实测根治）。
- 目录回推后：create 可指定任意目录内模型；`session/setModel`（纯 model）可自由切换；`supportsImages` 等能力标记也在回推时带上。

### 图片输入（多模态）

- `session/send` 的 `attachments:[{ref,fileName,mime,bytes}]` 字段**只认服务端发放的 `zcode-artifact://` 引用**，且 app-server 协议**没有客户端上传 RPC**（有 base64 上传 schema 但属于移动端桥接协议）。传本地路径/file:// URI 均被**静默丢弃**（模型看不到，探针实测）。
- 正确姿势：**在消息文本里内嵌 Markdown 图片引用** `![名字](file:///C:/path/img.png)`（正斜杠本地路径亦可），runtime 解析后物化成图像内容块。多模态模型（glm-5.3-flash）实测能看到图并正确回答。
- 非多模态模型（glm-5.3）：不报错不崩溃，runtime 在读文件时告知模型"当前模型不支持图像"，模型如实回答看不到（优雅降级）。
- 能力标识：`workspace/readState` 返回的 `settings.model.available[].supportsImages`。注意 CLI 配置 `~/.zcode/cli/config.json` **没有** modalities 信息，缺省即 false——需从桌面端 `~/.zcode/v2/config.json` 的 `modalities.input` 合并后随 runtimeModel 推送。

### 会话存储与删除

- 会话在 `~/.zcode/cli/db/db.sqlite`（WAL 模式）：`session` 表主键 `sess_<uuid>`，`message/part/session_entry/...` 等表外键 `ON DELETE CASCADE`。
- 协议**无删除 RPC**。删除 = `session/close`（若活跃）+ `DELETE FROM session WHERE id=?`（消息级联清零，实测）。可借 Node ≥22.5 内置的 `node:sqlite`（注意 `PRAGMA busy_timeout` 处理与服务端并发写）。
- 归档字段 `time_archived` 存在但同样无 RPC 写入口。

## 其他要点

- mode 传参：create 时传 `mode:"edit"`，state.updated 中确认 `mode.current=edit`（create result 的 `session.mode` 字段可能仍显示 build，以 state patch 为准）。
- 换 runtime：`zcode-app-cli` 的 `vendor/zcode.cjs` 与桌面端 `D:\Tools\ZCode\resources\glm\zcode.cjs` 同源可互换（同发布族，协议面一致，均经探针全链路验证），均需 Node ≥22.19。
- 环境变量：`NO_COLOR=1`、`ZCODE_DISABLE_UPDATE_CHECK=1`；Windows 下 spawn 用 `windowsHide`。
- 凭证：`~/.zcode/cli/config.json`（provider.zai + apiKey），headless 直接可用，不依赖桌面端登录态；两份 runtime 读同一个该文件、共用同一个会话库。
- 错误码速查：`-32601` 方法不存在（也是官方"客户端不支持"容错路径）、`-32602` 参数校验失败（`data.message` 含期望）、`-32010` 同会话并发 prompt、`-32031` 模型不可用/restoreWarning、`-32012` 目录 revision 不匹配。
