package com.grimrg.complexargs

/**
 * Pure logic for ordering and combining commandline options. Free of any IntelliJ Platform
 * dependency so it can be unit-tested headless without downloading or booting an IDE.
 */
object ArgCombiner
{
    /** Checked options (selectionOrder > 0), ordered by when they were ticked. */
    fun enabledInOrder(options: List<ArgOption>): List<ArgOption> =
        options.filter { it.selectionOrder > 0 }.sortedBy { it.selectionOrder }

    /** The launch commandline: checked option texts, tick-order, joined by a single space. Blank
     *  entries are dropped so an empty checked row never injects a stray double space. */
    fun combine(options: List<ArgOption>): String =
        enabledInOrder(options).map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** 1-based rank shown in the checkbox, or null when the option is not checked. */
    fun displayRank(option: ArgOption, options: List<ArgOption>): Int?
    {
        if (option.selectionOrder <= 0)
        {
            return null
        }
        val index = enabledInOrder(options).indexOfFirst { it === option }
        return if (index >= 0) index + 1 else null
    }

    /** The next tick value to assign when an option is checked (one past the current maximum). */
    fun nextOrder(options: List<ArgOption>): Int =
        (options.maxOfOrNull { it.selectionOrder } ?: 0) + 1

    fun flatten(groups: List<ArgGroup>): List<ArgOption> = groups.flatMap { it.options }

    fun combineGroups(groups: List<ArgGroup>): String = combine(flatten(groups))

    fun rankInGroups(option: ArgOption, groups: List<ArgGroup>): Int? = displayRank(option, flatten(groups))

    fun nextOrderInGroups(groups: List<ArgGroup>): Int = nextOrder(flatten(groups))
}
