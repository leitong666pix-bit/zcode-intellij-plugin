package zcode.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import zcode.idea.commands.SlashCommands
import zcode.idea.core.AssistantDeltaKind
import zcode.idea.core.ConnectionState
import zcode.idea.core.ModelOption
import zcode.idea.core.SessionSummary
import zcode.idea.core.ThoughtLevelInfo
import zcode.idea.core.ToolCallInfo
import zcode.idea.core.ZcodeSessionService
import zcode.idea.settings.ZcodeSettings
import zcode.idea.vfs.DiffOpener
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), ZcodeSessionService.Listener {

    private val service = project.getService(ZcodeSessionService::class.java)

    /** Markdown 代码块着色回调（IDE 词法器实现，见 [ideCodeHighlighter]）。 */
    private val codeHighlighter = ideCodeHighlighter(project)

    /** 首选宽度报告为极小值：视口对窄视图按视口宽度拉伸，消息区永不出现横向滚动。 */
    private val messagesPanel = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            isOpaque = false
            // 宽度变化后各消息的换行宽度/高度都要重算
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    SwingUtilities.invokeLater { revalidate() }
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val p = super.getPreferredSize()
            return Dimension(1, p.height)
        }
    }
    private val scroll: JBScrollPane = JBScrollPane(messagesPanel).apply {
        verticalScrollBar.unitIncrement = 18
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    /** 流式贴底跟踪：用户上翻阅读历史时不被新内容拽回底部（见 [scrollToBottom]）。 */
    private val stickyBottom = StickyBottomTracker(JBUI.scale(8)).also { it.attach(scroll.verticalScrollBar) }
    private val statusLabel = JBLabel("● 未连接").apply {
        foreground = ChatColors.dim
        font = JBFont.label().biggerOn(-1f)
    }

    /** 上下文占用（来自订阅快照的 runtime.contextUsage，每轮结束刷新）；无数据时隐藏。点击可手动压缩。 */
    private val contextLabel = JBLabel().apply {
        foreground = ChatColors.dim
        font = JBFont.label().biggerOn(-1f)
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "点击压缩上下文（/compact）：总结当前对话，释放上下文空间"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                doCompact()
            }
        })
    }
    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        runCatching { emptyText.setText("询问 ZCode…（Enter 发送，/ 命令，Shift+Enter 换行）") }
    }
    private val sendButton = JButton("发送")
    private val stopButton = JButton("停止").apply { isEnabled = false }
    private val modeCombo = JComboBox(ZcodeSettings.Mode.entries.map { it.label }.toTypedArray())
    private val modelCombo = JComboBox<ModelOption>().apply {
        isEnabled = false
        toolTipText = "模型（连接后自动加载）"
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.display ?: "" }
        preferredSize = Dimension(128, preferredSize.height)
    }
    private val thoughtCombo = JComboBox<ThoughtLevelInfo>().apply {
        isEnabled = false
        toolTipText = "思考强度（当前模型支持时可选）"
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.let { thoughtLabel(it.value) } ?: "" }
        preferredSize = Dimension(84, preferredSize.height)
    }

    /** 程序化刷新下拉选项时置位，避免触发选择回调。 */
    private var updatingModeCombo = false
    @Volatile private var disposed = false
    private val referenceNavigator by lazy { ReferenceNavigator(project, this, { disposed }, ::showNoticePopup) }
    private var updatingModelCombo = false
    private var updatingThoughtCombo = false
    private val newSessionButton = flatButton("新会话", AllIcons.General.Add, "结束当前会话并开始新会话") { service.newSession() }
    private val resumeButton = flatButton("恢复", AllIcons.Vcs.History, "恢复历史会话") { showResumePopup() }
    private val changedFilesButton = flatButton("变更文件 (0)", AllIcons.Actions.ListChanges, "查看本次会话修改过的文件") {
        showChangedFilesPopup()
    }

    /** 手动压缩上下文：以 /compact 命令消息发送，运行中自动排队；会话有上下文数据后才可用。 */
    private val compactButton = flatButton("压缩", AllIcons.Actions.Collapseall, "压缩上下文（/compact）：总结当前对话释放空间；当前有轮次运行时会自动排队") {
        doCompact()
    }.apply { isEnabled = false }

    private var currentAssistant: AssistantMessagePanel? = null
    private val toolPanels = HashMap<String, ToolCallPanel>()

    /** 下拉加载会话列表期间置位，防止连点重复弹窗。 */
    private var listingSessions = false

    /** 输入法组合态（拼音候选未提交）：期间 Enter 只提交候选词，不发送消息。 */
    private var imeComposing = false

    /** 服务端暴露的内置斜杠命令（goal/compact/init/plan），连接后经 onSlashCommands 更新。 */
    private var builtinSlash: List<SlashCommands.CommandDef> = emptyList()

    /** 本地扫描到的自定义命令（.zcode/commands 等），后台线程刷新。 */
    private var customSlash: List<SlashCommands.CommandDef> = emptyList()

    /** 自定义命令最近一次扫描时间：弹层触发时最多 5 秒重扫一次。 */
    private var customScanAt = 0L

    /** "/" 补全弹层：非焦点，挂在输入框上方，键盘事件由输入框的 KeyAdapter 转发。 */
    private var slashPopup: JBPopup? = null
    private var slashList: JBList<SlashCommands.CommandDef>? = null

    /** 输入框里的引用记号 → 选区信息：右键"引用选中代码"插入 `@文件:行` 记号，发送时展开成引用块。 */
    private val pendingRefs = LinkedHashMap<String, zcode.idea.context.SelectionContext.Info>()

    /** 待发送的图片 chip（发送时以 Markdown 内嵌进消息文本）。 */
    private val pendingImages = mutableListOf<zcode.idea.core.ImageRef>()
    private var imagesRow: JPanel? = null
    private var attachImageButton: JButton? = null

    /** 最近一次连接状态在状态栏上的呈现，临时提示（读取会话列表… 等）结束后恢复。 */
    private var lastStatusText: String = "● 未连接"
    private var lastStatusColor: Color = ChatColors.dim

    private fun restoreStatus() {
        statusLabel.text = lastStatusText
        statusLabel.foreground = lastStatusColor
    }

    /** 模式下拉 tooltip：官方中文名 + 官方一句说明（见 ZcodeSettings.Mode）。 */
    private fun modeTooltip(m: ZcodeSettings.Mode) = "${m.label}：${m.desc}"

    /** token 数缩写：999 → 999，45123 → 45k，1250000 → 1.3M。 */
    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.0fk".format(n / 1_000.0)
        else -> n.toString()
    }

    /** false = 面板当前显示空状态欢迎页，第一条消息到达时整体移除。 */
    private var chatStarted = false

    init {
        service.addListener(this)
        builtinSlash = service.slashCommands
        modeCombo.selectedIndex = ZcodeSettings.Mode.entries.indexOf(
            ZcodeSettings.Mode.fromId(service.currentMode())
        ).coerceAtLeast(0)
        // 模式下拉显示官方中文模式名（ZcodeSettings.Mode.label），完整说明放 tooltip；
        // 固定宽度避免把工具栏撑满（最长“变更前确认”5 个汉字 + 下拉箭头）
        modeCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = value ?: ""
        }
        modeCombo.preferredSize = Dimension(104, modeCombo.preferredSize.height)
        modeCombo.toolTipText = modeTooltip(ZcodeSettings.Mode.fromId(service.currentMode()))

        sendButton.addActionListener { doSend() }
        stopButton.addActionListener { service.stopCurrentTurn() }
        modeCombo.addActionListener {
            if (updatingModeCombo) return@addActionListener
            val idx = modeCombo.selectedIndex
            ZcodeSettings.Mode.entries.getOrNull(idx)?.let {
                val requested = it.id
                onModeChanged(service.currentMode(), true)
                service.setMode(requested)
            }
        }
        modelCombo.addActionListener {
            if (updatingModelCombo) return@addActionListener
            (modelCombo.selectedItem as? ModelOption)?.let { service.selectModel(it) }
        }
        thoughtCombo.addActionListener {
            if (updatingThoughtCombo) return@addActionListener
            (thoughtCombo.selectedItem as? ThoughtLevelInfo)?.let { service.selectThoughtLevel(it.value) }
        }

        inputArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // 运行中也可发送：消息自动排队，当前轮结束后顺序发出
                sendButton.isEnabled = inputArea.text.isNotBlank()
                updateSlashPopup()
            }
        })
        inputArea.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                // "/" 补全弹层开着：方向键选条目，Enter/Tab 补全，Esc 关闭（优先于发送与默认编辑行为）
                val list = slashList
                if (list != null && slashPopup?.isVisible == true) {
                    when (e.keyCode) {
                        java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_DOWN -> {
                            e.consume()
                            val size = list.model.size
                            if (size > 0) {
                                val delta = if (e.keyCode == java.awt.event.KeyEvent.VK_DOWN) 1 else -1
                                list.selectedIndex = ((list.selectedIndex + delta) % size + size) % size
                                list.ensureIndexIsVisible(list.selectedIndex)
                            }
                            return
                        }
                        java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_TAB ->
                            if (!imeComposing) {
                                e.consume()
                                list.selectedValue?.let { completeSlash(it) }
                                return
                            }
                        java.awt.event.KeyEvent.VK_ESCAPE -> {
                            e.consume()
                            hideSlashPopup()
                            return
                        }
                    }
                }
                // IME 组合未提交时 Enter 只交给输入法（提交候选词），不触发发送
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.modifiersEx == 0 && !imeComposing) {
                    e.consume()
                    doSend()
                }
                // Ctrl+V：剪贴板里是图片时截获并暂存为待发图片；纯文本不消费，交给默认粘贴
                if (e.keyCode == java.awt.event.KeyEvent.VK_V && e.isControlDown) {
                    if (pasteImageIfAvailable()) e.consume()
                }
            }
        })
        // 跟踪输入法组合状态（拼音候选框未提交期间），见上 keyPressed 的 Enter 分支
        inputArea.addInputMethodListener(object : java.awt.event.InputMethodListener {
            override fun inputMethodTextChanged(e: java.awt.event.InputMethodEvent?) {
                val composed = e?.text
                imeComposing = composed != null && composed.beginIndex != composed.endIndex
            }

            override fun caretPositionChanged(e: java.awt.event.InputMethodEvent?) {}
        })

        toolbar = buildToolbar()
        setContent(buildContent())
        addWelcome()
        // 打开工具窗口就预热连接：首次点“恢复”或发送时不用干等 app-server 冷启动
        service.prewarm()
    }

    // ---------------------------------------------------------------- UI 组装

    private fun buildToolbar(): JPanel = JPanel(ResponsiveToolbarLayout(leftCount = 4)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 10, 5, 10),
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
        )
        // 前 4 个 = 按钮组，后 5 个 = 标签+下拉；宽度放不下整条时按此顺序流式换行、每行铺满
        add(newSessionButton)
        add(resumeButton)
        add(changedFilesButton)
        add(compactButton)
        add(statusLabel)
        add(contextLabel)
        add(modelCombo)
        add(thoughtCombo)
        add(modeCombo)
        // 父容器（BorderLayout NORTH）按 Preferred 高度给空间，而算高度时宽度可能还是旧值，
        // 折行数会差一拍；这里补一手保证最终收敛到正确高度
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (height != preferredSize.height) revalidate()
            }
        })
    }

    private fun buildContent(): JPanel = JPanel(BorderLayout(0, 0)).apply {
        add(scroll, BorderLayout.CENTER)
        add(buildInputArea(), BorderLayout.SOUTH)
    }

    private fun buildInputArea(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8, 8, 10, 8)
        val card = BubblePanel(ChatColors.card, arc = 12, outline = true).apply {
            layout = BorderLayout(0, 4)
            border = JBUI.Borders.empty(8, 10, 6, 10)
            // 北侧堆叠：引用选区条 + 图片 chips
            val north = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
                isOpaque = false
            }
            north.add(buildImagesRow().also { imagesRow = it })
            add(north, BorderLayout.NORTH)
            add(inputScroll(), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(flatButton("附图", AllIcons.FileTypes.Any_type, "添加图片（当前模型支持图像时可用）") { chooseImageFile() }
                    .also { attachImageButton = it })
                add(stopButton)
                add(sendButton)
            }, BorderLayout.SOUTH)
        }
        add(card, BorderLayout.CENTER)
    }

    /** 输入区滚动容器：限制最大高度，长文本在框内滚动而不是把工具窗口撑爆。 */
    private fun inputScroll(): JBScrollPane {
        val pane = object : JBScrollPane(inputArea) {
            override fun getPreferredSize(): Dimension {
                val p = super.getPreferredSize()
                return Dimension(p.width, p.height.coerceAtMost(JBUI.scale(160)))
            }
        }
        pane.border = JBUI.Borders.empty()
        pane.verticalScrollBar.unitIncrement = 18
        pane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        return pane
    }

    /** 图片 chips 行：缩略图 + 文件名 + 移除，随 pendingImages 重建。 */
    private fun buildImagesRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        isOpaque = false
        isVisible = false
    }

    private fun rebuildImageChips() {
        val row = imagesRow ?: return
        row.removeAll()
        for (img in pendingImages.toList()) {
            row.add(object : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
                override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, preferredSize.height)
            }.apply {
                isOpaque = false
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border()),
                    JBUI.Borders.empty(2, 4),
                )
                add(JBLabel().also { label ->
                    // 解码/缩放开销可能不小，放后台线程；完成后回 EDT 设置图标
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val icon = thumbnailOf(img.absolutePath)
                        ApplicationManager.getApplication().invokeLater { label.icon = icon }
                    }
                })
                add(JBLabel(img.fileName).apply { font = JBFont.label().biggerOn(-2f) })
                add(flatButton("", AllIcons.Actions.Close, "移除图片") {
                    pendingImages.remove(img)
                    rebuildImageChips()
                }.apply { preferredSize = Dimension(20, 20) })
            })
        }
        row.isVisible = pendingImages.isNotEmpty()
        row.revalidate()
        row.repaint()
    }

    /** 32px 缩略图；读失败时给空图标。 */
    private fun thumbnailOf(path: String): javax.swing.Icon? = runCatching {
        val image = javax.swing.ImageIcon(File(path).toURI().toURL()).image
        val scaled = image.getScaledInstance(32, 32, java.awt.Image.SCALE_FAST)
        javax.swing.ImageIcon(scaled)
    }.getOrNull()

    /** 文件选择器挑图片。 */
    private fun chooseImageFile() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
            .createSingleFileDescriptor()
            .withFileFilter { f ->
                f.extension?.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
            }
        descriptor.title = "选择要发送的图片"
        com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            val io = java.io.File(vf.path)
            if (io.isFile) addImage(zcode.idea.core.ImageRef(io.name, io.absolutePath))
        }
    }

    /** 暂存一张图片（粘贴或文件选择）。 */
    private fun addImage(img: zcode.idea.core.ImageRef) {
        if (service.currentModel?.supportsImages != true) {
            showNoticePopup("当前模型 ${service.currentModel?.display ?: ""} 不支持图像输入，请先切换到多模态模型")
            return
        }
        pendingImages.add(img)
        rebuildImageChips()
    }

    private fun clearImages() {
        // 不删临时文件：session/send 提前返回，runtime 在回合内才读取文件，删早了图就没了
        pendingImages.clear()
        imagesRow?.isVisible = false
    }

    /** 按当前模型能力启停附图入口。 */
    private fun updateImageEntryState() {
        val ok = service.currentModel?.supportsImages == true
        attachImageButton?.apply {
            isEnabled = ok
            toolTipText = if (ok) "添加图片（可多张，随消息发送）" else "当前模型不支持图像输入"
        }
    }

    private fun addWelcome() {
        chatStarted = false
        messagesPanel.add(Box.createVerticalGlue())
        messagesPanel.add(buildEmptyState())
        messagesPanel.add(Box.createVerticalGlue())
        refreshUi()
    }

    private fun buildEmptyState(): JPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        val icon = pluginIcon("/icons/zcode.png", ChatPanel::class.java)
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            isOpaque = false
            if (icon != null) {
                add(JBLabel(icon).apply { alignmentX = Component.CENTER_ALIGNMENT })
                add(Box.createVerticalStrut(8))
            }
            add(JBLabel("ZCode Assistant").apply {
                font = JBFont.label().biggerOn(5f).asBold()
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(6))
            add(dimLabel("在下方输入问题开始对话；也可以在编辑器中选中代码后右键 → ZCode").apply {
                alignmentX = Component.CENTER_ALIGNMENT
            })
        }
        add(content, GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.CENTER
        })
    }

    // ---------------------------------------------------------------- 交互

    private fun doSend() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        inputArea.text = ""
        val images = pendingImages.toList()
        clearImages()
        // 斜杠命令不携带引用/图片（服务端拦截 /compact 要求文本精确匹配）
        if (text.startsWith("/")) {
            hideSlashPopup()
            pendingRefs.clear()
            handleSlashInput(text)
            return
        }
        // 输入文本里的 @文件:行 引用记号展开成完整引用块；没有记号则走普通发送（自动上下文）
        val expanded = zcode.idea.context.SelectionContext.substituteRefs(
            text, pendingRefs, ZcodeSettings.getInstance().state.maxSelectionChars,
        )
        pendingRefs.clear()
        if (expanded != null) {
            service.sendWithRefs(text, expanded, images)
        } else {
            service.send(text, null, images)
        }
    }

    /**
     * 斜杠命令路由：本地命令（/new /clear /help）插件内消化；自定义命令客户端展开后发送；
     * 服务端内置（/compact /fork 会被服务端拦截，其余作为普通 prompt）原样发送；未知命令拒发。
     */
    private fun handleSlashInput(text: String) {
        when (val route = SlashCommands.classify(text, customSlash, builtinSlash.map { it.name })) {
            SlashCommands.Route.NotCommand -> service.send(text)
            is SlashCommands.Route.Local -> when (route.name) {
                "help" -> {
                    appendMessage(dimLabel("↑↓ 选择命令，Enter/Tab 补全，Esc 关闭。自定义命令放在项目或用户目录的 .zcode/commands/*.md").apply {
                        border = JBUI.Borders.empty(2, 10)
                    })
                    inputArea.text = "/"
                    inputArea.caretPosition = 1
                    updateSlashPopup()
                }
                else -> service.newSession() // /new、/clear
            }
            is SlashCommands.Route.Custom ->
                service.sendCommand(text, SlashCommands.buildCommandPrompt(route.def, route.rawArgs))
            is SlashCommands.Route.Server -> service.send(text)
            is SlashCommands.Route.Unknown ->
                appendMessage(dimLabel("⚠ 未知命令 /${route.name}，输入 / 查看可用命令").apply {
                    foreground = JBColor.RED
                    border = JBUI.Borders.empty(2, 10)
                })
        }
    }

    // ---------------------------------------------------------------- 斜杠命令补全

    /** 手动压缩上下文：作为 /compact 命令消息发送（运行中会自动排队，当前轮结束后由服务端执行压缩）。 */
    private fun doCompact() {
        hideSlashPopup()
        service.send("/compact")
    }

    /** 弹层条目：本地 + 服务端内置 + 自定义（按名字去重，靠前的优先）。 */
    private fun slashItems(): List<SlashCommands.CommandDef> {
        val seen = LinkedHashSet<String>()
        return (SlashCommands.LOCAL_COMMANDS.asSequence() + builtinSlash.asSequence() + customSlash.asSequence())
            .filter { seen.add(it.name) }
            .toList()
    }

    /** 文本变化时驱动弹层："/" 开头且尚未输入参数（无空白）时显示/过滤，否则关闭。 */
    private fun updateSlashPopup() {
        val query = inputArea.text.trim()
        val active = query.startsWith("/") && query.length <= 65 &&
            query.none { it.isWhitespace() } && !imeComposing
        if (!active) {
            hideSlashPopup()
            return
        }
        maybeScanCustomCommands()
        val q = query.removePrefix("/").lowercase()
        val items = slashItems().filter { it.name.startsWith(q) }
        if (items.isEmpty()) {
            hideSlashPopup()
            return
        }
        showSlashPopup(items)
    }

    /** 自定义命令扫描限频（5 秒一次），文件 IO 放后台线程，结果回 EDT。 */
    private fun maybeScanCustomCommands() {
        val now = System.currentTimeMillis()
        if (now - customScanAt < 5_000) return
        customScanAt = now
        val home = File(System.getProperty("user.home"))
        val base = project.basePath?.let(::File)
        ApplicationManager.getApplication().executeOnPooledThread {
            val cmds = runCatching { SlashCommands.scanCustomCommands(home, base) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater {
                if (cmds != customSlash) {
                    customSlash = cmds
                    if (slashPopup?.isVisible == true) updateSlashPopup()
                }
            }
        }
    }

    private fun hideSlashPopup() {
        slashPopup?.let { p -> runCatching { p.cancel() } }
        slashPopup = null
        slashList = null
    }

    private val slashCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any, index: Int, selected: Boolean, hasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, selected, hasFocus)
            val def = value as? SlashCommands.CommandDef
            text = def?.let { "/${it.name} — ${it.description}" } ?: ""
            toolTipText = def?.inputHint
            return c
        }
    }

    /** 弹层挂在输入框上方（底部输入区的补全惯例），非焦点不抢键盘，按键由输入框转发。 */
    private fun showSlashPopup(items: List<SlashCommands.CommandDef>) {
        hideSlashPopup()
        val list = JBList(items).apply {
            cellRenderer = slashCellRenderer
            // 原型值让 JBList 布局前就能算出行高，viewport 尺寸才准
            prototypeCellValue = SlashCommands.CommandDef("prototype", "", null, "local")
            selectedIndex = 0
            visibleRowCount = items.size.coerceAtMost(8)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    selectedValue?.let(::completeSlash)
                }
            })
        }
        val pane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        val vp = list.preferredScrollableViewportSize
        val width = inputArea.width.coerceAtLeast(JBUI.scale(280))
        pane.preferredSize = Dimension(width, vp.height.coerceAtLeast(JBUI.scale(28)))
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(pane, list)
            .setFocusable(false)
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setShowBorder(true)
            .createPopup()
        slashList = list
        slashPopup = popup
        val size = pane.preferredSize
        popup.setSize(size)
        val anchor = runCatching { inputArea.locationOnScreen }.getOrNull()
        if (anchor != null) {
            popup.showInScreenCoordinates(inputArea, Point(anchor.x, anchor.y - size.height - JBUI.scale(4)))
        } else {
            popup.showUnderneathOf(inputArea)
        }
    }

    /** 选中一条命令：补全为 "/name "（尾随空格进入参数输入阶段，弹层自动关闭）。 */
    private fun completeSlash(def: SlashCommands.CommandDef) {
        hideSlashPopup()
        inputArea.text = "/${def.name} "
        inputArea.caretPosition = inputArea.text.length
        inputArea.requestFocusInWindow()
    }

    /** 剪贴板有图片时截获并暂存为待发图片，返回是否已处理；PNG 编码放后台线程，不卡 EDT。 */
    private fun pasteImageIfAvailable(): Boolean = runCatching {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) return false
        val image = clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "zcode-idea-images").apply { mkdirs() }
                val f = File(dir, "paste-${System.currentTimeMillis()}.png")
                val buffered = if (image is java.awt.image.BufferedImage) image else {
                    java.awt.image.BufferedImage(image.getWidth(null), image.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
                        it.graphics.drawImage(image, 0, 0, null)
                        it.graphics.dispose()
                    }
                }
                if (javax.imageio.ImageIO.write(buffered, "png", f)) f else null
            }.getOrNull()
            if (file != null) {
                ApplicationManager.getApplication().invokeLater {
                    addImage(zcode.idea.core.ImageRef(file.name, file.absolutePath))
                }
            }
        }
        true
    }.getOrDefault(false)

    /**
     * 编辑器右键"引用选中代码"入口：把 `@文件:起-止行` 记号插到输入框光标处并登记，
     * 发送时才展开成完整引用块。可多次引用不同位置，记号可在文本任意处（对齐 zcode 的内嵌引用）。
     */
    fun attachSelection(info: zcode.idea.context.SelectionContext.Info) {
        val token = zcode.idea.context.SelectionContext.refTokenOf(info)
        pendingRefs[token] = info
        // 插入光标处，与相邻文字之间自动补空格隔开；未聚焦输入框时 caret 在末尾（或 0），行为自然
        val doc = inputArea.document
        val pos = inputArea.caretPosition.coerceIn(0, doc.length)
        val before = if (pos > 0) doc.getText(pos - 1, 1) else "\n"
        val after = if (pos < doc.length) doc.getText(pos, 1) else "\n"
        val insert = buildString {
            if (!before[0].isWhitespace()) append(' ')
            append(token)
            if (!after[0].isWhitespace()) append(' ')
        }
        doc.insertString(pos, insert, null)
        inputArea.caretPosition = pos + insert.length
        inputArea.requestFocusInWindow()
    }

    private fun showResumePopup() {
        if (listingSessions) return
        listingSessions = true
        statusLabel.text = "读取会话列表…"
        statusLabel.foreground = ChatColors.dim
        service.listSessions(
            cb = { sessions ->
                listingSessions = false
                restoreStatus()
                if (sessions.isEmpty()) {
                    showNoticePopup("没有可恢复的历史会话")
                    return@listSessions
                }
                showSessionListPopup(sessions)
            },
            onError = {
                listingSessions = false
                restoreStatus()
                showNoticePopup("读取会话失败：$it")
            },
        )
    }

    /** 会话列表面板：整行点击恢复，行尾垃圾桶删除。 */
    private fun showSessionListPopup(sessions: List<SessionSummary>) {
        val rows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            background = UIUtil.getListBackground()
        }
        val listScroll = JBScrollPane(rows).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 18
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        val popupRef = arrayOfNulls<JBPopup>(1)

        fun render(list: List<SessionSummary>) {
            rows.removeAll()
            for (s in list) {
                rows.add(sessionRow(s,
                    onChoose = {
                        popupRef[0]?.closeOk(null)
                        resume(s.sessionId)
                    },
                    onDelete = { confirmAndDelete(s) { refreshRows(::render) } },
                ))
            }
            rows.add(Box.createVerticalStrut(4))
            listScroll.preferredSize = Dimension(460, (list.size * 34 + 10).coerceAtMost(380))
            rows.revalidate()
            rows.repaint()
        }

        render(sessions)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(listScroll, listScroll)
            .setTitle("恢复会话（点击行恢复，🗑 删除）")
            .setFocusable(true)
            .setRequestFocus(true)
            .createPopup()
        popupRef[0] = popup
        popup.showUnderneathOf(resumeButton)
    }

    /** 删除后重新拉取会话列表并重绘弹窗内容。 */
    private fun refreshRows(render: (List<SessionSummary>) -> Unit) {
        service.listSessions(
            cb = { sessions ->
                if (sessions.isEmpty()) {
                    showNoticePopup("没有可恢复的历史会话")
                } else {
                    render(sessions)
                }
            },
            onError = { showNoticePopup("读取会话失败：$it") },
        )
    }

    private fun confirmAndDelete(s: SessionSummary, onDeleted: () -> Unit) {
        val title = s.title ?: "(无标题)"
        val ok = Messages.showYesNoDialog(
            project,
            "删除会话「$title」？相关的消息记录将一并删除，无法恢复。",
            "删除会话",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!ok) return
        service.deleteSession(
            s.sessionId,
            onDone = onDeleted,
            onError = { showNoticePopup("删除失败：$it") },
        )
    }

    private fun sessionRow(s: SessionSummary, onChoose: () -> Unit, onDelete: () -> Unit): JPanel {
        val time = SimpleDateFormat("MM-dd HH:mm").format(Date(s.updatedAt))
        // mode 存的是协议 id（build/edit/plan/yolo），显示用官方中文名
        val modeText = s.mode?.let { ZcodeSettings.Mode.fromId(it).label } ?: "?"
        val label = JBLabel("[$time] ${s.title ?: "(无标题)"} · $modeText").apply {
            font = JBFont.label()
        }
        val delete = flatButton("", AllIcons.Actions.GC, "删除该会话", onDelete).apply {
            preferredSize = Dimension(26, 24)
        }
        val row = object : JPanel(BorderLayout(6, 0)) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        // 悬停高亮必须用不透明纯色：半透明色在 opaque 切换时不清底，反复悬停会叠加出残影/花字
        val normalBg = UIUtil.getListBackground()
        val hoverBg = UIUtil.getListSelectionBackground()
        val choose = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) onChoose()
            }
        }
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                row.background = hoverBg
            }

            override fun mouseExited(e: MouseEvent) {
                if (!row.contains(SwingUtilities.convertPoint(e.component, e.point, row))) {
                    row.background = normalBg
                }
            }
        }
        row.apply {
            isOpaque = true
            background = normalBg
            border = JBUI.Borders.empty(3, 8)
            add(label, BorderLayout.CENTER)
            add(delete, BorderLayout.EAST)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(choose)
            addMouseListener(hover)
        }
        // 鼠标事件只派发给最深层组件：label/删除键也挂监听，让整行可点、悬停高亮不闪烁
        label.addMouseListener(choose)
        label.addMouseListener(hover)
        delete.addMouseListener(hover)
        return row
    }

    /** 在“恢复”按钮下方弹一条简短提示（列表为空/读取失败等），比状态栏文字更可见。 */
    private fun showNoticePopup(text: String) {
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(dimLabel(text).apply { border = JBUI.Borders.empty(4, 8) }, null)
            .setFocusable(false)
            .setRequestFocus(false)
            .createPopup()
            .showUnderneathOf(resumeButton)
    }

    private fun resume(sessionId: String) {
        clearMessages()
        statusLabel.text = "恢复中…"
        statusLabel.foreground = ChatColors.dim
        service.resumeSession(
            sessionId,
            onTranscript = { entries ->
                val generation = service.viewGeneration()
                restoreStatus()
                // 和实时渲染保持一致：同一轮的思考+工具+正文进同一个 AssistantMessagePanel
                //（思考/工具在折叠区、正文在 body），只有换到用户消息才切面板。
                // 分批渲染：超长历史一次性建组件会卡住 EDT
                var assistant: AssistantMessagePanel? = null
                fun flushAssistant() {
                    assistant?.let { it.done(null); appendMessage(it) }
                    assistant = null
                }
                fun nextAssistant(): AssistantMessagePanel =
                    assistant ?: AssistantMessagePanel(referenceNavigator::openFile, codeHighlighter,
                        referenceNavigator::openSymbol, referenceNavigator::decorateSymbols)
                        .also { assistant = it }
                fun renderBatch(from: Int) {
                    if (disposed || !service.isViewCurrent(generation)) return
                    val end = (from + 50).coerceAtMost(entries.size)
                    for (idx in from until end) {
                        val entry = entries[idx]
                        when {
                            entry.role == "user" && entry.text != null -> {
                                flushAssistant()
                                // 历史里的用户消息可能拼着注入的 IDE 上下文块，拆开并把上下文折叠展示
                                val (prompt, ctx) = zcode.idea.context.SelectionContext.splitContext(entry.text!!)
                                appendMessage(UserMessagePanel(prompt, ctx))
                            }
                            entry.reasoning != null ->
                                nextAssistant().appendReasoning(entry.reasoning!!)
                            entry.toolName != null -> {
                                // 历史工具行也进思考区时间线（与实时渲染一致）：紧凑灰字，默认视为已完成
                                val raw = entry.toolName!!
                                val target = entry.toolTarget?.replace('\\', '/')?.take(80)
                                val label = if (raw.startsWith("📎")) raw else buildString {
                                    append("✓ ").append(raw.removePrefix("🔧 "))
                                    if (!target.isNullOrEmpty()) append(" · ").append(target)
                                }
                                nextAssistant().addToolCall(
                                    dimLabel(label).apply { border = JBUI.Borders.empty(1, 8) }
                                )
                            }
                            entry.text != null ->
                                nextAssistant().appendText(entry.text!!)
                        }
                    }
                    if (end < entries.size) {
                        SwingUtilities.invokeLater { renderBatch(end) }
                    } else {
                        flushAssistant()
                        scrollToBottom(force = true)
                    }
                }
                renderBatch(0)
            },
            onError = {
                restoreStatus()
                statusLabel.text = "恢复失败: $it"
                statusLabel.foreground = JBColor.RED
            },
        )
    }

    private fun showChangedFilesPopup() {
        val files = service.changedFilesSnapshot()
        if (files.isEmpty()) {
            showNoticePopup("本会话尚未修改文件")
            return
        }
        val items = files.map { it.path.substringAfterLast('\\').substringAfterLast('/') + "  ·  " + it.toolName to it }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items.map { it.first })
            .setTitle("本次会话修改的文件（点击查看 diff）")
            .setItemChosenCallback { chosen ->
                items.firstOrNull { it.first == chosen }?.second?.let { DiffOpener.show(project, it) }
            }
            .createPopup()
            .showUnderneathOf(changedFilesButton)
    }

    // ---------------------------------------------------------------- 消息流渲染

    private fun clearMessages() {
        messagesPanel.removeAll()
        currentAssistant = null
        toolPanels.clear()
        chatStarted = false
    }

    private fun appendMessage(component: JComponent) {
        if (!chatStarted) {
            messagesPanel.removeAll()
            chatStarted = true
        }
        if (messagesPanel.componentCount > 0) messagesPanel.add(Box.createVerticalStrut(6))
        // 消息面板均为整行宽度组件；用户气泡在 UserMessagePanel 内部贴右，这里统一左对齐即可
        component.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(component)
        refreshUi()
        scrollToBottom()
    }

    /**
     * [force]=true 无条件贴底（用户发消息/恢复会话）；否则仅当用户仍贴底时跟随——
     * 是否贴底由 [StickyBottomTracker] 依滚动事件维护，用户上翻即停跟随、拉回底部自动恢复。
     * 注意两跳 invokeLater 期间用户可能已上翻，**执行前必须复查**——只在入队时检查的话，
     * 密集流式下每个 delta 入队的贴底操作都会压过用户的滚轮（TOCTOU），表现为"怎么翻都被拽回底部"。
     */
    private fun scrollToBottom(force: Boolean = false) {
        if (!force && !stickyBottom.stuck) return
        // 两跳 invokeLater：等布局把 preferred 高度算完再贴底
        SwingUtilities.invokeLater {
            SwingUtilities.invokeLater {
                if (!force && !stickyBottom.stuck) return@invokeLater
                val bar = scroll.verticalScrollBar
                bar.value = bar.maximum
            }
        }
    }

    private fun refreshUi() {
        messagesPanel.revalidate()
        messagesPanel.repaint()
        val running = service.state == ConnectionState.RUNNING
        sendButton.isEnabled = inputArea.text.isNotBlank()
        stopButton.isEnabled = running
        changedFilesButton.text = "变更文件 (${service.changedFilesSnapshot().size})"
    }

    // ---------------------------------------------------------------- ZcodeSessionService.Listener

    override fun onStateChanged(state: ConnectionState, detail: String?) {
        val (text, color) = when (state) {
            ConnectionState.DISCONNECTED -> "未连接" to ChatColors.dim
            ConnectionState.STARTING -> "启动 zcode…" to ChatColors.dim
            ConnectionState.READY -> "就绪" to JBColor(0x3E8E3E, 0x5FA765)
            ConnectionState.RUNNING -> "运行中…" to JBColor(0x3574F0, 0x548AF7)
            ConnectionState.DEAD -> (detail ?: "连接断开") to JBColor.RED
        }
        statusLabel.text = "● $text"
        statusLabel.foreground = color
        lastStatusText = "● $text"
        lastStatusColor = color
        refreshUi()
    }

    override fun onUserEcho(text: String, contextBlock: String?) {
        // 防御：异常时序下上一轮助手面板可能没收尾（思考区悬着），先收掉再上新气泡
        currentAssistant?.done(null)
        currentAssistant = null
        appendMessage(UserMessagePanel(text, contextBlock))
        // 自己刚发出的消息必须可见：无条件贴底
        scrollToBottom(force = true)
    }

    override fun onAssistantDelta(kind: AssistantDeltaKind, text: String) {
        val panel = ensureAssistantPanel()
        when (kind) {
            AssistantDeltaKind.TEXT -> panel.appendText(text)
            AssistantDeltaKind.REASONING -> panel.appendReasoning(text)
        }
        scrollToBottom()
    }

    override fun onToolCall(info: ToolCallInfo) {
        val panel = ToolCallPanel(info) { path -> openFile(path) }
        toolPanels[info.id] = panel
        // 工具行收进当前助手气泡的思考区时间线（可能早于首个文本 delta，必要时先建面板）
        ensureAssistantPanel().addToolCall(panel)
        scrollToBottom()
    }

    /** 取当前轮的助手面板；还没有则创建并挂到消息流。 */
    private fun ensureAssistantPanel(): AssistantMessagePanel =
        currentAssistant ?: AssistantMessagePanel(referenceNavigator::openFile, codeHighlighter,
                        referenceNavigator::openSymbol, referenceNavigator::decorateSymbols).also {
            currentAssistant = it
            appendMessage(it)
        }

    override fun onToolUpdate(info: ToolCallInfo) {
        toolPanels[info.id]?.refresh()
        refreshUi()
    }

    override fun onAssistantDone(footer: String?) {
        currentAssistant?.done(footer)
        currentAssistant = null
    }

    override fun onTurnCompleted(summary: String) {
        if (summary.isNotBlank()) {
            val target = messagesPanel.components.lastOrNull { it is AssistantMessagePanel } as? AssistantMessagePanel
            if (target != null) {
                target.done(summary)
            } else {
                appendMessage(dimLabel("✓ $summary").apply { border = JBUI.Borders.empty(2, 10, 6, 10) })
            }
        }
        refreshUi()
    }

    override fun onNotice(text: String, error: Boolean) {
        appendMessage(dimLabel((if (error) "⚠ " else "") + text).apply {
            if (error) foreground = JBColor.RED
            border = JBUI.Borders.empty(2, 10)
        })
    }

    override fun onHistoryCleared() {
        listingSessions = false
        clearMessages()
        addWelcome()
        // 新会话还没跑任何请求，快照里不会有 contextUsage，先收掉旧值
        contextLabel.isVisible = false
        compactButton.isEnabled = false
    }

    override fun onContextUsage(usedTokens: Long, sizeTokens: Long) {
        if (sizeTokens <= 0) return
        val pct = usedTokens * 100.0 / sizeTokens
        contextLabel.text = "上下文 ${formatTokens(usedTokens)}/${formatTokens(sizeTokens)}"
        contextLabel.foreground = if (pct >= 80) JBColor.RED else ChatColors.dim
        contextLabel.toolTipText = buildString {
            append("当前会话上下文占用：%,d / %,d tokens（%.1f%%）".format(usedTokens, sizeTokens, pct))
            append("\n点击压缩上下文（/compact）：总结当前对话，释放上下文空间")
            if (pct >= 80) append("\n占用已超过 80%，建议压缩")
        }
        contextLabel.isVisible = true
        compactButton.isEnabled = true
    }

    override fun onSlashCommands(commands: List<SlashCommands.CommandDef>) {
        builtinSlash = commands
        if (slashPopup?.isVisible == true) updateSlashPopup()
    }

    override fun onModeChanged(mode: String, pending: Boolean) {
        updatingModeCombo = true
        try {
            val selected = ZcodeSettings.Mode.fromId(mode)
            modeCombo.selectedIndex = ZcodeSettings.Mode.entries.indexOf(selected)
            modeCombo.toolTipText = modeTooltip(selected) + if (pending) "（切换中）" else ""
            modeCombo.isEnabled = !pending
        } finally {
            updatingModeCombo = false
        }
    }

    override fun onModelsChanged(
        models: List<ModelOption>,
        current: ModelOption?,
        thoughtLevels: List<zcode.idea.core.ThoughtLevelInfo>,
        currentThoughtLevel: String?,
    ) {
        updatingModelCombo = true
        modelCombo.removeAllItems()
        models.forEach { modelCombo.addItem(it) }
        current?.let { modelCombo.selectedItem = it }
        updatingModelCombo = false
        modelCombo.isEnabled = models.isNotEmpty()
        modelCombo.toolTipText = current?.let { "当前模型：${it.providerId}/${it.modelId}" } ?: "模型（连接后自动加载）"

        updatingThoughtCombo = true
        thoughtCombo.removeAllItems()
        thoughtLevels.forEach { thoughtCombo.addItem(it) }
        currentThoughtLevel?.let { cur ->
            thoughtLevels.firstOrNull { it.value == cur }?.let { thoughtCombo.selectedItem = it }
        }
        updatingThoughtCombo = false
        thoughtCombo.isEnabled = thoughtLevels.isNotEmpty()
        thoughtCombo.toolTipText =
            if (thoughtLevels.isEmpty()) "思考强度（当前模型不支持）"
            else "思考强度：${thoughtLevels.joinToString(" / ") { it.value }}"

        updateImageEntryState()
    }

    /** 思考强度值的中文显示；未知值原样显示。 */
    private fun thoughtLabel(value: String): String = when (value.lowercase()) {
        "minimal" -> "最低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "max" -> "最高"
        "enabled" -> "开"
        "off" -> "关"
        "default" -> "默认"
        else -> value
    }

    private fun openFile(path: String) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path)) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }

    fun dispose() {
        disposed = true
        hideSlashPopup()
        service.removeListener(this)
    }

    // ---------------------------------------------------------------- 工具栏按钮

    private companion object {
        val HoverBg = JBColor(Color(0x14000000, true), Color(0x14FFFFFF, true))
        val PressBg = JBColor(Color(0x1E000000, true), Color(0x24FFFFFF, true))
    }

    /** 扁平工具栏按钮：去掉默认 L&F 底框，悬停/按下画半透明圆角底，深浅主题通用。 */
    private fun flatButton(text: String, icon: Icon, tip: String, action: () -> Unit): JButton =
        object : JButton(text, icon) {
            init {
                toolTipText = tip
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                isFocusable = false
                margin = JBUI.insets(4, 8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = UIUtil.getLabelForeground()
                addActionListener { action() }
            }

            override fun paintComponent(g: Graphics) {
                val bg = when {
                    model.isPressed -> PressBg
                    model.isRollover -> HoverBg
                    else -> null
                }
                if (bg != null) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = bg
                        g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    } finally {
                        g2.dispose()
                    }
                }
                super.paintComponent(g)
            }
        }
}

