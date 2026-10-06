package com.purrfectbytes.android.services

/** What was entered cannot be made into a video; the message says why, in words for the user. */
open class RenderInputException(message: String) : Exception(message)

/** What is to be made into a video, as it was chosen on screen. */
data class RenderRequest(
    val text: String,
    /** Spoken in place of [text] where the engine misreads it; null when not used. */
    val voicedText: String? = null,
    /** True when every line of the text is a turn in a conversation of two voices. */
    val conversation: Boolean = false,
    /** The voice of the text, or of the first speaker. Null is the voice of the language. */
    val voice: String? = null,
    /** The voice of the second speaker of a conversation. */
    val secondVoice: String? = null,
    /** How often the reading is repeated and at which speed; plain repetitions are one step. */
    val steps: List<SequenceStep>,
    /** True when [steps] were entered as a speed sequence. */
    val isSequence: Boolean = false
)

/**
 * Speech that is generated once, together with the part of the text on screen that the
 * highlight moves across while it is heard.
 */
data class Clip(
    /** What the engine is asked to say. */
    val spokenText: String,
    /** The text on screen this speech belongs to; with a pronunciation override it differs from [spokenText]. */
    val shownText: String,
    /** Where [shownText] begins in the text on screen. */
    val offset: Int,
    val voice: String?,
    val slow: Boolean
)

/** Everything that has to be generated for a video, and the order it is played in. */
data class RenderPlan(
    /** The text on screen. It is the same in every frame. */
    val displayText: String,
    val isConversation: Boolean,
    /** Every piece of speech once, however often it is played. */
    val clips: List<Clip>,
    /** The clips as they follow each other in the video, as positions in [clips]. */
    val order: List<Int>,
    /** What was made, for the message after rendering: "Video generated and repeated 10 times". */
    val message: String,
    /** The same in a few words, for the card of the video: "10 repetitions". */
    val summary: String
) {
    /** The clips that are heard in the first run through the text, as positions in [clips]. */
    val firstReading: List<Int>
        get() {
            val slow = clips[order.first()].slow
            return clips.indices.filter { clips[it].slow == slow }
        }
}

/**
 * Turns what was chosen on screen into what has to be generated.
 *
 * It follows the web app (conversion_routes.py): speech is generated once for every
 * speed, or once for every line and speed of a conversation, and the video plays those
 * pieces in the order of the repetitions.
 */
object RenderPlanner {

    /** Throws [RenderInputException] when [request] cannot be made into a video. */
    fun plan(request: RenderRequest): RenderPlan {
        SpeedSequence.problemWith(request.steps)?.let { throw RenderInputException(it) }

        val override = request.voicedText?.trim()?.takeIf { it.isNotEmpty() }
        // Normal speed first, like the web app
        val speeds = request.steps.map { it.slow }.distinct().sorted()

        return if (request.conversation) {
            conversation(request, override, speeds)
        } else {
            singleVoice(request, override, speeds)
        }
    }

    private fun singleVoice(request: RenderRequest, override: String?, speeds: List<Boolean>): RenderPlan {
        val shown = HighlightTiming.displayText(request.text)
        if (shown.isEmpty()) throw RenderInputException("Please enter some text")

        // As it was typed: the engine pauses at a line break, as between two sentences
        val spoken = override ?: request.text.trim()
        val clips = speeds.map { slow ->
            Clip(spokenText = spoken, shownText = shown, offset = 0, voice = request.voice, slow = slow)
        }
        val order = request.steps.flatMap { step -> List(step.count) { speeds.indexOf(step.slow) } }

        val repetitions = SpeedSequence.total(request.steps)
        return RenderPlan(
            displayText = shown,
            isConversation = false,
            clips = clips,
            order = order,
            message = when {
                request.isSequence ->
                    "Video generated with sequence ${SpeedSequence.describe(request.steps)} (${counted(repetitions)})"
                repetitions > 1 -> "Video generated and repeated $repetitions times"
                else -> "Video generated"
            },
            summary = when {
                request.isSequence -> "Sequence: ${SpeedSequence.describe(request.steps)}"
                repetitions > 1 -> counted(repetitions)
                else -> ""
            }
        )
    }

    private fun counted(repetitions: Int): String =
        if (repetitions == 1) "1 repetition" else "$repetitions repetitions"

    private fun conversation(request: RenderRequest, override: String?, speeds: List<Boolean>): RenderPlan {
        val lines = Dialogue.parse(request.text)
        val voiced = if (override != null) Dialogue.parseVoiced(lines, override) else lines
        val (shown, offsets) = Dialogue.displayText(lines)

        val clips = speeds.flatMap { slow ->
            lines.indices.map { index ->
                Clip(
                    spokenText = voiced[index].text,
                    shownText = lines[index].text,
                    offset = offsets[index],
                    voice = if (lines[index].speaker == 1) request.secondVoice else request.voice,
                    slow = slow
                )
            }
        }
        // Every repetition is the whole conversation, at the speed of its step
        val order = request.steps.flatMap { step ->
            val first = speeds.indexOf(step.slow) * lines.size
            List(step.count) { lines.indices.map { first + it } }.flatten()
        }

        val repetitions = counted(SpeedSequence.total(request.steps))
        val sequence = SpeedSequence.describe(request.steps)
        return RenderPlan(
            displayText = shown,
            isConversation = true,
            clips = clips,
            order = order,
            message = "Conversation video generated (${Dialogue.describe(lines)}, " +
                (if (request.isSequence) "sequence $sequence, " else "") + "$repetitions)",
            summary = "Conversation of ${lines.size} lines, " +
                if (request.isSequence) "sequence: $sequence" else repetitions
        )
    }
}
