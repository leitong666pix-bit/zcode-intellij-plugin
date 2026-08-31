# ZCode Assistant for IntelliJ IDEA

在 IDEA 里与 [ZCode](https://zai.love/) AI 编码助手对话的插件：右侧 ToolWindow 聊天、选区自动作为上下文、工具调用弹窗审批、修改的文件一键 diff。

> 📐 想了解架构与具体实现，请阅读 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**（模块职责、协议事件流、线程模型、设计决策、构建坑）；协议逆向细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 架构

```
IDEA 插件（Kotlin）
 ├─ ToolWindow 聊天面板（Swing，流式渲染助手输出/工具卡片）
 ├─ ZcodeSessionService —— 会话/进程管理，事件流翻译
 │    └─ AppServerClient —— stdio 上的行式 JSON-RPC（ZCode Protocol）
 │         └─ spawn: node <zcode.cjs> app-server --cwd <项目根>（桌面端同款通道）
 ├─ SelectionContext —— 发送时读取编辑器选区/活动文件，注入 prompt
 └─ VFS 刷新 + DiffManager —— 感知并展示 zcode 对磁盘文件的修改
```

职责边界：代码搜索/阅读/修改由 zcode runtime 自己完成（ripgrep + Read/Edit/Write + 其自带 LSP 插件），直接操作磁盘文件；插件负责 IDE 上下文注入、审批交互、变更感知与展示。协议细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 前置条件

- IntelliJ IDEA 2024.3+（构建基于本机 IDEA 2025.2.4）
- JDK 21（用于构建；运行由 IDE 自带 JBR）
- Node.js >= 22.19
- zcode 运行时（三选一，自动探测顺序如下，均可在设置页覆盖）：
  1. `zcode-app-cli`（`npm i -g zcode-app-cli`，npm 全局目录下 `vendor/zcode.cjs`）
  2. ZCode 桌面端安装目录（`<安装目录>\resources\glm\zcode.cjs`）
- 已配置凭证：`~/.zcode/cli/config.json`（provider + API key，或已通过 `zcode login` 等方式配置）

## 构建与安装

```bash
# Windows（Git Bash）
# 构建用 JDK：推荐标准布局的 JDK 21（如 Amazon Corretto 21 / Temurin 21）
# 注意：Microsoft OpenJDK 会因缺少 Packages 目录导致 instrumentCode 失败，
#       被打补丁的 JBR 会干扰 Gradle 测试执行器，均不建议用作构建 JDK
export JAVA_HOME="C:/Users/<你>/.jdks/corretto-21.0.12.1"
./gradlew.bat buildPlugin
# 产物：build/distributions/zcode-idea-plugin-<version>.zip
```

IDEA 中安装：Settings → Plugins → ⚙ → Install Plugin from Disk → 选择上面的 zip。

> 构建默认使用本机 IDEA（`build.gradle.kts` 中 `intellijIdeaLocal("D:/IntelliJ/...")`）。
> 换机器时改为 `intellijIdea("2024.3")`（联网下载平台依赖）。

开发调试：`./gradlew.bat runIde`（沙箱 IDE）。

## 使用

1. 打开右侧 **ZCode** 工具窗口，直接输入问题（Enter 发送，Shift+Enter 换行）。
2. 发送时自动附带：当前活动文件路径、选中的代码（带行号，超过上限截断）、打开的文件列表（可在设置中关闭）。
3. 编辑器中选中代码 → 右键 → **ZCode** → 解释/优化/写测试/自定义指令。
4. 工具审批：edit/build 模式下，zcode 执行 Write/Edit/Bash 等工具前会弹窗，允许或拒绝；plan 模式只读；yolo 全自动（谨慎）。
5. **变更文件 (N)** 按钮列出本会话被 zcode 修改过的文件，点击用 IDE 原生 diff 对比修改前内容。
6. **新会话 / 恢复…**：会话持久化在 zcode 侧（`~/.zcode/cli/`），可跨 IDE/CLI 恢复。

## 设置

Settings → Tools → ZCode：node 路径、runtime 路径、默认权限模式、上下文注入开关与上限，附"检测环境"按钮。

## 常见问题

- **提示未找到 Node/runtime**：设置页手动指定，或先装 `npm i -g zcode-app-cli`；点"检测环境"查看探测结果。
- **权限弹窗迟迟不出现**：zcode 进程可能正忙；确认工具窗口底部状态；必要时点"停止"。
- **yolo 模式**：工具全自动执行（包括 shell），仅在可信项目使用。
- **会话与桌面端共享吗**：会话数据在用户目录下共享，但本插件独立于 ZCode 桌面端运行，不需要桌面端开着。
- **Java 项目的 jdtls**：zcode 自带的 LSP 插件会随会话启动（内存开销较大）。本插件默认不动该配置；后续版本计划提供"IDE 会话禁用 zcode LSP 插件 + IDEA 诊断注入"选项。

## 路线图（见 docs/PROTOCOL.md 附录）

- IDEA 诊断（错误/警告）作为上下文/工具暴露给 zcode
- 终端 TUI 模式（集成终端跑 zcode，IDE 作为 MCP server 提供选区/诊断工具）
- 更丰富的 markdown 渲染、变更文件实时列表
