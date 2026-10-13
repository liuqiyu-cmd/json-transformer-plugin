package com.example.jsontransformer

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/** Opens the JSON Transformer tool window from the Tools menu. */
class OpenJsonTransformerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(JsonTransformerPanel.TOOL_WINDOW_ID) ?: return
        toolWindow.show()
    }
}