/**
 * 工具栏自适应布局：子组件按加入顺序排成一条流，宽度不够时整条流换行、每行从左铺满
 * （像文字折行，不存在一行大片留白、另一行挤爆的情况）；单行放得下时前 leftCount 个
 * （按钮组）贴左、其余（标签+下拉）贴右，即宽面板的经典外观。
 *
 * 替代方案都有缺陷：BorderLayout+EAST 在窄面板下把左组压成 0 宽（按钮"消失"）；
 * 左右组各占一行的两行布局在中等宽度下上行右侧/下行左侧各留大片空隙。
 * preferred 高度随当前宽度（折行数）变化，工具栏上挂 componentResized→revalidate 兜底收敛。
 */
internal class ResponsiveToolbarLayout(private val leftCount: Int) : LayoutManager {

    override fun preferredLayoutSize(target: Container): Dimension {
        val ins = target.insets
        val width = if (target.width > 0) target.width else rowWidth(target) + ins.left + ins.right
        val avail = (width - ins.left - ins.right).coerceAtLeast(0)
        val rows = computeRows(target, avail)
        val h = ins.top + rows.sumOf { rowHeight(target, it) } +
                (rows.size - 1) * VGAP + ins.bottom
        return Dimension(rowWidth(target) + ins.left + ins.right, h)
    }

