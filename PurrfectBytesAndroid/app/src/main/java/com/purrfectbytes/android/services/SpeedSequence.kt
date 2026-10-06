package com.purrfectbytes.android.services

/** One step of a speed sequence: the reading is repeated [count] times, at normal or slow speed. */
data class SequenceStep(val count: Int, val slow: Boolean)

/**
 * Repetitions at mixed speeds, such as 3 normal, then 4 slow, then 3 normal.
 *
 * A port of the web app's sequence_utils.py, with the same limits and the same short
 * form ("3n,4s,3n"), so a sequence means the same in both apps.
 */
object SpeedSequence {

    const val MAX_STEPS = 20
    const val MAX_TOTAL_REPETITIONS = 100

    /** What a sequence starts as when it is switched on for the first time. */
    val DEFAULT = listOf(
        SequenceStep(count = 3, slow = false),
        SequenceStep(count = 4, slow = true),
        SequenceStep(count = 3, slow = false)
    )

    fun total(steps: List<SequenceStep>): Int = steps.sumOf { it.count }

    /** "3 normal, 4 slow, 3 normal" */
    fun describe(steps: List<SequenceStep>): String =
        steps.joinToString(", ") { "${it.count} ${if (it.slow) "slow" else "normal"}" }

    /** "3n,4s,3n" */
    fun format(steps: List<SequenceStep>): String =
        steps.joinToString(",") { "${it.count}${if (it.slow) "s" else "n"}" }

    /** What is wrong with [steps], in words for the user, or null when they can be used. */
    fun problemWith(steps: List<SequenceStep>): String? = when {
        steps.isEmpty() -> "Add at least one sequence step"
        steps.size > MAX_STEPS -> "Sequence has too many steps (max $MAX_STEPS)"
        steps.any { it.count !in 1..MAX_TOTAL_REPETITIONS } ->
            "Each step count must be between 1 and $MAX_TOTAL_REPETITIONS"
        total(steps) > MAX_TOTAL_REPETITIONS ->
            "Sequence totals ${total(steps)} repetitions (max $MAX_TOTAL_REPETITIONS)"
        else -> null
    }

    /** Reads the short form. Returns null when [spec] is not a sequence that can be used. */
    fun parse(spec: String?): List<SequenceStep>? {
        val tokens = spec.orEmpty().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val steps = tokens.map { token ->
            val count = token.dropLast(1)
            if (count.isEmpty() || count.length > 3 || !count.all { it in '0'..'9' }) return null
            when (token.last()) {
                'n' -> SequenceStep(count.toInt(), slow = false)
                's' -> SequenceStep(count.toInt(), slow = true)
                else -> return null
            }
        }
        return steps.takeIf { problemWith(it) == null }
    }
}
