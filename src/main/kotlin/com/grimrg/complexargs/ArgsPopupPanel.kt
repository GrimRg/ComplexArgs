package com.grimrg.complexargs

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import java.util.IdentityHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The rolled-out editor, presented tree-like: collapsible groups, each holding option rows
 * (grip + checkbox showing selection rank + editable text + delete). Tool panel offers create-group,
 * deselect-all, reset-size, and doubles as the popup's move handle. A word-wrapping preview sits at
 * the bottom.
 *
 * Drag reorders via the grips: an option grip moves an option within or across groups; a group-header
 * grip reorders whole groups (carrying their options). A crisp ghost follows the cursor and a
 * drop-line marks the target; the move commits on release (no rebuild mid-drag, so the drag grab
 * survives).
 */
class ArgsPopupPanel(
    private val project: Project,
    private val groups: MutableList<ArgGroup>,
    private val onCombinedChanged: (String) -> Unit
) : JPanel(BorderLayout()), Disposable
{
    private enum class DragKind { NONE, OPTION, GROUP }

    private class RowMeta(val comp: JComponent, val isHeader: Boolean, val group: ArgGroup, val option: ArgOption?)

    private var saveFuture: ScheduledFuture<*>? = null
    private val rowsPanel = object : JPanel()
    {
        var dropLineY: Int? = null
        var dropLineX: Int = 0

        override fun paintComponent(g: Graphics)
        {
            super.paintComponent(g)
            val y = dropLineY ?: return
            val g2 = g.create() as Graphics2D
            g2.color = DROP_LINE_COLOR
            g2.fillRect(dropLineX, y - JBUI.scale(1), width - dropLineX, JBUI.scale(2))
            g2.dispose()
        }
    }
    private val scroll = JBScrollPane(rowsPanel)

    // ArgOption is a data class, so two options with identical text are structurally equal. These maps
    // (and the `it === option` checks elsewhere) track by object identity so distinct rows holding the
    // same text are never collapsed together.
    private val indicators = IdentityHashMap<ArgOption, OrderIndicator>()
    private val fields = IdentityHashMap<ArgOption, OptionTextCell>()
    private val groupIndicators = IdentityHashMap<ArgGroup, OrderIndicator>()
    private val dirSelector = HoverIconLabel(AllIcons.Nodes.Folder) { chooseWorkingDir() }
    private lateinit var newGroupButton: JButton
    private val rowMetas = mutableListOf<RowMeta>()
    private val metaByComp = mutableMapOf<JComponent, RowMeta>()
    private val preview = JTextArea(2, 40)

    private var popup: JBPopup? = null
    private var anchor: JComponent? = null
    private var suppressStore = true

    private var draggingKind = DragKind.NONE
    private var draggingGroup: ArgGroup? = null
    private var draggingOption: ArgOption? = null
    private var draggingRow: JComponent? = null
    private var grabOffsetY = 0
    private var draggingRowHeight = 0
    private var dragGlass: DragGlass? = null
    private var dragLayeredPane: JLayeredPane? = null

    init
    {
        rowsPanel.layout = BoxLayout(rowsPanel, BoxLayout.Y_AXIS)

        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scroll.border = JBUI.Borders.empty()
        scroll.preferredSize = autoFitScrollSize()

        add(buildToolBar(), BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(buildPreview(), BorderLayout.SOUTH)

        border = JBUI.Borders.empty(6)
        rebuildRows()
        refresh()
    }

    fun focusComponent(): JComponent = rowsPanel

    fun attachPopup(popup: JBPopup, anchor: JComponent)
    {
        this.popup = popup
        this.anchor = anchor
        addComponentListener(object : ComponentAdapter()
        {
            override fun componentResized(e: ComponentEvent)
            {
                if (!suppressStore)
                {
                    storeManualSize(size)
                }
            }
        })
        applySizing()
        manualLocation()?.let { popup.setLocation(it) }
        SwingUtilities.invokeLater { suppressStore = false }
    }

    private fun buildToolBar(): JComponent
    {
        val createGroup = flatIconButton(AllIcons.Actions.NewFolder, "Create group") {
            groups.add(ArgGroup("New Group", true, mutableListOf()))
            rebuildRows()
            refresh()
            refitIfAuto()
        }
        newGroupButton = createGroup
        val deselect = flatIconButton(AllIcons.Actions.Unselectall, "Deselect all options") {
            groups.forEach { group -> group.options.forEach { it.selectionOrder = 0 } }
            refresh()
        }
        val reset = flatIconButton(AllIcons.Actions.MoveToWindow, "Reset popup size and position") { resetSize() }

        val actions = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(createGroup)
            add(deselect)
            add(reset)
        }

        dirSelector.toolTipText = "Working directory (where presets are read/written)"
        updatePathLabel()

        val bar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(0, 2, 6, 2)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            add(actions, BorderLayout.WEST)
            add(dirSelector, BorderLayout.EAST)
        }
        makeMovable(bar)
        return bar
    }

    private fun chooseWorkingDir()
    {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "Select ComplexArgs Working Directory"
        val current = LocalFileSystem.getInstance().findFileByIoFile(ArgStore.workingDir(project))
        val chosen = FileChooser.chooseFile(descriptor, project, current) ?: return
        ArgStore.setWorkingDir(project, chosen.path)
        reloadFromStore()
        updatePathLabel()
    }

    private fun reloadFromStore()
    {
        groups.clear()
        groups.addAll(ArgStore.load(project))
        rebuildRows()
        refresh()
        refitIfAuto()
    }

    private fun updatePathLabel()
    {
        dirSelector.setText(truncatePath(ArgStore.workingDir(project).path))
    }

    private fun truncatePath(path: String, max: Int = 56): String =
        if (path.length <= max) path else "…" + path.takeLast(max - 1)

    private fun makeMovable(handle: JComponent)
    {
        val adapter = object : MouseAdapter()
        {
            private var startScreen: Point? = null
            private var popupStart: Point? = null

            override fun mousePressed(e: MouseEvent)
            {
                val p = popup ?: return
                startScreen = e.locationOnScreen
                popupStart = p.locationOnScreen
            }

            override fun mouseDragged(e: MouseEvent)
            {
                val p = popup ?: return
                val start = startScreen ?: return
                val origin = popupStart ?: return
                val now = e.locationOnScreen
                p.setLocation(Point(origin.x + (now.x - start.x), origin.y + (now.y - start.y)))
            }

            override fun mouseReleased(e: MouseEvent)
            {
                popup?.let { storeManualLocation(it.locationOnScreen) }
            }
        }
        handle.addMouseListener(adapter)
        handle.addMouseMotionListener(adapter)
    }

    private fun buildPreview(): JComponent
    {
        preview.isEditable = false
        preview.lineWrap = true
        preview.wrapStyleWord = true
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(6, 2, 0, 2)
            )
            add(JBLabel("Preview of commandline"), BorderLayout.NORTH)
            add(preview, BorderLayout.CENTER)
        }
    }

    private fun rebuildRows()
    {
        rowsPanel.removeAll()
        indicators.clear()
        fields.clear()
        groupIndicators.clear()
        rowMetas.clear()
        metaByComp.clear()

        if (groups.isEmpty())
        {
            rowsPanel.add(hintRow("No groups yet - use the New Group button", 4))
        }
        else
        {
            for (group in groups)
            {
                val header = buildGroupHeader(group)
                rowsPanel.add(header)
                rowMetas.add(RowMeta(header, true, group, null))

                if (group.expanded)
                {
                    if (group.options.isEmpty())
                    {
                        val hint = hintRow("No options - use + to add", OPTION_INDENT_PX)
                        rowsPanel.add(hint)
                        rowMetas.add(RowMeta(hint, false, group, null))
                    }
                    else
                    {
                        for (option in group.options)
                        {
                            val row = buildOptionRow(group, option)
                            rowsPanel.add(row)
                            rowMetas.add(RowMeta(row, false, group, option))
                        }
                    }
                }
            }
        }
        for (meta in rowMetas)
        {
            metaByComp[meta.comp] = meta
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    private fun hintRow(text: String, leftPad: Int): JComponent
    {
        val label = JBLabel(text)
        label.foreground = UIUtil.getInactiveTextColor()
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, leftPad, 6, 4)
            minimumSize = Dimension(0, ROW_HEIGHT)
            preferredSize = Dimension(JBUI.scale(DEFAULT_WIDTH), ROW_HEIGHT)
            maximumSize = Dimension(Int.MAX_VALUE, ROW_HEIGHT)
            add(label, BorderLayout.WEST)
        }
    }

    private fun buildGroupHeader(group: ArgGroup): JComponent
    {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.border = JBUI.Borders.empty(2, 0)
        row.minimumSize = Dimension(0, ROW_HEIGHT)
        row.preferredSize = Dimension(JBUI.scale(DEFAULT_WIDTH), ROW_HEIGHT)
        row.maximumSize = Dimension(Int.MAX_VALUE, ROW_HEIGHT)

        val triangle = JBLabel(if (group.expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight)
        triangle.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        triangle.border = JBUI.Borders.empty(0, 2)
        triangle.addMouseListener(object : MouseAdapter()
        {
            override fun mouseClicked(e: MouseEvent)
            {
                group.expanded = !group.expanded
                rebuildRows()
                refresh()
                refitIfAuto()
            }
        })

        val grip = JBLabel(AllIcons.General.Drag)
        grip.toolTipText = "Drag to reorder group"
        grip.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        grip.border = JBUI.Borders.empty(0, 2)
        wireDrag(grip, row, DragKind.GROUP, group, null)

        val name = JBTextField(group.name)
        name.font = name.font.deriveFont(Font.BOLD)
        name.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
        name.document.addDocumentListener(object : DocumentAdapter()
        {
            override fun textChanged(e: DocumentEvent)
            {
                group.name = name.text
                scheduleSave()
            }
        })

        val groupCheck = OrderIndicator(
            onToggle = { toggleGroup(group) },
            onUp = {},
            onDown = {},
            onRight = { name.requestFocusInWindow() }
        )
        groupCheck.toolTipText = "Enable or disable all options in this group"
        groupCheck.alignmentY = 0.5f
        groupIndicators[group] = groupCheck

        val addOption = flatIconButton(AllIcons.General.Add, "Add option to group") {
            group.options.add(ArgOption("", 0))
            rebuildRows()
            refresh()
            refitIfAuto()
        }
        setHighlight(addOption, groups.size == 1 && groups.sumOf { it.options.size } == 0)
        val deleteGroup = flatIconButton(AllIcons.Actions.Close, "Delete group") {
            groups.remove(group)
            rebuildRows()
            refresh()
            refitIfAuto()
        }

        row.add(triangle)
        row.add(grip)
        row.add(groupCheck)
        row.add(name)
        row.add(addOption)
        row.add(deleteGroup)
        return row
    }

    private fun buildOptionRow(group: ArgGroup, option: ArgOption): JComponent
    {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.border = JBUI.Borders.empty(2, 0)
        row.minimumSize = Dimension(0, ROW_HEIGHT)
        row.preferredSize = Dimension(JBUI.scale(DEFAULT_WIDTH), ROW_HEIGHT)
        row.maximumSize = Dimension(Int.MAX_VALUE, ROW_HEIGHT)

        val grip = JBLabel(AllIcons.General.Drag)
        grip.toolTipText = "Drag to reorder or move to another group"
        grip.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        grip.border = JBUI.Borders.empty(0, 2)
        grip.alignmentY = 0.5f
        wireDrag(grip, row, DragKind.OPTION, group, option)

        val indicator = OrderIndicator(
            onToggle = {
                option.selectionOrder = if (option.selectionOrder > 0) 0 else ArgCombiner.nextOrderInGroups(groups)
                refresh()
            },
            onUp = { focusCheckbox(prevOption(option)) },
            onDown = { focusCheckbox(nextOption(option)) },
            onRight = { fields[option]?.focusField() }
        )
        indicator.alignmentY = 0.5f
        indicators[option] = indicator

        val text = OptionTextCell(
            option.text,
            onTextChanged = { option.text = it; refreshPreviewAndSave() },
            onUp = { focusFieldOf(prevOption(option)) },
            onDown = { focusFieldOf(nextOption(option)) },
            onLeft = { indicators[option]?.requestFocusInWindow() }
        )
        fields[option] = text
        applyFieldStyle(option, text)

        val delete = flatIconButton(AllIcons.Actions.Close, "Delete this option") {
            removeByIdentity(group.options, option)
            rebuildRows()
            refresh()
            refitIfAuto()
        }
        delete.alignmentY = 0.5f

        row.add(Box.createHorizontalStrut(JBUI.scale(OPTION_INDENT_PX)))
        row.add(grip)
        row.add(indicator)
        row.add(text)
        row.add(delete)
        return row
    }

    // ---- drag & drop --------------------------------------------------------------------------

    private fun wireDrag(grip: JComponent, row: JComponent, kind: DragKind, group: ArgGroup, option: ArgOption?)
    {
        grip.addMouseListener(object : MouseAdapter()
        {
            override fun mouseReleased(e: MouseEvent)
            {
                if (draggingKind != DragKind.NONE)
                {
                    endDrag(e)
                }
            }
        })
        grip.addMouseMotionListener(object : MouseAdapter()
        {
            override fun mouseDragged(e: MouseEvent)
            {
                if (draggingKind == DragKind.NONE)
                {
                    startDrag(kind, group, option, row, grip, e)
                }
                if (draggingKind != DragKind.NONE)
                {
                    onDrag(e)
                }
            }
        })
    }

    private fun startDrag(kind: DragKind, group: ArgGroup, option: ArgOption?, row: JComponent, grip: JComponent, e: MouseEvent)
    {
        if (row.width <= 0 || row.height <= 0)
        {
            return
        }
        val rootPane = SwingUtilities.getRootPane(this) ?: return
        val layeredPane = rootPane.layeredPane

        draggingKind = kind
        draggingGroup = group
        draggingOption = option
        draggingRow = row
        grabOffsetY = SwingUtilities.convertPoint(e.component, e.point, row).y
        draggingRowHeight = row.height

        val image = UIUtil.createImage(row, row.width, row.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.graphics as Graphics2D
        row.paint(g)
        g.dispose()

        val glass = DragGlass()
        glass.image = image
        glass.bounds = Rectangle(0, 0, layeredPane.width, layeredPane.height)
        layeredPane.setLayer(glass, JLayeredPane.DRAG_LAYER)
        layeredPane.add(glass)
        dragGlass = glass
        dragLayeredPane = layeredPane

        // Collapse the source to an empty slot; keep the grip alive (blank its icon) for the grab.
        row.components.forEach { component ->
            if (component === grip)
            {
                (component as? JBLabel)?.icon = null
            }
            else
            {
                component.isVisible = false
            }
        }
        row.minimumSize = Dimension(row.width, ROW_HEIGHT)
        row.preferredSize = row.minimumSize

        updateGhost(e)
    }

    private fun onDrag(e: MouseEvent)
    {
        updateGhost(e)
        val y = SwingUtilities.convertPoint(e.component, e.point, rowsPanel).y
        if (draggingKind == DragKind.OPTION)
        {
            // Live reflow: move the (collapsed) source row among the rows so neighbours slide.
            rowsPanel.dropLineY = null
            liveMoveDraggedRow(y)
        }
        else
        {
            rowsPanel.dropLineX = 0
            rowsPanel.dropLineY = computeGroupDrop(y).second
            rowsPanel.repaint()
        }
    }

    private fun liveMoveDraggedRow(y: Int)
    {
        val row = draggingRow ?: return
        val comps = rowsPanel.components
        var target = comps.size - 1
        for (i in comps.indices)
        {
            val c = comps[i]
            if (y < c.y + c.height / 2)
            {
                target = i
                break
            }
        }
        if (target < 0)
        {
            target = 0
        }
        if (rowsPanel.getComponentZOrder(row) != target)
        {
            rowsPanel.setComponentZOrder(row, target)
            rowsPanel.revalidate()
            rowsPanel.repaint()
        }
    }

    private fun endDrag(e: MouseEvent)
    {
        val y = SwingUtilities.convertPoint(e.component, e.point, rowsPanel).y
        val kind = draggingKind
        val option = draggingOption
        val group = draggingGroup
        val row = draggingRow

        removeGhost()
        rowsPanel.dropLineY = null
        draggingKind = DragKind.NONE
        draggingOption = null
        draggingGroup = null
        draggingRow = null

        if (kind == DragKind.OPTION && option != null && group != null && row != null)
        {
            moveOptionByPosition(option, group, row)
        }
        else if (kind == DragKind.GROUP && group != null)
        {
            val target = computeGroupDrop(y).first
            val from = groups.indexOf(group)
            if (from >= 0)
            {
                groups.removeAt(from)
                var index = target
                if (from < index)
                {
                    index--
                }
                groups.add(index.coerceIn(0, groups.size), group)
            }
        }
        rebuildRows()
        refresh()
        refitIfAuto()
    }

    /** Maps the dragged row's live component position to a (group, index) and applies the move. */
    private fun moveOptionByPosition(option: ArgOption, fromGroup: ArgGroup, row: JComponent)
    {
        var currentGroup: ArgGroup? = null
        var count = 0
        var targetGroup: ArgGroup? = null
        var targetIndex = 0
        for (comp in rowsPanel.components)
        {
            if (comp === row)
            {
                targetGroup = currentGroup
                targetIndex = count
                break
            }
            val meta = metaByComp[comp] ?: continue
            if (meta.isHeader)
            {
                currentGroup = meta.group
                count = 0
            }
            else if (meta.option != null)
            {
                count++
            }
        }
        val destination = targetGroup ?: fromGroup
        removeByIdentity(fromGroup.options, option)
        destination.options.add(targetIndex.coerceIn(0, destination.options.size), option)
    }

    private fun removeByIdentity(list: MutableList<ArgOption>, item: ArgOption)
    {
        val i = list.indexOfFirst { it === item }
        if (i >= 0)
        {
            list.removeAt(i)
        }
    }

    private fun removeGhost()
    {
        dragGlass?.let { g -> dragLayeredPane?.let { lp -> lp.remove(g); lp.repaint() } }
        dragGlass = null
        dragLayeredPane = null
    }

    private fun updateGhost(e: MouseEvent)
    {
        val glass = dragGlass ?: return
        val lp = dragLayeredPane ?: return
        val origin = SwingUtilities.convertPoint(rowsPanel, Point(0, 0), lp)
        val cursor = SwingUtilities.convertPoint(e.component, e.point, lp)
        val viewOrigin = SwingUtilities.convertPoint(scroll, Point(0, 0), lp)
        val minY = viewOrigin.y
        val maxY = (viewOrigin.y + scroll.height - draggingRowHeight).coerceAtLeast(minY)
        val y = (cursor.y - grabOffsetY).coerceIn(minY, maxY)
        glass.place(origin.x, y)
    }

    /** Returns (insertionIndex, dropLineY) for the current group drag. */
    private fun computeGroupDrop(y: Int): Pair<Int, Int>
    {
        val headers = rowMetas.filter { it.isHeader }
        var index = headers.size
        for (i in headers.indices)
        {
            if (y < headers[i].comp.y + headers[i].comp.height / 2)
            {
                index = i
                break
            }
        }
        val lineY = if (index < headers.size)
        {
            headers[index].comp.y
        }
        else
        {
            rowMetas.lastOrNull()?.comp?.let { it.y + it.height } ?: 0
        }
        return index to lineY
    }

    // ---- refresh / styling --------------------------------------------------------------------

    private fun refresh()
    {
        setHighlight(newGroupButton, groups.isEmpty())
        for ((option, indicator) in indicators)
        {
            indicator.update(option.selectionOrder > 0)
        }
        for ((group, indicator) in groupIndicators)
        {
            indicator.update(groupState(group))
        }
        for ((option, field) in fields)
        {
            applyFieldStyle(option, field)
        }
        refreshPreviewAndSave()
    }

    private fun groupState(group: ArgGroup): OrderIndicator.State
    {
        val options = group.options
        if (options.isEmpty())
        {
            return OrderIndicator.State.OFF
        }
        val selected = options.count { it.selectionOrder > 0 }
        return when (selected)
        {
            0 -> OrderIndicator.State.OFF
            options.size -> OrderIndicator.State.ON
            else -> OrderIndicator.State.MIXED
        }
    }

    private fun toggleGroup(group: ArgGroup)
    {
        val allSelected = group.options.isNotEmpty() && group.options.all { it.selectionOrder > 0 }
        if (allSelected)
        {
            group.options.forEach { it.selectionOrder = 0 }
        }
        else
        {
            var next = ArgCombiner.nextOrderInGroups(groups)
            for (option in group.options)
            {
                if (option.selectionOrder <= 0)
                {
                    option.selectionOrder = next++
                }
            }
        }
        refresh()
    }

    private fun applyFieldStyle(option: ArgOption, field: OptionTextCell)
    {
        field.setDimmed(option.selectionOrder <= 0)
    }

    private fun orderedOptions(): List<ArgOption> = rowMetas.mapNotNull { it.option }

    private fun prevOption(option: ArgOption): ArgOption?
    {
        val list = orderedOptions()
        val i = list.indexOfFirst { it === option }
        return if (i > 0) list[i - 1] else null
    }

    private fun nextOption(option: ArgOption): ArgOption?
    {
        val list = orderedOptions()
        val i = list.indexOfFirst { it === option }
        return if (i in 0 until list.size - 1) list[i + 1] else null
    }

    private fun focusCheckbox(option: ArgOption?)
    {
        option?.let { indicators[it]?.requestFocusInWindow() }
    }

    private fun focusFieldOf(option: ArgOption?)
    {
        option?.let { fields[it]?.focusField() }
    }

    private fun refreshPreviewAndSave()
    {
        val combined = ArgCombiner.combineGroups(groups)
        preview.text = combined.ifBlank { "<no args>" }
        onCombinedChanged(combined)
        scheduleSave()
    }

    /** Debounced, off-EDT save with a snapshot, so typing never blocks the UI thread on disk I/O. */
    private fun scheduleSave()
    {
        saveFuture?.cancel(false)
        val snapshot = groups.map { g -> ArgGroup(g.name, g.expanded, g.options.map { it.copy() }.toMutableList()) }
        saveFuture = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule({ ArgStore.save(project, snapshot) }, 250, TimeUnit.MILLISECONDS)
    }

    /** Invoked when the popup closes (registered via Disposer). Cancels the pending debounced save and
     *  flushes the final edit synchronously so nothing typed in the last 250ms is lost. */
    override fun dispose()
    {
        val pending = saveFuture
        saveFuture = null
        if (pending != null && !pending.isDone)
        {
            pending.cancel(false)
            ArgStore.save(project, groups)
        }
    }

    private fun setHighlight(button: JButton, on: Boolean)
    {
        button.border = if (on)
        {
            JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.RED, 1), JBUI.Borders.empty(0, 3))
        }
        else
        {
            JBUI.Borders.empty(0, 4)
        }
    }

    private fun flatIconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton
    {
        val button = object : JButton(icon)
        {
            override fun paintComponent(g: Graphics)
            {
                if (model.isRollover || model.isPressed)
                {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    val arc = JBUI.scale(6)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
        button.toolTipText = tooltip
        button.isFocusable = false
        button.isContentAreaFilled = false
        button.isRolloverEnabled = true
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.margin = JBUI.emptyInsets()
        button.border = JBUI.Borders.empty(0, 4)
        val size = Dimension(JBUI.scale(26), JBUI.scale(26))
        button.preferredSize = size
        button.maximumSize = size
        button.minimumSize = size
        button.addActionListener { onClick() }
        return button
    }

    // ---- sizing / persistence -----------------------------------------------------------------

    private fun visibleRowCount(): Int
    {
        var count = 0
        for (group in groups)
        {
            count += 1
            if (group.expanded)
            {
                count += if (group.options.isEmpty()) 1 else group.options.size
            }
        }
        return count.coerceIn(1, VISIBLE_ROWS)
    }

    private fun autoFitScrollSize(): Dimension =
        Dimension(JBUI.scale(DEFAULT_WIDTH), visibleRowCount() * ROW_HEIGHT)

    private fun applySizing()
    {
        suppressStore = true
        val manual = manualSize()
        if (manual != null)
        {
            preferredSize = manual
        }
        else
        {
            preferredSize = null
            scroll.preferredSize = autoFitScrollSize()
        }
        revalidate()
        popup?.pack(true, true)
        SwingUtilities.invokeLater { suppressStore = false }
    }

    private fun refitIfAuto()
    {
        if (manualSize() == null)
        {
            applySizing()
        }
    }

    private fun resetSize()
    {
        val p = popup ?: return
        storeManualSize(null)
        storeManualLocation(null)
        suppressStore = true
        preferredSize = null
        scroll.preferredSize = autoFitScrollSize()
        revalidate()
        p.pack(true, true)
        p.size = Dimension(JBUI.scale(DEFAULT_WIDTH), p.size.height)
        anchor?.let { p.setLocation(RelativePoint.getSouthWestOf(it).screenPoint) }
        SwingUtilities.invokeLater { suppressStore = false }
    }

    private fun manualSize(): Dimension?
    {
        val pc = PropertiesComponent.getInstance()
        val w = pc.getInt(PROP_WIDTH, 0)
        val h = pc.getInt(PROP_HEIGHT, 0)
        return if (w > 0 && h > 0) Dimension(w, h) else null
    }

    private fun storeManualSize(size: Dimension?)
    {
        val pc = PropertiesComponent.getInstance()
        if (size == null)
        {
            pc.unsetValue(PROP_WIDTH)
            pc.unsetValue(PROP_HEIGHT)
        }
        else
        {
            pc.setValue(PROP_WIDTH, size.width, 0)
            pc.setValue(PROP_HEIGHT, size.height, 0)
        }
    }

    private fun frameOrigin(): Point?
    {
        val a = anchor ?: return null
        val window = SwingUtilities.getWindowAncestor(a) ?: return null
        return try { window.locationOnScreen } catch (e: Exception) { null }
    }

    /** Stored as an offset from the Rider window origin so the popup follows Rider across monitors. */
    private fun manualLocation(): Point?
    {
        val pc = PropertiesComponent.getInstance()
        val dx = pc.getInt(PROP_X, Int.MIN_VALUE)
        val dy = pc.getInt(PROP_Y, Int.MIN_VALUE)
        if (dx == Int.MIN_VALUE || dy == Int.MIN_VALUE)
        {
            return null
        }
        val origin = frameOrigin() ?: return null
        return Point(origin.x + dx, origin.y + dy)
    }

    private fun storeManualLocation(screenLocation: Point?)
    {
        val pc = PropertiesComponent.getInstance()
        if (screenLocation == null)
        {
            pc.unsetValue(PROP_X)
            pc.unsetValue(PROP_Y)
            return
        }
        val origin = frameOrigin()
        val dx = if (origin != null) screenLocation.x - origin.x else screenLocation.x
        val dy = if (origin != null) screenLocation.y - origin.y else screenLocation.y
        pc.setValue(PROP_X, dx, Int.MIN_VALUE)
        pc.setValue(PROP_Y, dy, Int.MIN_VALUE)
    }

    private companion object
    {
        const val VISIBLE_ROWS = 6
        const val DEFAULT_WIDTH = 560
        const val OPTION_INDENT_PX = 48
        val ROW_HEIGHT = JBUI.scale(34)
        const val PROP_WIDTH = "ComplexArgs.popup.width"
        const val PROP_HEIGHT = "ComplexArgs.popup.height"
        const val PROP_X = "ComplexArgs.popup.x"
        const val PROP_Y = "ComplexArgs.popup.y"
        val DROP_LINE_COLOR = JBColor.namedColor("Label.foreground", JBColor.foreground())
    }
}

/** Glass-pane overlay that paints a translucent snapshot of the row being dragged. */
private class DragGlass : JComponent()
{
    var image: BufferedImage? = null
    private var ghostX = 0
    private var ghostY = 0

    init
    {
        isOpaque = false
    }

    // Paint-only overlay: never intercept mouse events, so the drag grab keeps flowing to the grip.
    override fun contains(x: Int, y: Int): Boolean = false

    fun place(x: Int, y: Int)
    {
        ghostX = x
        ghostY = y
        repaint()
    }

    override fun paintComponent(g: Graphics)
    {
        val img = image ?: return
        val g2 = g.create() as Graphics2D
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f)
        UIUtil.drawImage(g2, img, ghostX, ghostY, null)
        g2.dispose()
    }
}
