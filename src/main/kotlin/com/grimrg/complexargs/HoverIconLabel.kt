package com.grimrg.complexargs

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A clickable [icon + text] element that paints a rounded hover background and is clickable across
 * its whole area (icon and text share the same hover + click), matching the ComplexArgs toolbar
 * widget. Grayscale hover only - no accent color.
 */
class HoverIconLabel(icon: Icon, private val onClick: () -> Unit) : JPanel()
{
    private var hovered = false
    private val label = JBLabel()

    init
    {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(2, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val iconLabel = JBLabel(icon)
        add(iconLabel)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(label)

        val mouse = object : MouseAdapter()
        {
            override fun mouseEntered(e: MouseEvent)
            {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent)
            {
                val p: Point = SwingUtilities.convertPoint(e.component, e.point, this@HoverIconLabel)
                if (!contains(p))
                {
                    hovered = false
                    repaint()
                }
            }

            override fun mouseClicked(e: MouseEvent)
            {
                onClick()
            }
        }
        listOf<Component>(this, iconLabel, label).forEach { it.addMouseListener(mouse) }
    }

    fun setText(text: String)
    {
        label.text = text
    }

    override fun paintComponent(g: Graphics)
    {
        if (hovered)
        {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
