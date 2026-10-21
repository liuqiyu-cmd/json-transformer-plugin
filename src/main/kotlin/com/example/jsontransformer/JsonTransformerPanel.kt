package com.example.jsontransformer

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.DefaultListCellRenderer

/**
 * The main UI for the plugin. Three tabs cover a complete table workflow
 * (数据源 → 表格编辑器 → 表格生成器):
 *
 *  1. "JSON 转换"   – paste a 数据源 (JSON / Excel-TSV / CSV / Markdown / HTML /
 *                     SQL / LaTeX / XML / MySQL / MediaWiki), pick the JSON
 *                     output layout and options; auto-updates live. The output
 *                     module offers a lazy tree view plus the classic code view.
 *  2. "表格编辑器"   – a spreadsheet-like grid of the parsed table with editing
 *                     operations (转置 / 去重 / 删除空行 / 大小写 / 查找替换 /
 *                     排序 / 撤销重做).
 *  3. "表格生成器"   – export the table as JSON / Excel(TSV) / SQL / LaTeX /
 *                     HTML / CSV / Markdown / XML / ASCII / MediaWiki.
 */
class JsonTransformerPanel(private val project: Project) : JPanel(BorderLayout()) {

    // -------------------------------------------------------------- state

    /** The working table. Edits in the grid mutate this without re-parsing input. */
    private var currentTable: Table? = null

    private val undoStack = ArrayDeque<Table>()
    private val redoStack = ArrayDeque<Table>()

    /** Debounces conversions while the user types in the input area. */
    private val debounce = Timer(250) { reparseAndRender() }.apply { isRepeats = false }

    // Tab indices + lazy-render dirty flags: only the visible tab is recomputed.
    private var jsonDirty = true
    private var generatorDirty = true

    // -------------------------------------------------------------- widgets

    private val inputArea = JBTextArea().apply {
        lineWrap = false
        font = JBTextArea().font.deriveFont(13f)
    }
    /** Output module: code view + lazy JSON tree view (树视图/代码视图/全展开/全折叠). */
    private val outputPanel = JsonOutputPanel(onDownload = { saveOutput() })

    /**
     * 数据源 entries. Each is either a JSON layout (handled by
     * [JsonTransformer]) or a non-JSON source (handled by [TableIO]).
     */
    private data class SourceEntry(val label: String, val source: SourceFormat, val jsonLayout: JsonTableFormat)

    private val sourceEntries = listOf(
        SourceEntry("自动识别 (Auto)", SourceFormat.AUTO, JsonTableFormat.AUTO),
        SourceEntry("JSON 数组 · 对象数组 (Array of Objects)", SourceFormat.JSON, JsonTableFormat.ARRAY_OF_OBJECTS),
        SourceEntry("JSON 数组 · 二维数组 (2D Array)", SourceFormat.JSON, JsonTableFormat.ARRAY_2D),
        SourceEntry("JSON 数组 · 列数组 (Column Array)", SourceFormat.JSON, JsonTableFormat.COLUMN_ARRAY),
        SourceEntry("JSON 数组 · 键值数组 (Keyed Array)", SourceFormat.JSON, JsonTableFormat.KEYED_ARRAY),
        SourceEntry("Excel (粘贴 TSV)", SourceFormat.EXCEL, JsonTableFormat.AUTO),
        SourceEntry("CSV", SourceFormat.CSV, JsonTableFormat.AUTO),
        SourceEntry("Markdown 表格", SourceFormat.MARKDOWN, JsonTableFormat.AUTO),
        SourceEntry("HTML 表格", SourceFormat.HTML, JsonTableFormat.AUTO),
        SourceEntry("插入 SQL", SourceFormat.SQL, JsonTableFormat.AUTO),
        SourceEntry("LaTeX 表格", SourceFormat.LATEX, JsonTableFormat.AUTO),
        SourceEntry("XML", SourceFormat.XML, JsonTableFormat.AUTO),
        SourceEntry("MySQL 查询结果", SourceFormat.MYSQL, JsonTableFormat.AUTO),
        SourceEntry("MediaWiki 表格", SourceFormat.MEDIAWIKI, JsonTableFormat.AUTO)
    )

