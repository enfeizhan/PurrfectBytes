package com.purrfectbytes.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RenderPlannerTest {

    private val normal = false
    private val slow = true

    private fun steps(vararg steps: Pair<Int, Boolean>) = steps.map { SequenceStep(it.first, it.second) }

    private fun repeated(times: Int, slowly: Boolean = false) = steps(times to slowly)

    private fun failureOf(request: RenderRequest): RenderInputException =
        try {
            RenderPlanner.plan(request)
            fail("expected a RenderInputException")
            throw IllegalStateException()
        } catch (e: RenderInputException) {
            e
        }

    // ------------------------------------------------------------- one voice

    @Test
    fun `a text read once is one clip played once`() {
        val plan = RenderPlanner.plan(RenderRequest(text = "Hello world", steps = repeated(1)))

        assertEquals(listOf(Clip("Hello world", "Hello world", 0, null, slow = false)), plan.clips)
        assertEquals(listOf(0), plan.order)
        assertEquals("Hello world", plan.displayText)
        assertFalse(plan.isConversation)
        assertEquals("Video generated", plan.message)
        assertEquals("", plan.summary)
    }

    @Test
    fun `repetitions play the same clip again`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "Hello world", voice = "en-GB-SoniaNeural", steps = repeated(10, slowly = true))
        )

        assertEquals(listOf(Clip("Hello world", "Hello world", 0, "en-GB-SoniaNeural", slow = true)), plan.clips)
        assertEquals(List(10) { 0 }, plan.order)
        assertEquals("Video generated and repeated 10 times", plan.message)
        assertEquals("10 repetitions", plan.summary)
    }

    @Test
    fun `a sequence needs the speech once for every speed`() {
        val plan = RenderPlanner.plan(
            RenderRequest(
                text = "Hello world",
                steps = steps(3 to normal, 4 to slow, 3 to normal),
                isSequence = true
            )
        )

        assertEquals(listOf(false, true), plan.clips.map { it.slow })
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 0, 0, 0), plan.order)
        assertEquals(
            "Video generated with sequence 3 normal, 4 slow, 3 normal (10 repetitions)",
            plan.message
        )
        assertEquals("Sequence: 3 normal, 4 slow, 3 normal", plan.summary)
    }

    @Test
    fun `a sequence that begins slowly is first heard slowly`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "Hello", steps = steps(2 to slow, 1 to normal), isSequence = true)
        )

        assertEquals(listOf(false, true), plan.clips.map { it.slow })
        assertEquals(listOf(1, 1, 0), plan.order)
        assertEquals(listOf(1), plan.firstReading)
    }

    @Test
    fun `a sequence of one speed needs one clip`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "Hello", steps = steps(2 to slow, 3 to slow), isSequence = true)
        )

        assertEquals(listOf(true), plan.clips.map { it.slow })
        assertEquals(List(5) { 0 }, plan.order)
        assertEquals("Video generated with sequence 2 slow, 3 slow (5 repetitions)", plan.message)
    }

    @Test
    fun `a sequence of one repetition is counted in the singular`() {
        val plan = RenderPlanner.plan(RenderRequest(text = "Hello", steps = steps(1 to slow), isSequence = true))

        assertEquals("Video generated with sequence 1 slow (1 repetition)", plan.message)
    }

    @Test
    fun `the text is shown tidied but spoken as it was typed`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "  Good   morning \n\n How are you today  \n", steps = repeated(1))
        )

        assertEquals("Good morning\nHow are you today", plan.displayText)
        assertEquals("Good morning\nHow are you today", plan.clips.single().shownText)
        // The line break is what makes the voice pause between the two lines
        assertEquals("Good   morning \n\n How are you today", plan.clips.single().spokenText)
    }

    @Test
    fun `an override is spoken while the text is shown`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "会議を行った", voicedText = " 会議をおこなった \n", steps = repeated(2))
        )

        assertEquals("会議を行った", plan.displayText)
        assertEquals("会議をおこなった", plan.clips.single().spokenText)
        assertEquals("会議を行った", plan.clips.single().shownText)
    }

    @Test
    fun `an empty override is no override`() {
        val plan = RenderPlanner.plan(RenderRequest(text = "Hello", voicedText = "  \n ", steps = repeated(1)))

        assertEquals("Hello", plan.clips.single().spokenText)
    }

    @Test
    fun `text is needed`() {
        assertEquals("Please enter some text", failureOf(RenderRequest(text = " \n ", steps = repeated(1))).message)
    }

    @Test
    fun `a sequence that cannot be played is refused`() {
        assertEquals(
            "Add at least one sequence step",
            failureOf(RenderRequest(text = "Hello", steps = emptyList(), isSequence = true)).message
        )
        assertEquals(
            "Sequence totals 120 repetitions (max 100)",
            failureOf(
                RenderRequest(text = "Hello", steps = steps(60 to normal, 60 to slow), isSequence = true)
            ).message
        )
    }

    // ---------------------------------------------------------- conversation

    private val korean = "직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요.\n직원: 이쪽으로 오세요."

    @Test
    fun `a conversation is one clip for every line, in two voices`() {
        val plan = RenderPlanner.plan(
            RenderRequest(
                text = korean,
                conversation = true,
                voice = "ko-KR-SunHiNeural",
                secondVoice = "ko-KR-InJoonNeural",
                steps = repeated(2)
            )
        )

        assertTrue(plan.isConversation)
        assertEquals(korean, plan.displayText)
        assertEquals(
            listOf(
                Clip("혼자 오셨어요?", "혼자 오셨어요?", 4, "ko-KR-SunHiNeural", slow = false),
                Clip("아니요, 친구하고 같이 왔어요.", "아니요, 친구하고 같이 왔어요.", 18, "ko-KR-InJoonNeural", slow = false),
                Clip("이쪽으로 오세요.", "이쪽으로 오세요.", 40, "ko-KR-SunHiNeural", slow = false)
            ),
            plan.clips
        )
        assertEquals(listOf(0, 1, 2, 0, 1, 2), plan.order)
        assertEquals(listOf(0, 1, 2), plan.firstReading)
        assertEquals("Conversation video generated (3 lines, 2 voices, 2 repetitions)", plan.message)
        assertEquals("Conversation of 3 lines, 2 repetitions", plan.summary)
    }

    @Test
    fun `every clip shows the part of the screen it belongs to`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "Hello\nA: Hi there\nHow are you?", conversation = true, steps = repeated(1))
        )

        assertEquals("— Hello\nA: Hi there\n— How are you?", plan.displayText)
        plan.clips.forEach { clip ->
            assertEquals(
                clip.shownText,
                plan.displayText.substring(clip.offset, clip.offset + clip.shownText.length)
            )
        }
    }

    @Test
    fun `a sequence plays the whole conversation at the speed of each step`() {
        val plan = RenderPlanner.plan(
            RenderRequest(
                text = "Hello\nHi there",
                conversation = true,
                steps = steps(1 to slow, 2 to normal),
                isSequence = true
            )
        )

        assertEquals(listOf(false, false, true, true), plan.clips.map { it.slow })
        assertEquals(listOf("Hello", "Hi there", "Hello", "Hi there"), plan.clips.map { it.spokenText })
        assertEquals(listOf(2, 3, 0, 1, 0, 1), plan.order)
        assertEquals(listOf(2, 3), plan.firstReading)
        assertEquals(
            "Conversation video generated (2 lines, 2 voices, sequence 1 slow, 2 normal, 3 repetitions)",
            plan.message
        )
        assertEquals("Conversation of 2 lines, sequence: 1 slow, 2 normal", plan.summary)
    }

    @Test
    fun `without a second voice both speakers have the voice of the language`() {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "Hello\nHi there", conversation = true, steps = repeated(1))
        )

        assertNull(plan.clips[0].voice)
        assertNull(plan.clips[1].voice)
    }

    @Test
    fun `an override of a conversation is spoken line by line`() {
        val plan = RenderPlanner.plan(
            RenderRequest(
                text = "A: 会議を行った\nB: 京都に行った",
                voicedText = "A: 会議をおこなった\n京都にいった",
                conversation = true,
                steps = repeated(1)
            )
        )

        assertEquals("A: 会議を行った\nB: 京都に行った", plan.displayText)
        assertEquals(listOf("会議をおこなった", "京都にいった"), plan.clips.map { it.spokenText })
        assertEquals(listOf("会議を行った", "京都に行った"), plan.clips.map { it.shownText })
        assertEquals(listOf(3, 13), plan.clips.map { it.offset })
    }

    @Test
    fun `a conversation that cannot be read is refused`() {
        assertEquals(
            "Conversation mode needs at least 2 lines of text - each line alternates between the two voices",
            failureOf(RenderRequest(text = "Only one line", conversation = true, steps = repeated(1))).message
        )
        assertEquals(
            "Pronunciation override must have the same number of lines as the text (2), got 1",
            failureOf(
                RenderRequest(text = "Hello\nHi", voicedText = "Hello", conversation = true, steps = repeated(1))
            ).message
        )
    }
}
