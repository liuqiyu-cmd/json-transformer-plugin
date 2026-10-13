package com.example.jsontransformer

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Lightweight project-level service that keeps a reference to the live
 * [JsonTransformerPanel] so editor actions can push text into it and drive
 * conversion without reaching into UI internals.
 */
@Service(Service.Level.PROJECT)
class JsonTransformerProjectService {
    var panel: JsonTransformerPanel? = null
}
