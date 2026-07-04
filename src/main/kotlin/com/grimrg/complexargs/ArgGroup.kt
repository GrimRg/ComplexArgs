package com.grimrg.complexargs

/**
 * A named, collapsible group of [ArgOption]s. Groups are organizational only - the combined
 * commandline still uses every checked option across all groups in checkbox-selection order.
 */
data class ArgGroup(
    var name: String = "New Group",
    var expanded: Boolean = true,
    val options: MutableList<ArgOption> = mutableListOf()
)
