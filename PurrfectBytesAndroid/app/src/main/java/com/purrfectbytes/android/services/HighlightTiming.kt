package com.purrfectbytes.android.services

import java.text.BreakIterator
import kotlin.math.ceil
import kotlin.math.max

/**
 * One thing the highlight can rest on: a character as the reader sees it, which may be
 * several code units (a Hindi syllable, a letter with its accent, an emoji).
 * [start] and [end] are positions in the displayed text.
 */
internal data class HighlightUnit(val start: Int, val end: Int)

/** The highlight rests on unit [unitIndex] for [frameCount] frames; -1 means no highlight. */
internal data class FrameSegment(val unitIndex: Int, val frameCount: Int)

/**
 * Decides which character is highlighted at which moment of the audio.
 *
 * Timing comes from two sources, in order of preference (the same as in the web app's
 * audio_timing.py):
 *
 * 1. The time of every word as reported by Edge TTS. Characters inside a word are spread
 *    across the word's duration; characters between words (punctuation, words that could
 *    not be found in the text) share the gap between their neighbours.
 * 2. Even spacing across the audio, used when no word times exist or they don't match
 *    the text.
 */
internal object HighlightTiming {

    /**
     * What is drawn on screen: line breaks are kept, blank lines are dropped, and other
     * runs of whitespace become a single space.
     */
    fun displayText(text: String): String =
        text.lines()
            .map { singleSpaced(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /**
     * [line] without whitespace at its ends and with a single space wherever it had
     * whitespace inside. Written out rather than left to a pattern, because "\s" means
     * more characters on a phone than on the computer the tests run on.
     */
    fun singleSpaced(line: String): String = buildString(line.length) {
        var pendingSpace = false
        for (char in line) {
            if (char.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                pendingSpace = false
                append(char)
            }
        }
    }

    /** The characters of [text] that can be highlighted, in reading order. Whitespace is skipped. */
    fun units(text: String): List<HighlightUnit> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)

        val units = mutableListOf<HighlightUnit>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (!text.substring(start, end).isBlank()) units += HighlightUnit(start, end)
            start = end
            end = iterator.next()
        }
        return units
    }

    /**
     * When each of [units] becomes highlighted, in seconds. Never decreases.
     *
     * [text] is what the audio belongs to. It may be only a part of what is on screen,
     * one line of a conversation: [offset] is then where it begins in the text on
     * screen, which is what the positions of [units] refer to.
     */
    fun unitStartTimes(
        text: String,
        units: List<HighlightUnit>,
        durationSeconds: Double,
        wordBoundaries: List<WordBoundary>,
        offset: Int = 0
    ): DoubleArray {
        val characterTimes = characterStartTimes(text, durationSeconds, wordBoundaries)
        val times = DoubleArray(units.size)
        var latest = 0.0
        units.forEachIndexed { index, unit ->
            latest = max(latest, characterTimes[unit.start - offset])
            times[index] = latest
        }
        return times
    }

    /** The positions in [units] of those that lie in the [length] characters from [offset]. */
    fun unitsWithin(units: List<HighlightUnit>, offset: Int, length: Int): IntRange {
        val first = units.indexOfFirst { it.start >= offset }
        if (first < 0 || units[first].start >= offset + length) return IntRange.EMPTY
        var last = first
        while (last + 1 < units.size && units[last + 1].start < offset + length) last++
        return first..last
    }

    /** When each position of [text] starts being spoken, in seconds. */
    fun characterStartTimes(
        text: String,
        durationSeconds: Double,
        wordBoundaries: List<WordBoundary>
    ): DoubleArray {
        if (text.isEmpty()) return DoubleArray(0)
        if (wordBoundaries.isNotEmpty()) {
            fromWordBoundaries(text, durationSeconds, wordBoundaries)?.let { return it }
        }
        return evenlySpaced(text, durationSeconds)
    }

    private class Span(val start: Int, val end: Int, val startTime: Double, val endTime: Double)

    private fun fromWordBoundaries(
        text: String,
        durationSeconds: Double,
        boundaries: List<WordBoundary>
    ): DoubleArray? {
        val spans = mutableListOf<Span>()
        var cursor = 0
        for (boundary in boundaries) {
            if (boundary.word.isEmpty()) continue
            var index = text.indexOf(boundary.word, cursor)
            if (index == -1) index = text.indexOf(boundary.word, cursor, ignoreCase = true)
            if (index == -1) continue
            spans += Span(index, index + boundary.word.length, boundary.start, boundary.end)
            cursor = index + boundary.word.length
        }

        // If most words couldn't be located (the service spoke numbers or symbols in its
        // own words, unusual punctuation, ...), the mapping isn't trustworthy.
        if (spans.isEmpty() || spans.size < max(1, boundaries.size / 2)) return null

        val starts = DoubleArray(text.length) { Double.NaN }
        val ends = DoubleArray(text.length) { Double.NaN }

        // Spread the characters of each word across the word's duration
        for (span in spans) {
            val length = span.end - span.start
            val step = (span.endTime - span.startTime) / length
            for (k in 0 until length) {
                starts[span.start + k] = span.startTime + k * step
                ends[span.start + k] = span.startTime + (k + 1) * step
            }
        }

        // Characters outside any word share the gap between their neighbours
        var previousEnd = 0.0
        var i = 0
        while (i < text.length) {
            if (starts[i].isNaN()) {
                var j = i
                while (j < text.length && starts[j].isNaN()) j++
                val nextStart = if (j < text.length) starts[j] else durationSeconds
                val step = max(nextStart - previousEnd, 0.0) / (j - i)
                for (k in i until j) {
                    starts[k] = previousEnd + (k - i) * step
                    ends[k] = previousEnd + (k - i + 1) * step
                }
                i = j
            } else {
                previousEnd = ends[i]
                i++
            }
        }
        return starts
    }

    /** Spreads the characters evenly across the audio; whitespace counts half. */
    private fun evenlySpaced(text: String, durationSeconds: Double): DoubleArray {
        val weights = DoubleArray(text.length) { if (text[it].isWhitespace()) 0.5 else 1.0 }
        val total = weights.sum().takeIf { it > 0 } ?: 1.0
        val secondsPerWeight = if (durationSeconds > 0) durationSeconds / total else 1.0

        val starts = DoubleArray(text.length)
        var position = 0.0
        for (i in text.indices) {
            starts[i] = position * secondsPerWeight
            position += weights[i]
        }
        return starts
    }

    /**
     * Turns start times into runs of frames. Every frame shows the last unit that has
     * started, so the highlight holds during pauses instead of disappearing.
     */
    fun frameSegments(unitStartTimes: DoubleArray, durationSeconds: Double, fps: Int): List<FrameSegment> {
        val frameCount = max(1, ceil(durationSeconds * fps).toInt())
        if (unitStartTimes.isEmpty()) return listOf(FrameSegment(-1, frameCount))

        val segments = mutableListOf<FrameSegment>()
        var unit = 0
        var runStart = 0
        for (frame in 0 until frameCount) {
            val time = frame.toDouble() / fps
            var current = unit
            while (current + 1 < unitStartTimes.size && unitStartTimes[current + 1] <= time) current++
            if (current != unit) {
                if (frame > runStart) segments += FrameSegment(unit, frame - runStart)
                unit = current
                runStart = frame
            }
        }
        segments += FrameSegment(unit, frameCount - runStart)
        return segments
    }
}
