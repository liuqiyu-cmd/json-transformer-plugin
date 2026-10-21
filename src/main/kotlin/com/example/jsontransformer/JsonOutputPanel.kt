package com.example.jsontransformer

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.ExpandVetoException
import javax.swing.tree.TreePath

/**
 * JSON output panel with two views, modeled after the IDE's JSON tree viewer:
 *
 *  - "代码视图"  – the classic plain-text rendering (identical to the saved/copied text).
 *  - "树视图"    – a lazy, paged JTree: containers show a count badge ("[ 189 ]"),
 *                  children load 50 at a time on expansion ("元素 50-99 …"),
 *                  array indices render as aligned blue ordinals and trailing
 *                  commas are dimmed.
 *
 * The tree is fully lazy: expanding a container loads only the first page of
 * children, so a 100k-element array opens instantly.
 */
class JsonOutputPanel(private val onDownload: () -> Unit) : JPanel(BorderLayout()) {

    // ------------------------------------------------------------- nodes

    /** User object of every tree node. */
    private class NodeData(
        val index: Int? = null,      // array element ordinal ("0:")
        val key: String? = null,     // object member key
        val value: JsonValue? = null,
        val isPage: Boolean = false, // "load more" pseudo node
        val pageFrom: Int = 0,       // first index of the NEXT page
        val pageTotal: Int = 0,      // total siblings in the container
        val comma: Boolean = false,  // dimmed trailing comma (matches code view)
        var loaded: Boolean = false  // container children already materialized
    ) {
        val isContainer: Boolean get() = value is JsonValue.Obj || value is JsonValue.Arr
    }

    private val PAGE_SIZE = 50
    private val MAX_EXPAND_NODES = 20000   // safety cap for 全展开

    // ------------------------------------------------------------- widgets

