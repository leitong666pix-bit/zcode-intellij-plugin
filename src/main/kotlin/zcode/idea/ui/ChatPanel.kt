package zcode.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import zcode.idea.core.AssistantDeltaKind
import zcode.idea.core.ConnectionState
import zcode.idea.core.ModelOption
import zcode.idea.core.SessionSummary
import zcode.idea.core.ToolCallInfo
import zcode.idea.core.ZcodeSessionService
import zcode.idea.settings.ZcodeSettings
import zcode.idea.vfs.DiffOpener
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
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
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), ZcodeSessionService.Listener {

    private val service = project.getService(ZcodeSessionService::class.java)

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
    private val statusLabel = JBLabel("● 未连接").apply {
        foreground = ChatColors.dim
        font = JBFont.label().biggerOn(-1f)
    }

    /** 上下文占用（来自订阅快照的 runtime.contextUsage，每轮结束刷新）；无数据时隐藏。 */
    private val contextLabel = JBLabel().apply {
        foreground = ChatColors.dim
        font = JBFont.label().biggerOn(-1f)
        isVisible = false
    }
    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        runCatching { emptyText.setText("询问 ZCode…（Enter 发送，Shift+Enter 换行）") }
    }
    private val sendButton = JButton("发送")
    private val stopButton = JButton("停止").apply { isEnabled = false }
    private val modeCombo = JComboBox(ZcodeSettings.Mode.entries.map { it.label }.toTypedArray())
    private val modelCombo = JComboBox<String>().apply {
        isEnabled = false
        toolTipText = "模型（连接后自动加载）"
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value ?: "" }
        preferredSize = Dimension(128, preferredSize.height)
    }
    private val thoughtCombo = JComboBox<String>().apply {
        isEnabled = false
        toolTipText = "思考强度（当前模型支持时可选）"
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value ?: "" }
        preferredSize = Dimension(84, preferredSize.height)
    }

    /** 程序化刷新下拉选项时置位，避免触发选择回调。 */
    private var updatingModelCombo = false
    private var updatingThoughtCombo = false
    private val newSessionButton = flatButton("新会话", AllIcons.General.Add, "结束当前会话并开始新会话") { service.newSession() }
    private val resumeButton = flatButton("恢复", AllIcons.Vcs.History, "恢复历史会话") { showResumePopup() }
    private val changedFilesButton = flatButton("变更文件 (0)", AllIcons.Actions.ListChanges, "查看本次会话修改过的文件") {
        showChangedFilesPopup()
    }

    private var currentAssistant: AssistantMessagePanel? = null
    private val toolPanels = HashMap<String, ToolCallPanel>()

    /** 下拉加载会话列表期间置位，防止连点重复弹窗。 */
    private var listingSessions = false

    /** 右键"引用选中代码"附带的上下文（发送时作为显式上下文，优先于自动采集）。 */
    private var pendingAttach: zcode.idea.context.SelectionContext.Info? = null
    private var attachLabel: JBLabel? = null
    private var attachRow: JPanel? = null

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
            val idx = modeCombo.selectedIndex
            ZcodeSettings.Mode.entries.getOrNull(idx)?.let {
                service.setMode(it.id)
                modeCombo.toolTipText = modeTooltip(it)
            }
        }
        modelCombo.addActionListener {
            if (updatingModelCombo) return@addActionListener
            val display = modelCombo.selectedItem as? String ?: return@addActionListener
            service.availableModels.firstOrNull { it.display == display }?.let { service.selectModel(it) }
        }
        thoughtCombo.addActionListener {
            if (updatingThoughtCombo) return@addActionListener
            val display = thoughtCombo.selectedItem as? String ?: return@addActionListener
            service.availableThoughtLevels.firstOrNull { thoughtLabel(it.value) == display || it.label == display }
                ?.let { service.selectThoughtLevel(it.value) }
        }

        inputArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                sendButton.isEnabled = inputArea.text.isNotBlank() && service.state != ConnectionState.RUNNING
            }
        })
        inputArea.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.modifiersEx == 0) {
                    e.consume()
                    doSend()
                }
                // Ctrl+V：剪贴板里是图片时截获并暂存为待发图片，不落输入框
                if (e.keyCode == java.awt.event.KeyEvent.VK_V && e.isControlDown) {
                    pasteImageIfAvailable()?.let { e.consume() }
                }
            }
        })

        toolbar = buildToolbar()
        setContent(buildContent())
        addWelcome()
        // 打开工具窗口就预热连接：首次点“恢复”或发送时不用干等 app-server 冷启动
        service.prewarm()
    }

    // ---------------------------------------------------------------- UI 组装

    private fun buildToolbar(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(4, 10, 5, 10),
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
        )
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(newSessionButton)
            add(resumeButton)
            add(changedFilesButton)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(statusLabel)
            add(contextLabel)
            add(modelCombo)
            add(thoughtCombo)
            add(modeCombo)
        }
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)
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
            north.add(buildAttachRow().also { attachRow = it })
            north.add(buildImagesRow().also { imagesRow = it })
            add(north, BorderLayout.NORTH)
            add(inputArea, BorderLayout.CENTER)
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
                add(JBLabel().apply { icon = thumbnailOf(img.absolutePath) })
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

    /** 显式引用的选区上下文条：显示在输入框上方，可一键移除；tooltip 预览内容。 */
    private fun buildAttachRow(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(0, 2, 4, 2)
        add(JBLabel().apply {
            icon = AllIcons.FileTypes.Any_type
            font = JBFont.label().biggerOn(-1f)
        }.also { attachLabel = it }, BorderLayout.CENTER)
        add(flatButton("", AllIcons.Actions.Close, "移除引用的选区") {
            pendingAttach = null
            attachRow?.apply { isVisible = false; revalidate(); repaint() }
        }.apply { preferredSize = Dimension(26, 24) }, BorderLayout.EAST)
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
        val attach = pendingAttach
        pendingAttach = null
        attachRow?.isVisible = false
        val images = pendingImages.toList()
        clearImages()
        val explicitContext = attach?.let {
            zcode.idea.context.SelectionContext.buildSelectionBlock(
                it, ZcodeSettings.getInstance().state.maxSelectionChars,
            )
        }
        service.send(text, explicitContext, images)
    }

    /** 剪贴板有图片时写入临时 PNG 并暂存，返回是否已处理。 */
    private fun pasteImageIfAvailable(): Boolean = runCatching {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) return false
        val image = clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
        val dir = File(System.getProperty("java.io.tmpdir"), "zcode-idea-images").apply { mkdirs() }
        val file = File(dir, "paste-${System.currentTimeMillis()}.png")
        val buffered = if (image is java.awt.image.BufferedImage) image else {
            java.awt.image.BufferedImage(image.getWidth(null), image.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
                it.graphics.drawImage(image, 0, 0, null)
                it.graphics.dispose()
            }
        }
        if (!javax.imageio.ImageIO.write(buffered, "png", file)) return false
        addImage(zcode.idea.core.ImageRef(file.name, file.absolutePath))
        true
    }.getOrDefault(false)

    /** 编辑器右键"引用选中代码"入口：挂上待发送的选区上下文并把焦点交给输入框。 */
    fun attachSelection(info: zcode.idea.context.SelectionContext.Info) {
        pendingAttach = info
        val name = info.relativePath ?: info.activeFile?.name ?: "选区"
        val lineRange = if (info.startLine != null && info.endLine != null) "第 ${info.startLine}-${info.endLine} 行" else ""
        val lineCount = info.selectionText?.lineSequence()?.count() ?: 0
        attachLabel?.apply {
            text = "已引用 $name $lineRange（共 $lineCount 行）"
            toolTipText = info.selectionText?.take(800)
        }
        attachRow?.apply { isVisible = true; revalidate(); repaint() }
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
                restoreStatus()
                // 和实时渲染保持一致：同一轮的思考+正文进同一个 AssistantMessagePanel
                //（思考在折叠区、正文在 body），只有换到用户消息才切面板
                var assistant: AssistantMessagePanel? = null
                fun flushAssistant() {
                    assistant?.let { it.done(null); appendMessage(it) }
                    assistant = null
                }
                for (entry in entries) {
                    when {
                        entry.role == "user" && entry.text != null -> {
                            flushAssistant()
                            // 历史里的用户消息可能拼着注入的 IDE 上下文块，拆开并把上下文折叠展示
                            val (prompt, ctx) = zcode.idea.context.SelectionContext.splitContext(entry.text!!)
                            appendMessage(UserMessagePanel(prompt, ctx))
                        }
                        entry.reasoning != null ->
                            (assistant ?: AssistantMessagePanel().also { assistant = it }).appendReasoning(entry.reasoning!!)
                        entry.toolName != null ->
                            appendMessage(dimLabel(entry.toolName!!).apply { border = JBUI.Borders.empty(2, 10) })
                        entry.text != null ->
                            (assistant ?: AssistantMessagePanel().also { assistant = it }).appendText(entry.text!!)
                    }
                }
                flushAssistant()
                scrollToBottom()
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

    private fun scrollToBottom() {
        // 两跳 invokeLater：等布局把 preferred 高度算完再贴底
        SwingUtilities.invokeLater {
            SwingUtilities.invokeLater {
                scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
            }
        }
    }

    private fun refreshUi() {
        messagesPanel.revalidate()
        messagesPanel.repaint()
        val running = service.state == ConnectionState.RUNNING
        sendButton.isEnabled = !running && inputArea.text.isNotBlank()
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
        appendMessage(UserMessagePanel(text, contextBlock))
        currentAssistant = null
    }

    override fun onAssistantDelta(kind: AssistantDeltaKind, text: String) {
        if (currentAssistant == null) {
            val panel = AssistantMessagePanel()
            currentAssistant = panel
            appendMessage(panel)
        }
        when (kind) {
            AssistantDeltaKind.TEXT -> currentAssistant?.appendText(text)
            AssistantDeltaKind.REASONING -> currentAssistant?.appendReasoning(text)
        }
        scrollToBottom()
    }

    override fun onToolCall(info: ToolCallInfo) {
        val panel = ToolCallPanel(info) { path -> openFile(path) }
        toolPanels[info.id] = panel
        appendMessage(panel)
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
        clearMessages()
        addWelcome()
        // 新会话还没跑任何请求，快照里不会有 contextUsage，先收掉旧值
        contextLabel.isVisible = false
    }

    override fun onContextUsage(usedTokens: Long, sizeTokens: Long) {
        if (sizeTokens <= 0) return
        val pct = usedTokens * 100.0 / sizeTokens
        contextLabel.text = "上下文 ${formatTokens(usedTokens)}/${formatTokens(sizeTokens)}"
        contextLabel.foreground = if (pct >= 80) JBColor.RED else ChatColors.dim
        contextLabel.toolTipText =
            "当前会话上下文占用：%,d / %,d tokens（%.1f%%）".format(usedTokens, sizeTokens, pct)
        contextLabel.isVisible = true
    }

    override fun onModelsChanged(
        models: List<ModelOption>,
        current: ModelOption?,
        thoughtLevels: List<zcode.idea.core.ThoughtLevelInfo>,
        currentThoughtLevel: String?,
    ) {
        updatingModelCombo = true
        modelCombo.removeAllItems()
        models.forEach { modelCombo.addItem(it.display) }
        current?.let { modelCombo.selectedItem = it.display }
        updatingModelCombo = false
        modelCombo.isEnabled = models.isNotEmpty()
        modelCombo.toolTipText = current?.let { "当前模型：${it.providerId}/${it.modelId}" } ?: "模型（连接后自动加载）"

        updatingThoughtCombo = true
        thoughtCombo.removeAllItems()
        thoughtLevels.forEach { thoughtCombo.addItem(thoughtLabel(it.value)) }
        currentThoughtLevel?.let { thoughtCombo.selectedItem = thoughtLabel(it) }
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
