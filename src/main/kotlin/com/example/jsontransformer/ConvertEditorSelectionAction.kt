package com.example.jsontransformer

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor popup action: takes the current selection (or whole document) and
 * sends it to the JSON Transformer tool window, then converts it.
 */
class ConvertEditorSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val service = project.getService(JsonTransformerProjectService::class.java)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(JsonTransformerPanel.TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            val panel = service.panel ?: return@show
            val text = editor.selectionModel.selectedText ?: editor.document.text
            panel.setInput(text)
            panel.convert()
        }
    }
}
