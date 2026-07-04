package com.grimrg.complexargs

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.undo.UndoManager

/**
 * Editable value for an option. Always a single-line [ExtendableTextField] in the row, so the
 * surrounding grip / checkbox / delete never move. While focused it shows an expand icon inside the
 * field; clicking it opens a wrapping [JBTextArea] overlay drawn ON TOP of the popup (covering the
 * rows below rather than pushing them), with a collapse button in its top-right. The overlay grows
 * and shrinks to fit its content. Both views keep the option text in sync.
 */
class OptionTextCell(
    initial: String,
    private val onTextChanged: (String) -> Unit,
    private val onUp: () -> Unit,
    private val onDown: () -> Unit,
    private val onLeft: () -> Unit
) : JPanel()
{
    private val field = ExtendableTextField(initial)
    private val expandExtension =
        ExtendableTextComponent.Extension.create(AllIcons.General.ExpandComponent, "Expand editor (Shift+Enter)") { openOverlay() }
    private var overlay: JPanel? = null
    private var overlayArea: JBTextArea? = null
    private var overlayCollapse: JButton? = null
    private var syncing = false
    private var dimmed = false

    init
    {
        isOpaque = false
        alignmentY = 0.5f
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
        field.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))

        field.document.addDocumentListener(object : DocumentAdapter()
        {
            override fun textChanged(e: DocumentEvent)
            {
                if (syncing) return
                onTextChanged(field.text)
                overlayArea?.let { area ->
                    syncing = true
                    area.text = field.text
                    syncing = false
                    SwingUtilities.invokeLater { positionOverlay() }
                }
            }
        })
        field.addFocusListener(object : FocusAdapter()
        {
            override fun focusGained(e: FocusEvent)
            {
                field.setExtensions(expandExtension)
                field.foreground = UIUtil.getTextFieldForeground()
            }

            override fun focusLost(e: FocusEvent)
            {
                if (overlay == null)
                {
                    field.setExtensions()
                }
                applyFieldColor()
            }
        })
        field.addKeyListener(object : java.awt.event.KeyAdapter()
        {
            override fun keyPressed(e: KeyEvent)
            {
                when (e.keyCode)
                {
                    KeyEvent.VK_UP -> { e.consume(); onUp() }
                    KeyEvent.VK_DOWN -> { e.consume(); onDown() }
                    KeyEvent.VK_LEFT -> if (field.caretPosition == 0) { e.consume(); onLeft() }
                    KeyEvent.VK_ENTER -> if (e.isShiftDown) { e.consume(); openOverlay() }
                }
            }
        })

        add(field)
        applyFieldColor()
    }

    fun focusField()
    {
        field.requestFocusInWindow()
    }

    fun setDimmed(dim: Boolean)
    {
        dimmed = dim
        applyFieldColor()
    }

    private fun applyFieldColor()
    {
        field.foreground = if (dimmed) NamedColorUtil.getInactiveTextColor() else UIUtil.getTextFieldForeground()
    }

    private fun openOverlay()
    {
        if (overlay != null) return
        val rootPane = SwingUtilities.getRootPane(this) ?: return
        val layeredPane = rootPane.layeredPane

        val area = JBTextArea(field.text)
        area.lineWrap = true
        area.wrapStyleWord = true
        area.margin = JBUI.insets(3)
        area.document.addDocumentListener(object : DocumentAdapter()
        {
            override fun textChanged(e: DocumentEvent)
            {
                if (syncing) return
                syncing = true
                field.text = area.text
                syncing = false
                onTextChanged(area.text)
                SwingUtilities.invokeLater { positionOverlay() }
            }
        })
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commit")
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "commit")
        area.actionMap.put("commit", object : AbstractAction()
        {
            override fun actionPerformed(e: ActionEvent)
            {
                closeOverlay()
            }
        })

        val undoManager = UndoManager()
        area.document.addUndoableEditListener(undoManager)
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "undo")
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask), "redo")
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask or InputEvent.SHIFT_DOWN_MASK), "redo")
        area.actionMap.put("undo", object : AbstractAction()
        {
            override fun actionPerformed(e: ActionEvent)
            {
                if (undoManager.canUndo()) undoManager.undo()
            }
        })
        area.actionMap.put("redo", object : AbstractAction()
        {
            override fun actionPerformed(e: ActionEvent)
            {
                if (undoManager.canRedo()) undoManager.redo()
            }
        })
        area.addFocusListener(object : FocusAdapter()
        {
            override fun focusLost(e: FocusEvent)
            {
                SwingUtilities.invokeLater {
                    val panel = overlay ?: return@invokeLater
                    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    if (owner == null || !SwingUtilities.isDescendingFrom(owner, panel))
                    {
                        closeOverlay()
                    }
                }
            }
        })

        val collapse = miniButton(AllIcons.General.CollapseComponent, "Collapse (Shift+Enter)") { closeOverlay() }
        val panel = JPanel(null).apply {
            isOpaque = true
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(collapse)
            add(area)
            setComponentZOrder(collapse, 0)
        }

        layeredPane.setLayer(panel, JLayeredPane.POPUP_LAYER)
        layeredPane.add(panel)
        overlay = panel
        overlayArea = area
        overlayCollapse = collapse

        positionOverlay()
        area.requestFocusInWindow()
        area.caretPosition = area.document.length
    }

    private fun closeOverlay()
    {
        val panel = overlay ?: return
        (panel.parent as? JLayeredPane)?.let { lp -> lp.remove(panel); lp.repaint() }
        overlay = null
        overlayArea = null
        overlayCollapse = null
        field.requestFocusInWindow()
    }

    private fun positionOverlay()
    {
        val panel = overlay ?: return
        val area = overlayArea ?: return
        val collapse = overlayCollapse ?: return
        val lp = panel.parent as? JLayeredPane ?: return

        val origin = SwingUtilities.convertPoint(field, 0, 0, lp)
        val width = field.width.coerceAtLeast(JBUI.scale(160))

        val lineHeight = area.getFontMetrics(area.font).height.coerceAtLeast(1)
        val minContent = lineHeight * MIN_LINES + JBUI.scale(6)
        area.setSize(width - JBUI.scale(2), Short.MAX_VALUE.toInt())
        val contentHeight = maxOf(minContent, area.preferredSize.height)

        // Never leave the popup: cap height to what fits, and shift up if it would run past the bottom.
        val margin = JBUI.scale(4)
        val available = (lp.height - margin * 2).coerceAtLeast(lineHeight)
        val height = (contentHeight + JBUI.scale(2)).coerceAtMost(available)
        var y = origin.y
        if (y + height > lp.height - margin)
        {
            y = (lp.height - margin - height).coerceAtLeast(margin)
        }

        panel.bounds = Rectangle(origin.x, y, width, height)
        area.setBounds(1, 1, width - 2, height - 2)
        val size = JBUI.scale(18)
        collapse.setBounds(width - 1 - size - JBUI.scale(2), JBUI.scale(2), size, size)
        panel.setComponentZOrder(collapse, 0)
        panel.revalidate()
        panel.repaint()
    }

    private companion object
    {
        const val MIN_LINES = 3
    }

    private fun miniButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton
    {
        val button = JButton(icon)
        button.toolTipText = tooltip
        button.isFocusable = false
        button.isContentAreaFilled = false
        button.margin = JBUI.emptyInsets()
        button.border = JBUI.Borders.empty()
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val size = Dimension(JBUI.scale(18), JBUI.scale(18))
        button.preferredSize = size
        button.maximumSize = size
        button.minimumSize = size
        button.addActionListener { onClick() }
        return button
    }
}