    override fun minimumLayoutSize(target: Container): Dimension = preferredLayoutSize(target)

    override fun layoutContainer(target: Container) {
        val ins = target.insets
        val avail = target.width - ins.left - ins.right
        if (avail <= 0) {
            repeat(target.componentCount) { target.getComponent(it).setBounds(0, 0, 0, 0) }
            return
        }
        val rows = computeRows(target, avail)
        if (rows.size == 1) {
            // 单行：左组贴左、右组贴右
            val rowH = rowHeight(target, rows[0])
            var xl = ins.left
            for (i in 0 until leftCount.coerceAtMost(target.componentCount)) {
                xl = place(target.getComponent(i), xl, ins.top, rowH) + HGAP
            }
            var xr = target.width - ins.right
            for (i in target.componentCount - 1 downTo leftCount) {
                val c = target.getComponent(i)
                val w = c.preferredSize.width
                c.setBounds(xr - w, ins.top + (rowH - c.preferredSize.height) / 2, w, c.preferredSize.height)
                xr -= w + HGAP
            }
            return
        }
        var y = ins.top
        for (row in rows) {
            val rowH = rowHeight(target, row)
            var x = ins.left
            for (i in row) {
                x = place(target.getComponent(i), x, y, rowH) + HGAP
            }
            y += rowH + VGAP
        }
    }

