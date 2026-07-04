package com.grimrg.complexargs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArgCombinerTest
{
    @Test
    fun combinesCheckedInSelectionOrder()
    {
        val options = listOf(
            ArgOption("Level_Arena", 1),
            ArgOption("-basedir=\"X\"", 3),
            ArgOption("-run=MyCommandlet -log", 0),
            ArgOption("-steam", 2),
        )
        assertEquals("Level_Arena -steam -basedir=\"X\"", ArgCombiner.combine(options))
    }

    @Test
    fun uncheckedAreExcludedAndEmptyIsBlank()
    {
        val options = listOf(ArgOption("-x", 0), ArgOption("-y", 0))
        assertEquals("", ArgCombiner.combine(options))
    }

    @Test
    fun blankCheckedOptionsDoNotAddStraySpaces()
    {
        val options = listOf(ArgOption("-a", 1), ArgOption("   ", 2), ArgOption("-b", 3))
        assertEquals("-a -b", ArgCombiner.combine(options))
    }

    @Test
    fun tiedSelectionOrdersKeepListOrder()
    {
        val a = ArgOption("-a", 2)
        val b = ArgOption("-b", 2)
        assertEquals("-a -b", ArgCombiner.combine(listOf(a, b)))
    }

    @Test
    fun displayRankIsContiguousDespiteGaps()
    {
        val a = ArgOption("a", 1)
        val b = ArgOption("b", 5)
        val c = ArgOption("c", 0)
        val d = ArgOption("d", 9)
        val options = listOf(a, b, c, d)
        assertEquals(1, ArgCombiner.displayRank(a, options))
        assertEquals(2, ArgCombiner.displayRank(b, options))
        assertNull(ArgCombiner.displayRank(c, options))
        assertEquals(3, ArgCombiner.displayRank(d, options))
    }

    @Test
    fun nextOrderIsOnePastMax()
    {
        val options = listOf(ArgOption("a", 1), ArgOption("b", 4), ArgOption("c", 0))
        assertEquals(5, ArgCombiner.nextOrder(options))
    }

    @Test
    fun combineGroupsSpansEveryGroup()
    {
        val g1 = ArgGroup("g1", true, mutableListOf(ArgOption("-a", 1)))
        val g2 = ArgGroup("g2", true, mutableListOf(ArgOption("-b", 2), ArgOption("-c", 0)))
        assertEquals("-a -b", ArgCombiner.combineGroups(listOf(g1, g2)))
    }
}
