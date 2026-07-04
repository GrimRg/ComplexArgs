package com.grimrg.complexargs

import com.intellij.openapi.project.Project
import com.jetbrains.rider.cpp.run.configurations.CppConfigurationParametersExtension
import com.jetbrains.rider.run.configurations.exe.ExeConfigurationParameters

/**
 * Appends the combined preset commandline to a Rider C++ run configuration at launch, via the
 * official rider-cpp extension point (registered in plugin.xml as run.configurations.cpp).
 *
 * process() runs after Rider has resolved the launch parameters (macros like
 * $(LocalDebuggerCommandArguments) are already expanded), so appending here preserves the user's
 * base arguments and never touches the run configuration's fields, files, or requires a reload.
 */
class ComplexArgsCppExtension(private val project: Project) : CppConfigurationParametersExtension
{
    override fun process(parameters: ExeConfigurationParameters)
    {
        val combined = ArgStore.combinedFor(project).trim()
        if (combined.isEmpty())
        {
            return
        }
        val existing = parameters.programParameters.trim()
        if (existing == combined || existing.endsWith(" $combined"))
        {
            return
        }
        parameters.programParameters = if (existing.isEmpty()) combined else "$existing $combined"
    }
}