    private fun place(c: Component, x: Int, y: Int, rowH: Int): Int {
        val ps = c.preferredSize
        c.setBounds(x, y + (rowH - ps.height) / 2, ps.width, ps.height)
        return x + ps.width
    }

    /** 折行：每行尽量放满（下一个放不下才换行），返回每行的组件下标。 */
    private fun computeRows(target: Container, avail: Int): List<List<Int>> {
        val rows = mutableListOf<MutableList<Int>>()
        var cur = mutableListOf<Int>()
        var x = 0
        for (i in 0 until target.componentCount) {
            val w = target.getComponent(i).preferredSize.width
            if (x > 0 && x + HGAP + w > avail) {
                rows.add(cur)
                cur = mutableListOf()
                x = 0
            }
            if (x > 0) x += HGAP
            cur.add(i)
            x += w
        }
        rows.add(cur)
        return rows
    }

    private fun rowHeight(target: Container, row: List<Int>): Int =
        row.maxOf { target.getComponent(it).preferredSize.height }

    private fun rowWidth(target: Container): Int {
        var x = 0
        for (i in 0 until target.componentCount) {
            if (x > 0) x += HGAP
            x += target.getComponent(i).preferredSize.width
        }
        return x
    }

    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}

    private companion object {
        /** 同行相邻组件的水平间距。 */
        const val HGAP = 8

        /** 折行的行距。 */
        const val VGAP = 4
    }
}
