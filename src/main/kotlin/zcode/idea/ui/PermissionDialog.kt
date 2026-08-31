package zcode.idea.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.Box.createVerticalBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 工具调用审批对话框。展示 zcode 请求执行的工具与参数，
 * 返回所选 option 的 response 对象（原样回给 app-server）。
 */
class PermissionDialog(project: Project, private val params: JsonObject) : DialogWrapper(project, true) {

    private var chosenResponse: JsonObject? = null
    private val options: List<Pair<String, JsonObject>> = parseOptions(params)

    init {
        title = "ZCode 请求执行工具"
        setModal(true)
        init()
    }

    private fun parseOptions(params: JsonObject): List<Pair<String, JsonObject>> {
        val arr = params.getAsJsonArray("options") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asJsonObject
            val label = o.get("name")?.asString ?: o.get("optionId")?.asString ?: return@mapNotNull null
            val resp = o.getAsJsonObject("response") ?: JsonObject().apply {
                addProperty("decision", o.get("optionId")?.asString ?: "deny")
            }
            label to resp
        }
    }

    override fun createActions(): Array<javax.swing.Action> {
        val actions = mutableListOf<javax.swing.Action>()
        options.forEach { (label, response) ->
            actions.add(object : javax.swing.AbstractAction(label) {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    chosenResponse = response
                    close(OK_EXIT_CODE, true)
                }
            })
        }
        cancelAction.putValue(javax.swing.Action.NAME, "拒绝")
        actions.add(cancelAction)
        return actions.toTypedArray()
    }

    override fun doCancelAction() {
        chosenResponse = null
        super.doCancelAction()
    }

    fun showAndGetResponse(): JsonObject? {
        show()
        return chosenResponse ?: denyResponse()
    }

    private fun denyResponse(): JsonObject {
        val denyOption = options.firstOrNull { it.first.contains("deny", ignoreCase = true) }
        return denyOption?.second ?: JsonObject().apply {
            addProperty("decision", "deny")
            addProperty("reason", "user cancelled")
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        val tool = params.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "(unknown)"
        val risk = params.get("riskLevel")?.takeIf { !it.isJsonNull }?.asString
        val reason = params.get("reason")?.takeIf { !it.isJsonNull }?.asString

        val header = createVerticalBox().apply {
            add(JLabel("<html><b>工具: $tool</b>" + (risk?.let { "&nbsp;&nbsp;<span style='color:#CC7A00'>$it 风险</span>" } ?: "") + "</html>"))
            reason?.let { add(JLabel("<html><span style='color:#888888'>$it</span></html>")) }
            add(Box.createVerticalStrut(6))
        }
        panel.add(header, BorderLayout.NORTH)

        val input = params.get("input")?.takeIf { it.isJsonObject }?.asJsonObject
        val pretty = if (input != null) {
            runCatching { GsonBuilder().setPrettyPrinting().create().toJson(input) }.getOrDefault(input.toString())
        } else "(无参数)"
        val textArea = JBTextArea(pretty).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scroll = JBScrollPane(textArea).apply { preferredSize = Dimension(560, 240) }
        panel.add(scroll, BorderLayout.CENTER)
        panel.preferredSize = Dimension(620, 340)
        return panel
    }
}