    /** 表格生成器 output formats. */
    private val generatorFormats = listOf(
        GeneratorFormat.JSON to "JSON",
        GeneratorFormat.EXCEL to "Excel",
        GeneratorFormat.MAGIC to "Magic",
        GeneratorFormat.SQL to "SQL",
        GeneratorFormat.LATEX to "LaTeX",
        GeneratorFormat.HTML to "HTML",
        GeneratorFormat.CSV to "CSV",
        GeneratorFormat.MARKDOWN to "Markdown",
        GeneratorFormat.XML to "XML",
        GeneratorFormat.PDF to "PDF",
        GeneratorFormat.ASCII to "ASCII",
        GeneratorFormat.MEDIAWIKI to "MediaWiki"
    )
    private val outputFormats = listOf(
        JsonTableFormat.ARRAY_OF_OBJECTS to "Array of Object (对象数组)",
        JsonTableFormat.ARRAY_2D to "2D Array (二维数组)",
        JsonTableFormat.COLUMN_ARRAY to "Column Array (列数组)",
        JsonTableFormat.KEYED_ARRAY to "Keyed Array (键值数组)"
    )
    private val indentStyles = listOf(
        IndentStyle.TWO_SPACES to "2 spaces",
        IndentStyle.FOUR_SPACES to "4 spaces",
        IndentStyle.EIGHT_SPACES to "8 spaces",
        IndentStyle.TABS to "Tabs"
    )

    private val inputFormatCombo = JComboBox(sourceEntries.map { it.label }.toTypedArray())
    private val outputFormatCombo = JComboBox(outputFormats.map { it.second }.toTypedArray())
    private val indentCombo = JComboBox(indentStyles.map { it.second }.toTypedArray())
    private val generatorCombo = JComboBox(generatorFormats.map { it.second }.toTypedArray())

    /** Only enabled while the generator format is Excel (see [saveXlsx]). */
    private val exportXlsxButton = JButton("导出 Excel (.xlsx)").apply {
        toolTipText = "仅生成格式为 Excel 时可用：把当前表格写成真正的 .xlsx 文件"
    }
    private val generatorArea = JBTextArea().apply {
        lineWrap = false
        isEditable = false
        font = JBTextArea().font.deriveFont(13f)
    }
    private val minifyCheck = JCheckBox("压缩输出 (Minify)")
    private val smartParseCheck = JCheckBox("智能解析 (Parse JSON)")
    private val rootNameField = JTextField(10)

    private val undoButton = JButton("撤销")
    private val redoButton = JButton("重做")

