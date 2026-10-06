package com.purrfectbytes.android.services

/**
 * One line of a conversation, spoken by speaker 0 or 1.
 *
 * [label] is the name written in front of the line ("직원"). It is shown on screen but
 * never spoken; it is empty when the line names nobody.
 */
data class DialogueLine(val text: String, val speaker: Int, val label: String = "")

/** The text cannot be read as a conversation; the message says why, in words for the user. */
class DialogueException(message: String) : RenderInputException(message)

/**
 * Text as a conversation between two voices: every line is one turn.
 *
 * A port of the web app's dialogue_utils.py, so a conversation is split the same way in
 * both apps. One difference: whitespace inside a line is reduced to single spaces here,
 * which is how the video shows it anyway.
 */
object Dialogue {

    const val MAX_LINES = 40

    /** Shown in front of a line that names no speaker. */
    const val SPEAKER_PREFIX = "— "

    const val MAX_LABEL_LENGTH = 20

    /** A colon after one of these ends a clause, not the name of a speaker. */
    private const val NOT_IN_A_LABEL = ".!?。！？…,、;"

    /**
     * Splits "직원: 혼자 오셨어요?" into "직원" and "혼자 오셨어요?".
     *
     * A line that names no speaker comes back whole, with an empty name. What counts as
     * a name is kept narrow on purpose, so that an ordinary sentence with a colon in it
     * is left alone.
     */
    fun splitSpeakerLabel(line: String): Pair<String, String> {
        val whole = "" to line

        val colon = line.indexOfFirst { it == ':' || it == '：' }
        if (colon < 1) return whole

        val name = line.substring(0, colon)
        if (name.codePointCount(0, name.length) > MAX_LABEL_LENGTH) return whole
        if (name.first().isWhitespace() || '/' in name) return whole

        val label = name.trim()
        val spoken = line.substring(colon + 1).trim()
        // A slash right after the colon belongs to an address such as https://...
        if (label.isEmpty() || spoken.isEmpty() || spoken.startsWith("/")) return whole
        if (label.any { it in NOT_IN_A_LABEL }) return whole

        return label to spoken
    }

    private fun linesOf(text: String): List<String> =
        text.lines().map { HighlightTiming.singleSpaced(it) }.filter { it.isNotEmpty() }

    /**
     * Splits [text] into the turns of two speakers.
     *
     * Lines with the same name have the same voice, so a speaker may have two turns in a
     * row. Lines without a name take turns: 0, 1, 0, 1, ... Empty lines are skipped.
     */
    fun parse(text: String): List<DialogueLine> {
        val lines = linesOf(text)
        if (lines.size < 2) {
            throw DialogueException(
                "Conversation mode needs at least 2 lines of text - " +
                    "each line alternates between the two voices"
            )
        }
        if (lines.size > MAX_LINES) {
            throw DialogueException("Conversation has too many lines (max $MAX_LINES)")
        }

        val parsed = lines.map { splitSpeakerLabel(it) }

        // The names, in the order they first appear, are voices 0 and 1
        val names = mutableListOf<String>()
        for ((label, _) in parsed) {
            if (label.isNotEmpty() && names.none { it.equals(label, ignoreCase = true) }) names += label
        }
        if (names.size > 2) {
            throw DialogueException(
                "Conversation mode supports two speakers, but found ${names.size}: ${names.joinToString(", ")}"
            )
        }

        var previous: Int? = null
        return parsed.map { (label, spoken) ->
            val speaker = when {
                label.isNotEmpty() -> names.indexOfFirst { it.equals(label, ignoreCase = true) }
                else -> previous?.let { 1 - it } ?: 0
            }
            previous = speaker
            DialogueLine(text = spoken, speaker = speaker, label = label)
        }
    }

    /**
     * The lines of a pronunciation override, matched to the conversation they belong to.
     *
     * Each line of [voicedText] is spoken in place of the line at the same position, by
     * the same speaker. A name in front of an override line is dropped, so the override
     * can be copied from the text and changed where needed.
     */
    fun parseVoiced(displayLines: List<DialogueLine>, voicedText: String): List<DialogueLine> {
        val lines = linesOf(voicedText)
        if (lines.size != displayLines.size) {
            throw DialogueException(
                "Pronunciation override must have the same number of lines as the " +
                    "text (${displayLines.size}), got ${lines.size}"
            )
        }
        return lines.zip(displayLines) { line, display ->
            display.copy(text = splitSpeakerLabel(line).second)
        }
    }

    /** "6 lines, 2 voices" */
    fun describe(lines: List<DialogueLine>): String = "${lines.size} lines, 2 voices"

    /**
     * The conversation as it is shown on screen, and where in it the spoken part of each
     * line begins.
     *
     * Every line starts with its speaker's name, or with a dash when it names nobody. The
     * highlight only ever rests on what comes after that.
     */
    fun displayText(lines: List<DialogueLine>): Pair<String, List<Int>> {
        val shown = StringBuilder()
        val offsets = mutableListOf<Int>()
        lines.forEachIndexed { index, line ->
            if (index > 0) shown.append('\n')
            shown.append(if (line.label.isNotEmpty()) "${line.label}: " else SPEAKER_PREFIX)
            offsets += shown.length
            shown.append(line.text)
        }
        return shown.toString() to offsets
    }
}