    val codeArea = JBTextArea().apply {
        lineWrap = false
        isEditable = false
        font = font.deriveFont(13f)
    }

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode(NodeData()))
    private val tree = JTree(treeModel).apply {
        setRootVisible(true)
        setShowsRootHandles(true)
        rowHeight = 22
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        // Single click toggles expansion (default is 2 = double click), so a
        // node opens on the first click like in the IDE JSON viewer.
        toggleClickCount = 1
        cellRenderer = Renderer()
        addTreeWillExpandListener(LazyLoader())
        addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) = onExpanded(event)
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) { }
        })
    }

    private val treeToggle = JToggleButton("树视图", true)
    private val codeToggle = JToggleButton("代码视图")
    private val expandAllButton = JButton("全展开")
    private val collapseAllButton = JButton("全折叠")
    private val downloadButton = JButton("⬇ 下载")

    private val cards = JPanel(CardLayout())
    private val treeScroll = JBScrollPane(tree)

    init {
        // Toolbar: view toggle + expand/collapse + download (mirrors the viewer bar).
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            val group = ButtonGroup()
            fun toggle(b: JToggleButton) { group.add(b); b.isFocusPainted = false }
            toggle(treeToggle); toggle(codeToggle)
            add(treeToggle); add(codeToggle)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(6, 18) })
            add(expandAllButton); add(collapseAllButton)
            add(downloadButton)
        }
        cards.add(treeScroll, VIEW_TREE)
        cards.add(JBScrollPane(codeArea), VIEW_CODE)
        add(bar, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)

        val layout = cards.layout as CardLayout
        fun show(view: String) {
            layout.show(cards, view)
            val isTree = view == VIEW_TREE
            expandAllButton.isEnabled = isTree
            collapseAllButton.isEnabled = isTree
        }
        treeToggle.addActionListener { if (treeToggle.isSelected) show(VIEW_TREE) }
        codeToggle.addActionListener { if (codeToggle.isSelected) show(VIEW_CODE) }
        expandAllButton.addActionListener { expandAll() }
        collapseAllButton.addActionListener { collapseAll() }
        downloadButton.addActionListener { onDownload() }
        show(VIEW_TREE)
    }

    // ------------------------------------------------------------- api

    /** Update the output: refreshes the code text and rebuilds the tree. */
    fun setCode(text: String) {
        codeArea.text = text
        rebuildTree(text)
    }

    /** Current code-view text (exactly what copy/save/export produces). */
    val codeText: String get() = codeArea.text

    // ------------------------------------------------------------- tree building

    /** Last text the tree was built from — identical output skips the rebuild
     *  so the user's expansion state survives option toggles / re-renders. */
    private var lastTreeText: String? = null

    private fun rebuildTree(text: String) {
        if (text == lastTreeText) return
        lastTreeText = text
        val rootValue = if (text.isBlank()) null else runCatching { Json.parse(text) }.getOrNull()
        val root = DefaultMutableTreeNode(NodeData(value = rootValue))
        treeModel.setRoot(root)
        treeModel.reload()
        SwingUtilities.invokeLater {
            tree.selectionModel.clearSelection()   // no stuck "selected root" look
            if (rootValue != null) tree.expandRow(0) else tree.collapseRow(0)
            tree.scrollRowToVisible(0)
        }
    }

    /** Materialize children [from, from+PAGE_SIZE) of a container node. */
    private fun loadPage(node: DefaultMutableTreeNode, from: Int) {
        val d = node.userObject as NodeData
        when (val v = d.value) {
            is JsonValue.Arr -> {
                val size = v.elements.size
                val end = minOf(from + PAGE_SIZE, size)
                for (i in from until end) {
                    node.add(DefaultMutableTreeNode(NodeData(index = i, value = v.elements[i], comma = i < size - 1)))
                }
                if (end < size) node.add(DefaultMutableTreeNode(NodeData(isPage = true, pageFrom = end, pageTotal = size)))
            }
            is JsonValue.Obj -> {
                val keys = v.members.keys.toList()
                val size = keys.size
                val end = minOf(from + PAGE_SIZE, size)
                for (i in from until end) {
                    val k = keys[i]
                    node.add(DefaultMutableTreeNode(NodeData(key = k, value = v.members[k], comma = i < size - 1)))
                }
                if (end < size) node.add(DefaultMutableTreeNode(NodeData(isPage = true, pageFrom = end, pageTotal = size)))
            }
            else -> return
        }
        d.loaded = true
        treeModel.nodeStructureChanged(node)
    }

    /** Page pseudo-nodes are vetoed here (they must not expand themselves);
     *  real containers load their children in [TreeExpansionListener.treeExpanded]
     *  — mutating the model inside treeWillExpand leaves JTree's internal
     *  expansion state inconsistent (the node looks collapsed, JTree thinks it
     *  is expanded, and clicks appear dead until a direct expandPath fixes it). */
    private inner class LazyLoader : TreeWillExpandListener {
        override fun treeWillExpand(event: TreeExpansionEvent) {
            val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
            val d = node.userObject as? NodeData ?: return
            if (d.isPage) {
                SwingUtilities.invokeLater { consumePage(node) }
                throw ExpandVetoException(event)
            }
        }

        override fun treeWillCollapse(event: TreeExpansionEvent) { /* always allowed */ }

        /** Replace the page pseudo-node with the next chunk, re-expanding the parent. */
        fun consumePage(node: DefaultMutableTreeNode) {
            val d = node.userObject as NodeData
            val parent = node.parent as? DefaultMutableTreeNode ?: return
            parent.remove(node)
            loadPage(parent, d.pageFrom)
            SwingUtilities.invokeLater { tree.expandPath(TreePath(parent.path)) }
        }
    }

    /** Materialize children AFTER the expansion completed — the safe point to
     *  mutate the model. Runs on the EDT synchronously, so no flicker. */
    private fun onExpanded(event: TreeExpansionEvent) {
        val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val d = node.userObject as? NodeData ?: return
        if (d.isContainer && !d.loaded) {
            loadPage(node, 0)
        }
    }

    // ------------------------------------------------------------- expand / collapse

    private fun expandAll() {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        val stack = ArrayDeque<DefaultMutableTreeNode>()
        stack.addLast(root)
        var budget = MAX_EXPAND_NODES
        while (stack.isNotEmpty() && budget > 0) {
            val n = stack.removeLast()
            val d = n.userObject as? NodeData ?: continue
            when {
                d.isPage -> {                       // drain every page so all children appear
                    val parent = n.parent as? DefaultMutableTreeNode ?: continue
                    parent.remove(n)
                    loadPage(parent, d.pageFrom)
                    stack.addLast(parent)
                }
                d.isContainer -> {
                    if (!d.loaded) loadPage(n, 0)
                    budget -= n.childCount
                    tree.expandPath(TreePath(n.path))
                    for (i in 0 until n.childCount) stack.addLast(n.getChildAt(i) as DefaultMutableTreeNode)
                }
            }
        }
        tree.scrollRowToVisible(0)
    }

    private fun collapseAll() {
        for (row in tree.rowCount - 1 downTo 0) tree.collapseRow(row)
        tree.scrollRowToVisible(0)
    }

    // ------------------------------------------------------------- rendering

    private inner class Renderer : DefaultTreeCellRenderer() {
        init {
            leafIcon = null; openIcon = null; closedIcon = null
        }
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, focus: Boolean
        ): java.awt.Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, focus)
            val d = (value as? DefaultMutableTreeNode)?.userObject as? NodeData
            // Selected rows render in the theme's selection foreground (uniform,
            // no syntax colors) — colored text on the blue selection background
            // is unreadable.
            text = when {
                d == null -> value.toString()
                sel -> plainHtml(d)
                else -> htmlFor(d)
            }
            return this
        }
    }

    /** Selection-safe rendering: same content, uniform (light) color. */
    private fun plainHtml(d: NodeData): String {
        if (d.isPage) {
            val remaining = d.pageTotal - d.pageFrom
            return "<html>… 还有 $remaining 项（元素 ${d.pageFrom}-${d.pageTotal - 1}），展开加载</html>"
        }
        if (d.value == null) return "<html>（无法解析为 JSON / Not valid JSON）</html>"
        val sb = StringBuilder("<html>")
        d.index?.let { sb.append("$it: ") }
        d.key?.let { sb.append("\"${esc(it)}\": ") }
        sb.append(plainValue(d.value))
        if (d.comma) sb.append(" ,")
        return sb.append("</html>").toString()
    }

    private fun plainValue(v: JsonValue): String = when (v) {
        is JsonValue.Obj  -> "{ ${v.members.size} }"
        is JsonValue.Arr  -> "[ ${v.elements.size} ]"
        is JsonValue.Str  -> "\"${esc(truncate(v.value))}\""
        is JsonValue.Num  -> v.raw
        is JsonValue.Bool -> v.value.toString()
        JsonValue.Null    -> "null"
    }

    private fun htmlFor(d: NodeData): String {
        if (d.isPage) {
            val remaining = d.pageTotal - d.pageFrom
            return "<html>${span(pageColor, "… 还有 $remaining 项（元素 ${d.pageFrom}-${d.pageTotal - 1}），展开加载", italic = true)}</html>"
        }
        if (d.value == null) {
            return "<html>${span(pageColor, "（无法解析为 JSON / Not valid JSON）", italic = true)}</html>"
        }
        val sb = StringBuilder("<html>")
        d.index?.let { sb.append(span(indexColor, "$it:")) .append(' ') }
        d.key?.let { sb.append(span(keyColor, "\"${esc(it)}\"")).append(span(braceColor, ":")) .append(' ') }
        sb.append(valueHtml(d.value))
        if (d.comma) sb.append(" ").append(span(commaColor, ","))
        return sb.append("</html>").toString()
    }

    private fun valueHtml(v: JsonValue): String = when (v) {
        is JsonValue.Obj  -> span(braceColor, "{ ") + span(countColor, v.members.size.toString()) + span(braceColor, " }")
        is JsonValue.Arr  -> span(braceColor, "[ ") + span(countColor, v.elements.size.toString()) + span(braceColor, " ]")
        is JsonValue.Str  -> span(strColor, "\"${esc(truncate(v.value))}\"")
        is JsonValue.Num  -> span(numColor, v.raw)
        is JsonValue.Bool -> span(boolColor, v.value.toString())
        JsonValue.Null    -> span(nullColor, "null", italic = true)
    }

    private fun span(color: Color, text: String, italic: Boolean = false): String {
        val style = if (italic) "font-style:italic;" else ""
        return "<span style='color:${hex(color)};$style'>${esc(text)}</span>"
    }

    private fun truncate(s: String) = if (s.length > 160) s.take(160) + "…" else s

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    // Theme-aware palette (light / dark), mirroring the IDE JSON viewer colors.
    private val indexColor = JBColor(Color(0x174AD4), Color(0x6F9EFF))
    private val keyColor   = JBColor(Color(0x9E6A00), Color(0xFFC66D))
    private val strColor   = JBColor(Color(0x067D17), Color(0x6AAB73))
    private val numColor   = JBColor(Color(0x1750EB), Color(0x2AACB8))
    private val boolColor  = JBColor(Color(0x0033B3), Color(0xCC7832))
    private val nullColor  = JBColor.GRAY
    private val braceColor = JBColor(Color(0x6E6E6E), Color(0xA9B7C6))
    private val countColor = JBColor(Color(0x1A7F37), Color(0x499C54))
    private val commaColor = JBColor(Color(0xB0B0B0), Color(0x606366))
    private val pageColor  = JBColor.GRAY

    companion object {
        private const val VIEW_TREE = "tree"
        private const val VIEW_CODE = "code"
    }
}

/** Convenience: same titled border style used across the tool window. */
@Suppress("FunctionName")
fun jsonOutputBorder(title: String): javax.swing.border.Border = TitledBorder(title)