    /** Footer statistics bar under the editor grid (COUNT/SUM/MIN/MAX/AVG). */
    private val statsLabel = JLabel(" ").apply {
        font = font.deriveFont(11f)
        border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    private val tableModel = EditableTableModel()
    private val grid = JTable(tableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setShowGrid(true)
        rowHeight = 20
        // proper cell (row+column) selection mode
        setCellSelectionEnabled(true)
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    }

    /** Tabbed container kept as a field so lazy rendering knows the visible tab. */
    private val tabs = JTabbedPane()

    /** Row-number header rendered to the left of the grid. */
    private val rowHeaderModel = DefaultListModel<String>()
    private val rowHeader = JList(rowHeaderModel).apply {
        fixedCellWidth = 44
        fixedCellHeight = grid.rowHeight
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, false, false)
                horizontalAlignment = SwingConstants.CENTER
                return this
            }
        }
    }

    // -------------------------------------------------------------- init

    init {
        inputFormatCombo.selectedIndex = 0
        outputFormatCombo.selectedIndex = 0
        indentCombo.selectedIndex = 0

        buildToolbar()
        buildTabs()
        wireListeners()
        updateXlsxExportEnabled()

        tableModel.onCellEdited = { updated ->
            pushUndo()
            currentTable = updated
            renderOnly()
        }
        tableModel.onTableChanged = {
            syncRowHeader()
            fitColumnWidths()
        }

        inputArea.text = EXAMPLE_INPUT
        reparseAndRender()
        updateUndoRedo()
    }

    // -------------------------------------------------------------- layout

    private fun buildToolbar() {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(label("数据源"))
            add(inputFormatCombo)
            add(label("数据格式"))
            add(outputFormatCombo)
            add(label("缩进大小"))
            add(indentCombo)
            add(minifyCheck)
            add(smartParseCheck)
            add(label("根对象名称/表名"))
            add(rootNameField)
            add(button("从编辑器载入") { loadFromEditor() })
            add(button("上传文件") { uploadSourceFile() })
            add(button("复制到剪贴板") { copyOutput() })
            add(button("下载文件") { saveOutput() })
        }
        add(bar, BorderLayout.NORTH)
    }

    private fun buildTabs() {
        tabs.apply {
            addTab("JSON 转换", buildJsonView())
            addTab("表格编辑器", buildEditorView())
            addTab("表格生成器", buildGeneratorView())
            addChangeListener { renderVisible() }
        }
        add(tabs, BorderLayout.CENTER)
    }

    private fun buildGeneratorView(): JComponent {
        val left = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val fmtPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(label("生成格式"))
            add(generatorCombo)
            add(exportXlsxButton)
        }
        val hint = JLabel(
            "<html>JSON = 对象数组输出；Excel = TSV（可粘贴进 Excel）；<br>" +
            "SQL 表名 / XML 根元素取「根对象名称/表名」；<br>" +
            "Magic 与 PDF 输出可打印的文本表格（ASCII 网格）。</html>"
        ).apply { font = font.deriveFont(11f) }
        left.add(fmtPanel)
        left.add(hint)
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            left,
            JBScrollPane(generatorArea).also { it.border = titledBorder("生成结果 (Generated)") }
        ).apply {
            dividerLocation = 200
            resizeWeight = 0.25
        }
        return split
    }

    private fun buildJsonView(): JComponent {
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(inputArea).also { it.border = titledBorder("输入 JSON (Input)") },
            outputPanel.also { it.border = titledBorder("输出 JSON (Output)") }
        ).apply {
            dividerLocation = 480
            resizeWeight = 0.5
        }
        return split
    }

    private fun buildEditorView(): JComponent {
        val ops = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply {
            add(undoButton); add(redoButton)
            add(button("重置表格") { reparseAndRender() })
            add(button("转置") { applyOperation { JsonTransformer.transpose(it) } })
            add(button("去重") { applyOperation { JsonTransformer.deduplicate(it) } })
            add(button("删除空行") { applyOperation { JsonTransformer.deleteBlankRows(it) } })
            add(button("删除选中行") { deleteSelectedRows() })
            add(button("删除选中列") { deleteSelectedColumns() })
            add(button("转大写") { applyOperation { JsonTransformer.transformCase(it, CaseMode.UPPER) } })
            add(button("转小写") { applyOperation { JsonTransformer.transformCase(it, CaseMode.LOWER) } })
            add(button("首字母大写") { applyOperation { JsonTransformer.transformCase(it, CaseMode.CAPITALIZE) } })
            add(button("查找替换") { findReplace() })
            add(button("按选中列排序 ↑") { sortBySelectedColumn(true) })
            add(button("按选中列排序 ↓") { sortBySelectedColumn(false) })
        }
        val export = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply {
            add(JLabel("导出（编辑结果，不影响输入 JSON）:"))
            add(JButton("导出 JSON").apply {
                toolTipText = "在窗口中预览编辑后的表格 JSON（沿用 JSON 转换页的格式设置），可复制或保存为 .json 文件"
                addActionListener { exportEditedJson() }
            })
            add(button("复制结果") { copyOutput() })
            add(button("导出文件") { saveOutput() })
        }
        val bars = JPanel(BorderLayout()).apply {
            add(ops, BorderLayout.NORTH)
            add(export, BorderLayout.SOUTH)
        }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(bars, BorderLayout.NORTH)
        val scroll = JBScrollPane(grid)
        scroll.rowHeader = javax.swing.JViewport().apply { view = rowHeader }   // row numbers
        wrapper.add(scroll, BorderLayout.CENTER)
        wrapper.add(statsLabel, BorderLayout.SOUTH)
        grid.componentPopupMenu = buildGridPopupMenu()
        undoButton.addActionListener { undo() }
        redoButton.addActionListener { redo() }
        return wrapper
    }

    /** Right-click menu on the grid: clipboard + row/column operations + sort. */
    private fun buildGridPopupMenu(): JPopupMenu {
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit) = menu.add(JMenuItem(text).apply { addActionListener { action() } })
        item("复制 (Copy)") { copyGridSelection() }
        item("剪切 (Cut)") { cutGridSelection() }
        item("粘贴 (Paste)") { pasteGridClipboard() }
        menu.addSeparator()
        item("删除选中行 (Delete Row(s))") { deleteSelectedRows() }
        item("在上方插入行 (Insert Row(s) up)") { insertRowAtAnchor(false) }
        item("在下方插入行 (Insert Row(s) down)") { insertRowAtAnchor(true) }
        menu.addSeparator()
        item("删除选中列 (Delete Column(s))") { deleteSelectedColumns() }
        item("在左侧插入列 (Insert Column(s) left)") { insertColumnAtAnchor(false) }
        item("在右侧插入列 (Insert Column(s) right)") { insertColumnAtAnchor(true) }
        menu.addSeparator()
        item("升序排序 (Sort A to Z)") { sortBySelectedColumn(true) }
        item("降序排序 (Sort Z to A)") { sortBySelectedColumn(false) }
        return menu
    }

    // -------------------------------------------------------------- listeners

    private fun wireListeners() {
        // Any output option change re-renders from the current table.
        outputFormatCombo.addActionListener { renderOnly() }
        indentCombo.addActionListener { renderOnly() }
        minifyCheck.addActionListener { renderOnly() }
        generatorCombo.addActionListener {
            renderOnly()
            updateXlsxExportEnabled()
        }
        exportXlsxButton.addActionListener { saveXlsx() }

        // Input-affecting options re-parse then render.
        inputFormatCombo.addActionListener { reparseAndRender() }
        smartParseCheck.addActionListener { reparseAndRender() }

        // Root object name only affects rendering.
        rootNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = renderOnly()
            override fun removeUpdate(e: DocumentEvent?) = renderOnly()
            override fun changedUpdate(e: DocumentEvent?) = renderOnly()
        })

        // Live conversion while typing the input (debounced).
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = debounce.restart()
            override fun removeUpdate(e: DocumentEvent?) = debounce.restart()
            override fun changedUpdate(e: DocumentEvent?) = debounce.restart()
        })

        // Footer statistics follow the grid selection.
        val statsUpdater = javax.swing.event.ListSelectionListener { updateStats() }
        grid.selectionModel.addListSelectionListener(statsUpdater)
        grid.columnModel.selectionModel.addListSelectionListener(statsUpdater)
    }

    /** The .xlsx export button is only meaningful for the Excel generator format. */
    private fun updateXlsxExportEnabled() {
        val isExcel = generatorFormats.getOrNull(generatorCombo.selectedIndex)?.first == GeneratorFormat.EXCEL
        exportXlsxButton.isEnabled = isExcel
    }

    /** Recompute the footer statistics from the selected column (or whole table). */
    private fun updateStats() {
        val t = currentTable
        if (t == null || t.columns.isEmpty()) {
            statsLabel.text = " "
            return
        }
        val viewCol = grid.selectedColumn
        val modelCol = if (viewCol >= 0) grid.convertColumnIndexToModel(viewCol) else -1
        val stats = if (modelCol in t.columns.indices) {
            JsonTransformer.columnStats(t, modelCol)
        } else {
            // No column selected: aggregate over all columns.
            val all = (0 until t.columns.size).mapNotNull { JsonTransformer.columnStats(t, it) }
            val sums = all.mapNotNull { it.sum }
            JsonTransformer.ColumnStats(
                count = all.sumOf { it.count },
                sum = sums.takeIf { it.size == all.count { s -> s.sum != null } }?.sumOf { it },
                min = all.mapNotNull { it.min }.minOrNull(),
                max = all.mapNotNull { it.max }.maxOrNull(),
                avg = null
            )
        }
        statsLabel.text = cursorText() + (stats?.render() ?: "COUNT: 0")
    }

    /** "R2 C3 |" style cursor position prefix for the status bar. */
    private fun cursorText(): String {
        val pos = buildList {
            val r = grid.selectedRow
            if (r >= 0) add("R${r + 1}")
            val c = selectedModelColumn()
            if (c >= 0) add("C${c + 1}")
        }
        return if (pos.isEmpty()) "" else pos.joinToString(" ") + "  |  "
    }

    /** Rebuild the row-number header to match the current table height. */
    private fun syncRowHeader() {
        rowHeaderModel.clear()
        repeat(currentTable?.rowCount ?: 0) { i -> rowHeaderModel.addElement((i + 1).toString()) }
    }

    /** Give each grid column a sensible preferred width from its header name. */
    private fun fitColumnWidths() {
        val cm = grid.columnModel
        for (i in 0 until cm.columnCount) {
            val name = tableModel.getColumnName(i)
            cm.getColumn(i).preferredWidth = ((name.length + 8) * 9).coerceIn(70, 240)
        }
    }

    // -------------------------------------------------------------- actions

    fun setInput(text: String) {
        inputArea.text = text
    }

    /** Public entry point used by editor actions. */
    fun convert() = reparseAndRender()

    /** Parse the current input into [currentTable], then render. */
    private fun reparseAndRender() {
        val input = inputArea.text
        if (input.isBlank()) {
            currentTable = null
            tableModel.table = null
            outputPanel.setCode("")
            undoStack.clear(); redoStack.clear()
            updateUndoRedo()
            return
        }
        try {
            val entry = sourceEntries[inputFormatCombo.selectedIndex]
            val table = if (entry.source == SourceFormat.JSON) {
                JsonTransformer.parse(input, entry.jsonLayout, smartParseCheck.isSelected)
            } else {
                TableIO.parseSource(input, entry.source, smartParseCheck.isSelected)
            }
            currentTable = table
            tableModel.table = table
            undoStack.clear(); redoStack.clear()
            updateUndoRedo()
            renderOnly()
        } catch (e: Exception) {
            currentTable = null
            tableModel.table = null
            generatorArea.text = ""
            outputPanel.setCode("转换出错 / Error:\n${e.message ?: e.javaClass.simpleName}")
            undoStack.clear(); redoStack.clear()
            updateUndoRedo()
        }
    }

    /** Mark both outputs stale, then re-render only the currently visible tab. */
    private fun renderOnly() {
        jsonDirty = true
        generatorDirty = true
        renderVisible()
    }

    /**
     * Lazy rendering: the JSON output and the generator output are recomputed
     * only when their tab is visible. Switching tabs renders the newly visible
     * one on demand — big tables no longer re-render twice per keystroke.
     */
    private fun renderVisible() {
        when (tabs.selectedIndex) {
            2 -> if (generatorDirty) { renderGeneratorOutput(); generatorDirty = false }
            else -> if (jsonDirty) { renderJsonOutput(); jsonDirty = false }
        }
        if (tabs.selectedIndex == 1) updateStats()
    }

    private fun renderJsonOutput() {
        val t = currentTable ?: run { outputPanel.setCode(""); return }
        try {
            outputPanel.setCode(
                JsonTransformer.render(
                    t,
                    outputFormats[outputFormatCombo.selectedIndex].first,
                    indentStyles[indentCombo.selectedIndex].first,
                    rootNameField.text.takeIf { it.isNotBlank() },
                    minifyCheck.isSelected
                )
            )
        } catch (e: Exception) {
            outputPanel.setCode("转换出错 / Error:\n${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun renderGeneratorOutput() {
        val t = currentTable ?: run { generatorArea.text = ""; return }
        try {
            generatorArea.text = TableIO.generate(
                t,
                generatorFormats[generatorCombo.selectedIndex].first,
                indentStyles[indentCombo.selectedIndex].first,
                rootNameField.text.takeIf { it.isNotBlank() },
                minifyCheck.isSelected
            )
        } catch (e: Exception) {
            generatorArea.text = "生成出错 / Error:\n${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun applyOperation(op: (Table) -> Table) {
        val t = currentTable ?: run {
            showNotification("没有可编辑的表格 / No table to edit")
            return
        }
        pushUndo()
        val result = op(t)
        currentTable = result
        tableModel.table = result
        renderOnly()
        updateUndoRedo()
    }

    private fun pushUndo() {
        currentTable?.let {
            undoStack.addLast(it)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        currentTable?.let { redoStack.addLast(it) }
        currentTable = prev
        tableModel.table = prev
        renderOnly()
        updateUndoRedo()
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        currentTable?.let { undoStack.addLast(it) }
        currentTable = next
        tableModel.table = next
        renderOnly()
        updateUndoRedo()
    }

    private fun updateUndoRedo() {
        undoButton.isEnabled = undoStack.isNotEmpty()
        redoButton.isEnabled = redoStack.isNotEmpty()
    }

    private fun findReplace() {
        if (currentTable == null) {
            showNotification("没有可编辑的表格 / No table to edit")
            return
        }
        val findField = JTextField(20)
        val replField = JTextField(20)
        val regexCheck = JCheckBox("使用正则表达式 (Regex)")
        val panel = JPanel(GridLayout(0, 1, 4, 4)).apply {
            add(JLabel("查找内容 (Find):")); add(findField)
            add(JLabel("替换为 (Replace):")); add(replField)
            add(regexCheck)
        }
        val choice = JOptionPane.showConfirmDialog(
            this, panel, "查找替换 (Find & Replace)",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (choice != JOptionPane.OK_OPTION || findField.text.isEmpty()) return
        val find = findField.text
        val repl = replField.text
        val regex = regexCheck.isSelected
        applyOperation { JsonTransformer.replaceText(it, find, repl, regex) }
    }

    private fun sortBySelectedColumn(ascending: Boolean) {
        val t = currentTable ?: run {
            showNotification("没有可编辑的表格 / No table to edit")
            return
        }
        if (t.columns.isEmpty()) return
        val viewCol = grid.selectedColumn
        val modelCol = if (viewCol < 0) 0 else grid.convertColumnIndexToModel(viewCol)
        applyOperation { JsonTransformer.sortRows(it, modelCol, ascending) }
    }

    private fun deleteSelectedRows() {
        val t = currentTable ?: run {
            showNotification("没有可编辑的表格 / No table to edit")
            return
        }
        val viewRows = grid.selectedRows
        if (viewRows.isEmpty()) {
            showNotification("请先在表格中选中要删除的行 / Select rows to delete first")
            return
        }
        val modelRows = viewRows.map { grid.convertRowIndexToModel(it) }
        applyOperation { JsonTransformer.deleteRows(it, modelRows) }
    }

    private fun deleteSelectedColumns() {
        val t = currentTable ?: run {
            showNotification("没有可编辑的表格 / No table to edit")
            return
        }
        if (t.columns.isEmpty()) return
        val viewCols = grid.selectedColumns
        if (viewCols.isEmpty()) {
            showNotification("请先在表格中选中要删除的列 / Select columns to delete first")
            return
        }
        val modelCols = viewCols.map { grid.convertColumnIndexToModel(it) }
        applyOperation { JsonTransformer.deleteColumns(it, modelCols) }
    }

    // ------------------------------------------- grid context-menu operations

    private fun selectedModelColumn(): Int {
        val viewCol = grid.selectedColumn
        return if (viewCol >= 0) grid.convertColumnIndexToModel(viewCol) else -1
    }

    private fun insertRowAtAnchor(below: Boolean) {
        val viewRow = grid.selectedRow
        val modelRow = if (viewRow >= 0) grid.convertRowIndexToModel(viewRow) else (currentTable?.rowCount ?: 0)
        val at = if (below) modelRow + 1 else modelRow
        applyOperation { JsonTransformer.insertRows(it, at, 1) }
    }

    private fun insertColumnAtAnchor(right: Boolean) {
        val modelCol = selectedModelColumn()
        val at = if (modelCol < 0) (currentTable?.columns?.size ?: 0) else if (right) modelCol + 1 else modelCol
        applyOperation { JsonTransformer.insertColumns(it, at, 1) }
    }

    /** Copy the selected cells (or whole rows/columns) as TSV. */
    private fun copyGridSelection() {
        val t = currentTable ?: return
        val viewRows = grid.selectedRows
        val viewCols = grid.selectedColumns
        if (viewRows.isEmpty() || viewCols.isEmpty()) return
        val sb = StringBuilder()
        viewRows.forEachIndexed { ri, vr ->
            if (ri > 0) sb.append('\n')
            viewCols.forEachIndexed { ci, vc ->
                if (ci > 0) sb.append('\t')
                val r = grid.convertRowIndexToModel(vr)
                val c = grid.convertColumnIndexToModel(vc)
                sb.append(JsonTransformer.displayCell(t.columns.getOrNull(c)?.cells?.getOrNull(r) ?: JsonValue.Null))
            }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(sb.toString()))
        showNotification("已复制选中单元格 / Copied selection")
    }

    private fun cutGridSelection() {
        copyGridSelection()
        val viewRows = grid.selectedRows
        val viewCols = grid.selectedColumns
        if (viewRows.isEmpty() || viewCols.isEmpty() || currentTable == null) return
        val modelRows = viewRows.map { grid.convertRowIndexToModel(it) }
        val modelCols = viewCols.map { grid.convertColumnIndexToModel(it) }
        applyOperation { table ->
            var t = table
            for (r in modelRows) for (c in modelCols) {
                t = JsonTransformer.setCell(t, r, c, JsonValue.Null)
            }
            t
        }
    }

    /** Paste tabular text from the clipboard starting at the anchor cell. */
    private fun pasteGridClipboard() {
        val t = currentTable ?: run {
            showNotification("没有可编辑的表格 / No table to edit")
            return
        }
        val text = try {
            val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
            } else null
        } catch (_: Exception) { null } ?: run {
            showNotification("剪贴板没有文本 / No text on clipboard")
            return
        }
        val anchorRow = if (grid.selectedRow >= 0) grid.convertRowIndexToModel(grid.selectedRow) else 0
        val anchorCol = selectedModelColumn().coerceAtLeast(0)
        val lines = text.split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val parsed = lines.map { line -> TableIO.parseTsvLine(line) }
        val needRows = (anchorRow + parsed.size).coerceAtLeast(t.rowCount)
        val needCols = (anchorCol + (parsed.maxOfOrNull { it.size } ?: 0)).coerceAtLeast(t.columns.size)
        applyOperation { table0 ->
            var table = table0
            // grow rows / columns as needed
            if (needRows > table.rowCount) table = JsonTransformer.insertRows(table, table.rowCount, needRows - table.rowCount)
            if (needCols > table.columns.size) table = JsonTransformer.insertColumns(table, table.columns.size, needCols - table.columns.size)
            parsed.forEachIndexed { dr, cells ->
                cells.forEachIndexed { dc, value ->
                    val r = anchorRow + dr
                    val c = anchorCol + dc
                    table = JsonTransformer.setCell(table, r, c, JsonTransformer.parseCell(value))
                }
            }
            table
        }
    }

    // ---------------------------------------------------------- file upload

    /** Open a data-source file (.xlsx / .csv / .tsv / JSON / …) into the tool. */
    private fun uploadSourceFile() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            /* chooseFiles = */ true, /* chooseFolders = */ false,
            /* chooseJars = */ false, /* chooseJarsAsFiles = */ false,
            /* chooseJarContents = */ false, /* chooseMultiple = */ false
        ).apply {
            title = "选择数据源文件 (Choose data source file)"
            description = "支持 .xlsx / .csv / .tsv / .json / .md / .html / .xml / .sql"
            withFileFilter { f ->
                f.extension?.lowercase() in setOf(
                    "xlsx", "csv", "tsv", "txt", "json", "md", "markdown",
                    "html", "htm", "xml", "sql", "tex", "wiki"
                )
            }
        }
        val files = com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null)
        val file = files.firstOrNull() ?: return
        try {
            if (file.extension?.lowercase() == "xlsx") {
                val table = file.inputStream.use { XlsxReader.read(it, smartParseCheck.isSelected) }
                // Put the same data into the input area as TSV (the Excel source
                // format), so the 数据源 panel stays the single source of truth.
                inputFormatCombo.selectedIndex = sourceEntries.indexOfFirst { it.source == SourceFormat.EXCEL }.coerceAtLeast(0)
                inputArea.text = TableIO.generate(table, GeneratorFormat.EXCEL, IndentStyle.TWO_SPACES, null, false)
                convert()
                showNotification("已载入 Excel / Loaded: ${file.name}")
            } else {
                val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                inputFormatCombo.selectedIndex = 0   // 自动识别
                setInput(text)
                convert()
                showNotification("已载入 / Loaded: ${file.name}")
            }
        } catch (e: Exception) {
            showNotification("载入失败 / Load failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * The output text + suggested file extension of the *active* tab:
     * the generator tab exports the generator result, the other tabs the
     * JSON conversion result (which reflects editor edits too).
     */
    private fun activeOutput(): Pair<String, String>? {
        return if (tabs.selectedIndex == 2) {
            val fmt = generatorFormats.getOrNull(generatorCombo.selectedIndex)?.first ?: return null
            renderGeneratorOutput()
            generatorDirty = false
            val text = generatorArea.text
            if (text.isEmpty() || text.startsWith("生成出错")) return null
            text to when (fmt) {
                GeneratorFormat.JSON -> "json"
                GeneratorFormat.EXCEL -> "tsv"
                GeneratorFormat.SQL -> "sql"
                GeneratorFormat.LATEX -> "tex"
                GeneratorFormat.HTML -> "html"
                GeneratorFormat.CSV -> "csv"
                GeneratorFormat.MARKDOWN -> "md"
                GeneratorFormat.XML -> "xml"
                GeneratorFormat.MEDIAWIKI -> "wiki"
                else -> "txt"          // MAGIC / PDF / ASCII text grids
            }
        } else {
            val text = outputPanel.codeText
            if (text.isEmpty() || text.startsWith("转换出错")) null else text to "json"
        }
    }

    /** Export the edited table as JSON in a dialog window: preview the JSON,
     *  then copy it to the clipboard or save it to a file (表格编辑器). */
    fun exportEditedJson() {
        val t = currentTable ?: run {
            showNotification("没有可导出的表格 / No table to export")
            return
        }
        val json = try {
            JsonTransformer.render(
                t,
                outputFormats[outputFormatCombo.selectedIndex].first,
                indentStyles[indentCombo.selectedIndex].first,
                rootNameField.text.takeIf { it.isNotBlank() },
                minifyCheck.isSelected
            )
        } catch (e: Exception) {
            showNotification("导出失败 / Export failed: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        showJsonExportDialog(json)
    }

    /** Modal window showing the exported JSON with copy / save actions. */
    private fun showJsonExportDialog(json: String) {
        val area = JBTextArea(json).apply {
            lineWrap = false
            isEditable = true        // allow last-minute tweaks before copy/save
            font = font.deriveFont(13f)
            setCaretPosition(0)
        }
        val scroll = JBScrollPane(area).apply {
            preferredSize = java.awt.Dimension(720, 480)
            border = titledBorder("编辑后的表格 JSON (Exported JSON)")
        }
        val copyButton = JButton("复制到剪贴板").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(area.text))
                text = "已复制 ✓"
                Timer(1500) { text = "复制到剪贴板" }.apply { isRepeats = false }.start()
            }
        }
        val saveButton = JButton("保存为文件...").apply {
            addActionListener { saveTextAsFile(area.text, "导出 JSON") }
        }
        val closeButton = JButton("关闭")

        // A plain modal JDialog (JOptionPane option buttons don't auto-close).
        val dialog = JDialog(JOptionPane.getFrameForComponent(this), "导出 JSON (Export JSON)", true).apply {
            defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
            layout = BorderLayout()
            add(scroll, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
                add(copyButton); add(saveButton); add(closeButton)
            }, BorderLayout.SOUTH)
            // ESC closes the window too
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "closeDialog")
            rootPane.actionMap.put("closeDialog", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) = dispose()
            })
        }
        closeButton.addActionListener { dialog.dispose() }
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    /** Save [text] as a file chosen via the IDE save dialog. */
    private fun saveTextAsFile(text: String, title: String) {
        val descriptor = FileSaverDescriptor(title, "选择保存位置", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(project.basePath) ?: return
        val file: File = wrapper.file
        runCatching { file.writeText(text) }
            .onSuccess { showNotification("已保存到 / Saved: ${file.absolutePath}") }
            .onFailure { e -> showNotification("导出失败 / Export failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    fun copyOutput() {
        val (text, _) = activeOutput() ?: run {
            showNotification("当前没有可复制的结果 / Nothing to copy")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        showNotification("已复制到剪贴板 / Copied to clipboard")
    }

    fun saveOutput() {
        val (text, _) = activeOutput() ?: run {
            showNotification("当前没有可保存的结果 / Nothing to save")
            return
        }
        val descriptor = FileSaverDescriptor(
            "保存转换结果", "选择保存转换结果的位置",
            "json", "csv", "tsv", "sql", "xml", "html", "md", "tex", "txt", "wiki"
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(project.basePath) ?: return
        val file: File = wrapper.file
        file.writeText(text)
        showNotification("已保存到 / Saved: ${file.absolutePath}")
    }

    /** Export the working table as a real .xlsx file (zero-dependency writer). */
    fun saveXlsx() {
        val t = currentTable ?: run {
            showNotification("没有可导出的表格 / No table to export")
            return
        }
        val descriptor = FileSaverDescriptor("导出 Excel", "保存为 .xlsx 文件", "xlsx")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(project.basePath) ?: return
        val file: File = wrapper.file
        runCatching {
            file.writeBytes(XlsxWriter.write(t, rootNameField.text.takeIf { it.isNotBlank() }))
        }.onSuccess {
            showNotification("已导出 Excel / Exported: ${file.absolutePath}")
        }.onFailure { e ->
            showNotification("导出失败 / Export failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun loadFromEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            showNotification("没有打开的编辑器 / No open editor")
            return
        }
        val text = editor.selectionModel.selectedText ?: editor.document.text
        if (text.isBlank()) {
            showNotification("编辑器内容为空 / Editor is empty")
            return
        }
        setInput(text)
        convert()
    }

    /** Show a proper IDE balloon notification (falls back to stdout when the
     *  platform is unavailable, e.g. in headless/test contexts). */
    private fun showNotification(message: String) {
        val ok = runCatching {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("JSON Transformer")
                .createNotification(message, com.intellij.notification.NotificationType.INFORMATION)
                .notify(project)
        }.isSuccess
        if (!ok) {
            Toolkit.getDefaultToolkit().beep()
            println("[JSON Transformer] $message")
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "JSON Transformer"
        const val EXAMPLE_INPUT = """[
  { "name": "Laptop", "price": 999, "stock": 15 },
  { "name": "Mouse", "price": 29, "stock": 50 },
  { "name": "Keyboard", "price": 79, "stock": 25 }
]"""
    }
}

/** Table model backed by the converter's [Table]; edits write straight back. */
private class EditableTableModel : AbstractTableModel() {

    private var backing: Table? = null

    /** Callback fired after a cell edit with the updated table. */
    var onCellEdited: ((Table) -> Unit)? = null

    /** Callback fired whenever the whole table structure is replaced. */
    var onTableChanged: (() -> Unit)? = null

    var table: Table?
        get() = backing
        set(value) {
            backing = value
            fireTableStructureChanged()
            onTableChanged?.invoke()
        }

    override fun getRowCount(): Int = backing?.rowCount ?: 0
    override fun getColumnCount(): Int = backing?.columns?.size ?: 0
    override fun getColumnName(column: Int): String = backing?.columns?.getOrNull(column)?.name ?: ""
    override fun isCellEditable(row: Int, column: Int): Boolean = true

    override fun getValueAt(row: Int, column: Int): Any {
        val cell = backing?.columns?.getOrNull(column)?.cells?.getOrNull(row) ?: JsonValue.Null
        return JsonTransformer.displayCell(cell)
    }

    override fun setValueAt(aValue: Any?, row: Int, column: Int) {
        val t = backing ?: return
        val updated = JsonTransformer.setCell(t, row, column, JsonTransformer.parseCell(aValue?.toString() ?: ""))
        backing = updated
        fireTableRowsUpdated(row, row)
        onCellEdited?.invoke(updated)
    }
}

private fun label(text: String): JLabel = JLabel(text)

private fun button(text: String, action: () -> Unit): JButton =
    JButton(text).apply { addActionListener { action() } }

private fun titledBorder(title: String): javax.swing.border.Border = TitledBorder(title)
