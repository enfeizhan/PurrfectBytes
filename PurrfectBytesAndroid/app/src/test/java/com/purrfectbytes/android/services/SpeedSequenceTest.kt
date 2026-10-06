package com.purrfectbytes.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedSequenceTest {

    private val normal = false
    private val slow = true

    private fun steps(vararg steps: Pair<Int, Boolean>) = steps.map { SequenceStep(it.first, it.second) }

    // What the web app (sequence_utils.py, parse_sequence) makes of the same input
    @Test
    fun `the short form is read the way the web app reads it`() {
        assertEquals(steps(2 to normal, 3 to slow, 2 to normal), SpeedSequence.parse("2n,3s,2n"))
        assertEquals(steps(5 to slow), SpeedSequence.parse("5s"))
        assertEquals(steps(2 to normal, 3 to slow), SpeedSequence.parse(" 2N , 3S "))
        assertEquals(steps(2 to normal), SpeedSequence.parse("2n,"))
        assertEquals(steps(100 to normal), SpeedSequence.parse("100n"))
    }

    @Test
    fun `what is not a sequence is not read`() {
        val refused = listOf(
            null, "", "  ,  ",
            "2x", "abc", "n2", "2", "n", "2ns", "-1n", "2.5n", "٣n",
            "0n", "101n", "60n,60s",
            List(21) { "1n" }.joinToString(",")
        )

        refused.forEach { assertNull("\"$it\" was read", SpeedSequence.parse(it)) }
    }

    @Test
    fun `a sequence is written the way it is read`() {
        val sequence = steps(3 to normal, 4 to slow, 3 to normal)

        assertEquals("3n,4s,3n", SpeedSequence.format(sequence))
        assertEquals(sequence, SpeedSequence.parse(SpeedSequence.format(sequence)))
    }

    @Test
    fun `a sequence is described in words`() {
        assertEquals("2 normal, 3 slow", SpeedSequence.describe(steps(2 to normal, 3 to slow)))
        assertEquals(7, SpeedSequence.total(steps(2 to normal, 3 to slow, 2 to normal)))
    }

    @Test
    fun `a new sequence is three normal, four slow, three normal`() {
        assertEquals("3n,4s,3n", SpeedSequence.format(SpeedSequence.DEFAULT))
        assertNull(SpeedSequence.problemWith(SpeedSequence.DEFAULT))
    }

    @Test
    fun `what is wrong with a sequence is said in words`() {
        assertEquals("Add at least one sequence step", SpeedSequence.problemWith(emptyList()))
        assertEquals(
            "Sequence has too many steps (max 20)",
            SpeedSequence.problemWith(List(21) { SequenceStep(1, normal) })
        )
        assertEquals(
            "Each step count must be between 1 and 100",
            SpeedSequence.problemWith(steps(3 to normal, 0 to slow))
        )
        assertEquals(
            "Each step count must be between 1 and 100",
            SpeedSequence.problemWith(steps(101 to normal))
        )
        assertEquals(
            "Sequence totals 120 repetitions (max 100)",
            SpeedSequence.problemWith(steps(60 to normal, 60 to slow))
        )
        assertNull(SpeedSequence.problemWith(steps(50 to normal, 50 to slow)))
        assertNull(SpeedSequence.problemWith(List(20) { SequenceStep(5, slow) }))
    }
}
