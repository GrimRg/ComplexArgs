package com.grimrg.complexargs

/**
 * A named, collapsible group of [ArgOption]s. A group can be temporarily deactivated ([inactive]),
 * which grays out its options and drops them from the combined commandline without changing their
 * checked state.
 */
data class ArgGroup(
    var name: String = "New Group",
    var expanded: Boolean = true,
    val options: MutableList<ArgOption> = mutableListOf(),
    var inactive: Boolean = false
)
