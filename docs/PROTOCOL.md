# ZCode Protocol（app-server stdio JSON-RPC）实现笔记

来源：对 `zcode.cjs`（runtime 0.16.5，ZCode Desktop 3.9.2 同源）的静态逆向 + 三轮活体探针（见 `probe-artifacts/`）。
通道：`node <zcode.cjs> app-server`，stdin/stdout 每行一个 JSON 对象（newline-delimited JSON-RPC，信封无 `jsonrpc` 字段）。

## 请求（client → server）

写入一行 `{"id":<number>,"method":"...","params":{...}}`，stdout 回 `{"id":<number>,"result":...}` 或 `{"id":<number>,"error":{"code":<int>,"message":"...","data":...}}`。

**不需要 initialize 握手**（`initialize`/`ping` 均返回 -32601 Method not found）。探针已验证的请求：

| 方法 | params | result | 备注 |
|---|---|---|---|
| `session/create` | `{workspace:{workspaceKey,workspacePath}, mode?:\"build\\|edit\\|plan\\|yolo\", persistence?}` | `{session:{sessionId,mode,model,status,...}, projection, runtime:{eventSeq,...}, settings, messages:[]}` | sessionId 在 `result.session.sessionId`；首次 create 会先发两个 server 请求（见下） |
| `session/subscribe` | `{sessionId, deliveryKind:"desktop-continuous"\|"web-remote-replayable", afterSeq?:int, includeSnapshot?:bool}` | `{eventSeq, events:[...], sessionId}` | **订阅后正文事件才通过 `session/event` 通知推送**；`afterSeq` **不传只推订阅后的新事件**，传了则回放该 seq 之后的历史事件（断线补播用——恢复会话场景传 0 会重放全部历史，UI 若再渲染转录就重复一遍） |
| `session/send` | `{sessionId, content:<string>, inputId?}` | `{accepted:true, sessionId, stateRevision}` | 发出即返回；后续走通知流。同 session 并发第二条会被拒（-32010） |
| `session/stop` | `{sessionId}` | — | 中止当前 turn |
| `session/read` | `{sessionId}` | `{messages:[{info:{role,messageId,model,time,tokens,finish,...}, parts:[...]}]}` | 完整转录（恢复会话渲染用） |
| `session/list` | `{workspace:{workspaceKey,workspacePath}}` | `{sessions:[{sessionId,title,mode,status,createdAt,updatedAt,workspace}]}` | 按时间倒序 |
| `session/events` | `{sessionId, afterSeq:int, limit?:int}` | `{events:[...]}` | 按 seq 补播事件行（与通知同构） |
| `session/resume` | `{sessionId, workspace:{workspaceKey,workspacePath}, runtimeModel?, thoughtLevel?, mcpServers?, toolAllowlist?, toolDenylist?}` | 同 create | 把既有会话变为**当前 app-server 进程内活跃**——`session/read`/`send`/`subscribe` 对未激活会话抛 `Session is not active: <id>`（源码 `sessions.get(t)` 查不到即抛）。恢复历史会话必须先 resume，即使客户端进程已存活 |

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
→ session/create {workspace, mode}
   （期间对 server 请求回 -32601；requestPermission 若出现则转 UI）
→ session/subscribe {sessionId, deliveryKind:"desktop-continuous"}    ← 不传 afterSeq：只推订阅后的新事件
→ session/send {sessionId, content}
← state.updated(status=running)
← session/event: turn.started / model.streaming(text_delta...) / tool.updated / turn.completed
← state.updated(status=idle, reason=prompt_completed)
（工具写盘直接落磁盘文件；Edit/Write 在 build/edit 模式触发 interaction/requestPermission）
```

## 其他要点

- mode 传参：create 时传 `mode:"edit"`，state.updated 中确认 `mode.current=edit`（create result 的 `session.mode` 字段可能仍显示 build，以 state patch 为准）。
- 换 runtime：`zcode-app-cli` 的 `vendor/zcode.cjs` 与桌面端 `D:\Tools\ZCode\resources\glm\zcode.cjs` 同源可互换，均需 Node ≥22.19。
- 环境变量：`NO_COLOR=1`、`ZCODE_DISABLE_UPDATE_CHECK=1`；Windows 下 spawn 用 `windowsHide`。
- 凭证：`~/.zcode/cli/config.json`（provider.zai + apiKey），headless 直接可用，不依赖桌面端登录态。
