package com.purrfectbytes.android.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTimingTest {

    private class Case(val text: String, val duration: Double, val words: List<WordBoundary>, val starts: DoubleArray)

    // What the web app (audio_timing.py, compute_character_timings without lead time and
    // overlap) computes for the same input. The Android port must agree with it.
    private val cases = listOf(
        Case(
            text = "Hello world",
            duration = 2.0,
            words = listOf(WordBoundary("Hello", 0.1, 0.6), WordBoundary("world", 0.7, 1.4)),
            starts = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.84, 0.98, 1.12, 1.26)
        ),
        Case(
            text = "Hello, big world!",
            duration = 3.0,
            words = listOf(WordBoundary("Hello", 0.1, 0.5), WordBoundary("big", 0.9, 1.1), WordBoundary("world", 1.2, 1.9)),
            starts = doubleArrayOf(0.1, 0.18, 0.26, 0.34, 0.42, 0.5, 0.7, 0.9, 0.966666667, 1.033333333, 1.1, 1.2, 1.34, 1.48, 1.62, 1.76, 1.9)
        ),
        Case(
            text = "今日は天気がいい",
            duration = 2.4,
            words = listOf(WordBoundary("今日", 0.1, 0.5), WordBoundary("は", 0.5, 0.7), WordBoundary("天気", 0.8, 1.3), WordBoundary("が", 1.3, 1.5), WordBoundary("いい", 1.5, 2.0)),
            starts = doubleArrayOf(0.1, 0.3, 0.5, 0.8, 1.05, 1.3, 1.5, 1.75)
        ),
        Case(
            text = "HELLO World",
            duration = 2.0,
            words = listOf(WordBoundary("hello", 0.0, 0.5), WordBoundary("world", 0.6, 1.0)),
            starts = doubleArrayOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.68, 0.76, 0.84, 0.92)
        ),
        Case(
            text = "It costs 25 dollars",
            duration = 3.0,
            words = listOf(WordBoundary("It", 0.1, 0.2), WordBoundary("costs", 0.3, 0.6), WordBoundary("twenty-five", 0.7, 1.3), WordBoundary("dollars", 1.4, 2.0)),
            starts = doubleArrayOf(0.1, 0.15, 0.2, 0.3, 0.36, 0.42, 0.48, 0.54, 0.6, 0.8, 1.0, 1.2, 1.4, 1.485714286, 1.571428571, 1.657142857, 1.742857143, 1.828571429, 1.914285714)
        ),
        Case(
            text = "abc def",
            duration = 1.0,
            words = listOf(WordBoundary("xyz", 0.0, 0.5), WordBoundary("qqq", 0.5, 1.0)),
            starts = doubleArrayOf(0.0, 0.153846154, 0.307692308, 0.461538462, 0.538461538, 0.692307692, 0.846153846)
        ),
        Case(
            text = "abc def",
            duration = 1.0,
            words = listOf(),
            starts = doubleArrayOf(0.0, 0.153846154, 0.307692308, 0.461538462, 0.538461538, 0.692307692, 0.846153846)
        ),
        Case(
            text = "a  b\nc",
            duration = 4.0,
            words = listOf(),
            starts = doubleArrayOf(0.0, 0.888888889, 1.333333333, 1.777777778, 2.666666667, 3.111111111)
        )
    )

    @Test
    fun `characters start when the web app says they start`() {
        cases.forEachIndexed { index, case ->
            val starts = HighlightTiming.characterStartTimes(case.text, case.duration, case.words)
            assertArrayEquals("case $index: ${case.text}", case.starts, starts, 1e-6)
        }
    }

    @Test
    fun `empty text has no timing`() {
        assertEquals(0, HighlightTiming.characterStartTimes("", 3.0, emptyList()).size)
    }

    @Test
    fun `whitespace is cleaned up for display but line breaks stay`() {
        assertEquals("one two\nthree", HighlightTiming.displayText("  one   two \n\n\t three  "))
        assertEquals("", HighlightTiming.displayText(" \n \n"))
    }

    @Test
    fun `units are the characters a reader sees`() {
        val text = "né e\u0301 😀"
        val units = HighlightTiming.units(text).map { text.substring(it.start, it.end) }
        // The second é is an e followed by a separate accent; the emoji is two code units
        assertEquals(listOf("n", "é", "e\u0301", "😀"), units)
    }

    @Test
    fun `whitespace is never a unit`() {
        val text = " a \n b　c "
        val units = HighlightTiming.units(text).map { text.substring(it.start, it.end) }
        assertEquals(listOf("a", "b", "c"), units)
    }

    @Test
    fun `units start with the word they belong to`() {
        val text = "Hello world"
        val words = listOf(WordBoundary("Hello", 0.1, 0.6), WordBoundary("world", 0.7, 1.4))
        val units = HighlightTiming.units(text)
        val starts = HighlightTiming.unitStartTimes(text, units, 2.0, words)

        assertEquals(10, units.size)
        assertEquals(0.1, starts[0], 1e-9) // H
        assertEquals(0.7, starts[5], 1e-9) // w
    }

    @Test
    fun `unit times never go backwards`() {
        // The service reports the second word as starting before the first one ended
        val text = "ab cd"
        val words = listOf(WordBoundary("ab", 0.5, 1.5), WordBoundary("cd", 1.0, 1.2))
        val starts = HighlightTiming.unitStartTimes(text, HighlightTiming.units(text), 2.0, words)
        assertTrue(starts.toList().zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `frames follow the start times`() {
        // 30 frames a second: unit 1 starts at frame 13 (0.4333 s), unit 2 at frame 19
        val segments = HighlightTiming.frameSegments(doubleArrayOf(0.0, 13 / 30.0, 19 / 30.0), 1.0, 30)
        assertEquals(
            listOf(FrameSegment(0, 13), FrameSegment(1, 6), FrameSegment(2, 11)),
            segments
        )
    }

    @Test
    fun `the first unit is shown during the silence before the first word`() {
        val segments = HighlightTiming.frameSegments(doubleArrayOf(0.5, 1.0), 2.0, 10)
        assertEquals(listOf(FrameSegment(0, 10), FrameSegment(1, 10)), segments)
    }

    @Test
    fun `frames cover the whole audio`() {
        val segments = HighlightTiming.frameSegments(doubleArrayOf(0.0, 0.31, 0.32, 2.0), 3.3, 30)
        assertEquals(99, segments.sumOf { it.frameCount })
        assertTrue(segments.all { it.frameCount > 0 })
    }

    @Test
    fun `a unit shorter than a frame is skipped`() {
        // Units 1 and 2 both start between frame 3 and frame 4
        val segments = HighlightTiming.frameSegments(doubleArrayOf(0.0, 0.31, 0.32), 1.0, 10)
        assertEquals(listOf(0, 2), segments.map { it.unitIndex })
    }

    @Test
    fun `text without anything to highlight gives frames without highlight`() {
        assertEquals(listOf(FrameSegment(-1, 15)), HighlightTiming.frameSegments(DoubleArray(0), 0.5, 30))
    }

    @Test
    fun `audio of unknown length still gives a frame`() {
        assertEquals(listOf(FrameSegment(0, 1)), HighlightTiming.frameSegments(doubleArrayOf(0.0), 0.0, 30))
    }

    // ------------------------------------------------ one line of several

    @Test
    fun `a line is tidied like the text it belongs to`() {
        assertEquals("one two three", HighlightTiming.singleSpaced("  one \t two\u3000\u3000three\u00a0"))
        assertEquals("", HighlightTiming.singleSpaced(" \t "))
        assertEquals("猫", HighlightTiming.singleSpaced("猫"))
    }

    @Test
    fun `the wide space is a space on every computer`() {
        // A pattern with "\s" finds this space on a phone but not where the tests run
        assertEquals("吾輩は 猫である", HighlightTiming.displayText("吾輩は\u3000\u3000猫である\u3000"))
    }

    @Test
    fun `the units of a line are found in the text on screen`() {
        val shown = "— Hello\nA: Hi there\n— Bye"
        val units = HighlightTiming.units(shown)
        fun textOf(range: IntRange) = range.joinToString("") { shown.substring(units[it].start, units[it].end) }

        assertEquals("Hello", textOf(HighlightTiming.unitsWithin(units, offset = 2, length = 5)))
        assertEquals("Hithere", textOf(HighlightTiming.unitsWithin(units, offset = 11, length = 8)))
        assertEquals("Bye", textOf(HighlightTiming.unitsWithin(units, offset = 22, length = 3)))
    }

    @Test
    fun `a line without anything to highlight has no units`() {
        val shown = "ab  cd"
        val units = HighlightTiming.units(shown)

        assertTrue(HighlightTiming.unitsWithin(units, offset = 2, length = 2).isEmpty())
        assertTrue(HighlightTiming.unitsWithin(units, offset = 6, length = 4).isEmpty())
        assertTrue(HighlightTiming.unitsWithin(emptyList(), offset = 0, length = 4).isEmpty())
    }

    @Test
    fun `the times of a line are counted from the start of its own audio`() {
        val shown = "A: Hello\nB: big world"
        val units = HighlightTiming.units(shown)
        val line = HighlightTiming.unitsWithin(units, offset = 12, length = 9)
        val words = listOf(WordBoundary("big", 0.2, 0.5), WordBoundary("world", 0.6, 1.1))

        val starts = HighlightTiming.unitStartTimes(
            "big world", units.slice(line), 1.5, words, offset = 12
        )

        assertEquals(8, starts.size)
        assertEquals(0.2, starts[0], 1e-9) // b
        assertEquals(0.4, starts[2], 1e-9) // g
        assertEquals(0.6, starts[3], 1e-9) // w
        assertEquals(1.0, starts[7], 1e-9) // d
    }
}
