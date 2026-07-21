package com.grimrg.complexargs

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * Grayscale check control, tri-state (off / on / mixed). Focusable: Enter/Space toggles,
 * Up/Down/Right fire the nav callbacks. State lives in the model; this only paints it.
 */
class OrderIndicator(
    private val onToggle: () -> Unit,
    private val onUp: () -> Unit,
    private val onDown: () -> Unit,
    private val onRight: () -> Unit
) : JComponent()
{
    enum class State { OFF, ON, MIXED }

    private var state = State.OFF

    init
    {
        preferredSize = Dimension(JBUI.scale(30), JBUI.scale(26))
        minimumSize = Dimension(JBUI.scale(30), JBUI.scale(26))
        maximumSize = Dimension(JBUI.scale(30), JBUI.scale(26))
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter()
        {
            override fun mouseClicked(e: MouseEvent)
            {
                requestFocusInWindow()
                onToggle()
            }
        })
        addFocusListener(object : FocusAdapter()
        {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        addKeyListener(object : KeyAdapter()
        {
            override fun keyPressed(e: KeyEvent)
            {
                when (e.keyCode)
                {
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { e.consume(); onToggle() }
                    KeyEvent.VK_UP -> { e.consume(); onUp() }
                    KeyEvent.VK_DOWN -> { e.consume(); onDown() }
                    KeyEvent.VK_RIGHT -> { e.consume(); onRight() }
                }
            }
        })
    }

    fun update(on: Boolean) = update(if (on) State.ON else State.OFF)

    fun update(newState: State)
    {
        state = newState
        repaint()
    }

    override fun paintComponent(g: Graphics)
    {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val s = JBUI.scale(16)
        val x = (width - s) / 2
        val y = (height - s) / 2
        val r = JBUI.scale(4).toFloat()
        val box = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), s.toFloat(), s.toFloat(), r, r)
        val fg = UIUtil.getLabelForeground()

        if (state != State.OFF)
        {
            g2.color = ColorUtil.withAlpha(fg, 0.12)
            g2.fill(box)
        }
        g2.color = if (isFocusOwner) fg else JBColor.border()
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
        g2.draw(box)

        g2.color = fg
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (state)
        {
            State.ON ->
            {
                g2.drawLine(x + (s * 0.28f).toInt(), y + (s * 0.52f).toInt(), x + (s * 0.43f).toInt(), y + (s * 0.68f).toInt())
                g2.drawLine(x + (s * 0.43f).toInt(), y + (s * 0.68f).toInt(), x + (s * 0.72f).toInt(), y + (s * 0.32f).toInt())
            }
            State.MIXED -> g2.drawLine(x + (s * 0.28f).toInt(), y + s / 2, x + (s * 0.72f).toInt(), y + s / 2)
            State.OFF -> {}
        }
        g2.dispose()
    }
}
