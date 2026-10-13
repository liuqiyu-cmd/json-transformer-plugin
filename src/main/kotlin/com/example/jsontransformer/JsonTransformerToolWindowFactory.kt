package com.example.jsontransformer

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the "JSON Transformer" tool window and wires its panel into the
 * project service so editor actions can reach it.
 */
class JsonTransformerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JsonTransformerPanel(project)
        project.getService(JsonTransformerProjectService::class.java).panel = panel
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
