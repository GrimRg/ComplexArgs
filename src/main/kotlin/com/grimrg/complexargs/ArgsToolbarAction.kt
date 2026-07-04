package com.grimrg.complexargs

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.awt.RelativePoint
import javax.swing.JComponent

/**
 * Widget docked in the New UI main toolbar. Renders [ArgsToolbarWidget]
 * (prompt icon + combined commandline + arrow), whole element clickable; clicking rolls out the
 * [ArgsPopupPanel] editor, left-aligned to the widget.
 *
 * Hidden until a project is open; text is refreshed from the persisted options through the update
 * cycle so it populates without opening the popup.
 *
 * Only renders when the New UI is enabled - the MainToolbar* action groups do not exist in the
 * Classic UI.
 */
class ArgsToolbarAction : AnAction(), CustomComponentAction, DumbAware
{
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent)
    {
    }

    override fun update(e: AnActionEvent)
    {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        if (project != null)
        {
            val combined = ArgStore.combinedFor(project)
            e.presentation.putClientProperty(TEXT_KEY, if (combined.isBlank()) "<no args>" else truncate(combined))
        }
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent
    {
        lateinit var widget: ArgsToolbarWidget
        widget = ArgsToolbarWidget { openPopup(widget) }
        return widget
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation)
    {
        (component as? ArgsToolbarWidget)?.setDisplayText(presentation.getClientProperty(TEXT_KEY) ?: "")
    }

    private fun openPopup(widget: ArgsToolbarWidget)
    {
        val dataContext = DataManager.getInstance().getDataContext(widget)
        val project = CommonDataKeys.PROJECT.getData(dataContext)
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return

        // Read + parse the presets off the EDT so opening never blocks the UI while the disk is busy.
        ApplicationManager.getApplication().executeOnPooledThread {
            val groups = ArgStore.load(project)
            ApplicationManager.getApplication().invokeLater {
                if (!widget.isShowing) return@invokeLater
                val panel = ArgsPopupPanel(project, groups) { combined ->
                    widget.setDisplayText(if (combined.isBlank()) "<no args>" else truncate(combined))
                }
                val popup = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(panel, panel.focusComponent())
                    .setResizable(true)
                    .setMovable(true)
                    .setRequestFocus(true)
                    .setCancelOnClickOutside(true)
                    .setCancelOnWindowDeactivation(false)
                    .createPopup()
                Disposer.register(popup, panel)
                popup.show(RelativePoint.getSouthWestOf(widget))
                panel.attachPopup(popup, widget)
            }
        }
    }

    private fun truncate(text: String, max: Int = 44): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private companion object
    {
        val TEXT_KEY = Key.create<String>("ComplexArgs.Text")
    }
}
