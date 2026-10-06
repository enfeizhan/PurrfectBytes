package com.purrfectbytes.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DialogueTest {

    private fun failureOf(block: () -> Unit): DialogueException =
        try {
            block()
            fail("expected a DialogueException")
            throw IllegalStateException()
        } catch (e: DialogueException) {
            e
        }

    // ------------------------------------------------------ names of speakers

    // What the web app (dialogue_utils.py, split_speaker_label) makes of the same lines
    private val named = listOf(
        "직원: 혼자 오셨어요?" to ("직원" to "혼자 오셨어요?"),
        "店員：一人ですか？" to ("店員" to "一人ですか？"),
        "A:b" to ("A" to "b"),
        "A:  b" to ("A" to "b"),
        "A:\tb" to ("A" to "b"),
        "Tom : hello" to ("Tom" to "hello"),
        "Dr Who: hi" to ("Dr Who" to "hi"),
        "A: b: c" to ("A" to "b: c"),
        "Q: What?" to ("Q" to "What?"),
        "「先生」：こんにちは" to ("「先生」" to "こんにちは"),
        "😀😀: hi" to ("😀😀" to "hi"),
        "10:30 is the time" to ("10" to "30 is the time"),
        "12345678901234567890: x" to ("12345678901234567890" to "x")
    )

    private val notNamed = listOf(
        "혼자 오셨어요?",
        "Wait, listen: this is important.",
        "A very long introductory phrase indeed: the rest",
        "123456789012345678901: x",
        "See https://example.com for details",
        "http://x",
        "a/b: c",
        "직원:",
        "A: ",
        ": text",
        "Mr. Kim: hello",
        "Ann; Bob: hi",
        "はい、先生：こんにちは",
        "A…: b"
    )

    @Test
    fun `a name in front of a line is told from what is spoken`() {
        named.forEach { (line, expected) ->
            assertEquals("\"$line\"", expected, Dialogue.splitSpeakerLabel(line))
        }
    }

    @Test
    fun `a sentence with a colon in it is left alone`() {
        notNamed.forEach { line ->
            assertEquals("\"$line\"", "" to line, Dialogue.splitSpeakerLabel(line))
        }
    }

    @Test
    fun `the wide space of Chinese and Japanese may follow the colon`() {
        // The web app does not take this for a name; here the space is like any other
        assertEquals("A" to "b", Dialogue.splitSpeakerLabel("A:\u3000b"))
        assertEquals(
            listOf(DialogueLine("こんにちは", 0, "店員"), DialogueLine("はい", 1, "客")),
            Dialogue.parse("店員：\u3000こんにちは\n客：\u3000はい")
        )
    }

    // ------------------------------------------------------------ whose turn

    private class Case(
        val text: String,
        val speakers: List<Int>,
        val shown: String,
        val offsets: List<Int>
    )

    // What the web app (parse_dialogue and dialogue_display_text) makes of the same text
    private val cases = listOf(
        Case(
            text = "Hello\nHi there\nHow are you?\nGreat!",
            speakers = listOf(0, 1, 0, 1),
            shown = "— Hello\n— Hi there\n— How are you?\n— Great!",
            offsets = listOf(2, 10, 21, 36)
        ),
        Case(
            text = "직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요.",
            speakers = listOf(0, 1),
            shown = "직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요.",
            offsets = listOf(4, 18)
        ),
        Case(
            text = "A: one\nA: two\nB: three\nA: four",
            speakers = listOf(0, 0, 1, 0),
            shown = "A: one\nA: two\nB: three\nA: four",
            offsets = listOf(3, 10, 17, 26)
        ),
        Case(
            text = "Ann: hi\nBob: hello\nann: bye",
            speakers = listOf(0, 1, 0),
            shown = "Ann: hi\nBob: hello\nann: bye",
            offsets = listOf(5, 13, 24)
        ),
        Case(
            text = "A: one\nplain\nA: three",
            speakers = listOf(0, 1, 0),
            shown = "A: one\n— plain\nA: three",
            offsets = listOf(3, 9, 18)
        ),
        Case(
            text = "직원: 안녕하세요\n반갑습니다",
            speakers = listOf(0, 1),
            shown = "직원: 안녕하세요\n— 반갑습니다",
            offsets = listOf(4, 12)
        ),
        Case(
            text = "plain\nA: two\nplain again\nB: four\nlast",
            speakers = listOf(0, 0, 1, 1, 0),
            shown = "— plain\nA: two\n— plain again\nB: four\n— last",
            offsets = listOf(2, 11, 17, 32, 39)
        ),
        Case(
            text = "B: first\nA: second\nB: third",
            speakers = listOf(0, 1, 0),
            shown = "B: first\nA: second\nB: third",
            offsets = listOf(3, 12, 22)
        )
    )

    @Test
    fun `the turns are the ones of the web app`() {
        cases.forEach { case ->
            val lines = Dialogue.parse(case.text)
            val (shown, offsets) = Dialogue.displayText(lines)

            assertEquals(case.text, case.speakers, lines.map { it.speaker })
            assertEquals(case.text, case.shown, shown)
            assertEquals(case.text, case.offsets, offsets)
        }
    }

    @Test
    fun `every offset is where the spoken part of its line begins`() {
        cases.forEach { case ->
            val lines = Dialogue.parse(case.text)
            val (shown, offsets) = Dialogue.displayText(lines)

            lines.forEachIndexed { index, line ->
                assertEquals(line.text, shown.substring(offsets[index], offsets[index] + line.text.length))
            }
        }
    }

    @Test
    fun `empty lines and spaces around a line do not count`() {
        val lines = Dialogue.parse("\n  Hello   there \n\n\n\tHi\n   \n")

        assertEquals(listOf(DialogueLine("Hello there", 0), DialogueLine("Hi", 1)), lines)
        assertEquals("2 lines, 2 voices", Dialogue.describe(lines))
    }

    @Test
    fun `a conversation needs two lines`() {
        val message = "Conversation mode needs at least 2 lines of text - " +
            "each line alternates between the two voices"

        assertEquals(message, failureOf { Dialogue.parse("Only one line") }.message)
        assertEquals(message, failureOf { Dialogue.parse("\n\nOnly one line\n\n") }.message)
        assertEquals(message, failureOf { Dialogue.parse("") }.message)
    }

    @Test
    fun `a conversation has at most forty lines`() {
        assertEquals(40, Dialogue.parse(List(40) { "line $it" }.joinToString("\n")).size)
        assertEquals(
            "Conversation has too many lines (max 40)",
            failureOf { Dialogue.parse(List(41) { "line $it" }.joinToString("\n")) }.message
        )
    }

    @Test
    fun `a third speaker is refused by name`() {
        val error = failureOf { Dialogue.parse("A: one\nB: two\nC: three\na: four") }

        assertEquals("Conversation mode supports two speakers, but found 3: A, B, C", error.message)
    }

    @Test
    fun `what cannot be read is a problem of the input`() {
        assertTrue(failureOf { Dialogue.parse("one line") } is RenderInputException)
    }

    // ---------------------------------------------------------- pronunciation

    @Test
    fun `an override is spoken line by line by the same speakers`() {
        val lines = Dialogue.parse("직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요.")

        val voiced = Dialogue.parseVoiced(lines, "직원: 혼자 오셨어요\n관광객: 아니요")

        assertEquals(
            listOf(DialogueLine("혼자 오셨어요", 0, "직원"), DialogueLine("아니요", 1, "관광객")),
            voiced
        )
    }

    @Test
    fun `an override needs no names and ignores empty lines`() {
        val lines = Dialogue.parse("A: 行った\nB: 来た")

        val voiced = Dialogue.parseVoiced(lines, "\nおこなった\n\nきた\n")

        assertEquals(listOf(DialogueLine("おこなった", 0, "A"), DialogueLine("きた", 1, "B")), voiced)
    }

    @Test
    fun `an override with another number of lines is refused`() {
        val lines = Dialogue.parse("Hello\nHi there")

        assertEquals(
            "Pronunciation override must have the same number of lines as the text (2), got 1",
            failureOf { Dialogue.parseVoiced(lines, "Hello") }.message
        )
        assertEquals(
            "Pronunciation override must have the same number of lines as the text (2), got 3",
            failureOf { Dialogue.parseVoiced(lines, "a\nb\nc") }.message
        )
    }
}
