package com.purrfectbytes.android.services

import com.google.gson.JsonParser

/** A word or a grammar point of the sentence, as found by the model for the user to review. */
data class StudyItem(
    val isGrammar: Boolean,
    val term: String,
    val phonetics: String,
    val meaning: String
) {
    /** "元気 (げんき) = healthy; well" */
    val label: String
        get() = term + (if (phonetics.isNotEmpty()) " ($phonetics)" else "") + " = " + meaning
}

/**
 * The prompts and the answer formats for YouTube titles and descriptions.
 *
 * All are copies of the web app's (PurrfectBytesWeb/src/services/youtube_metadata_service.py):
 * when a prompt changes there, copy it here, so the two apps write the same kind of
 * description. Nothing here touches the network or Android.
 */
internal object YouTubeMetadataFormat {

    const val MAX_TITLE_LENGTH = 100

    /** Asks for every word and grammar point worth explaining, as a list to choose from. */
    private const val EXTRACTION_TEMPLATE = """You are a language-learning content assistant. Analyze the target sentence and extract every vocabulary item and grammar point a learner of that language might want explained. The user will review your list and untick unwanted entries, so LEAN INCLUSIVE: a missed item cannot be added back, but an extra item costs nothing. When unsure whether something is worth explaining, include it.

RULES:

Identify the language automatically. ALL meanings and explanations MUST be written in English.

Vocabulary items ("kind": "vocabulary"): content words, collocations, set phrases, and idioms. Give verbs and adjectives in their dictionary/base form, not the inflected form used in the sentence. Cover ALL word types - verbs, adjectives, adverbs, and adverbial phrases deserve entries just as much as nouns. Prefer meaningful multi-word chunks over splitting them into their parts: when a phrase's meaning or usage is not obvious from its individual words, list the whole phrase as one item.

Grammar points ("kind": "grammar"): purely grammatical machinery - particles, conjugations and verb forms, tense/aspect/mood markers, conditionals, sentence endings, connectors, agreement patterns, whatever the target language uses. Watch especially for patterns in which an ordinary noun, verb, or preposition carries an abstract structural meaning (equivalents of "in the course of", "as far as", "on the grounds that"): they look like plain vocabulary and are the easiest points to overlook.

When a content word is fused with a grammar pattern, list BOTH: the content word in its dictionary form as vocabulary, and the pattern as grammar. But if a word's whole role in the sentence is to build a pattern already listed as grammar (auxiliaries, light or helper verbs, helper adjectives, copulas), list only the pattern.

Never list the same item as both kinds.

List items in the order they appear in the sentence.

Provide accurate phonetics per language (Japanese always Hiragana - never Romaji, Korean Romanization, Chinese Pinyin, English IPA, etc.); use an empty string when not applicable.

OUTPUT FORMAT: Respond with a strict JSON array and NOTHING else - no prose, no markdown fences, no trailing commentary:

[{"kind": "vocabulary", "term": "original script", "phonetics": "phonetics", "meaning": "meaning or explanation in English"}, {"kind": "grammar", "term": "original script", "phonetics": "phonetics", "meaning": "explanation in English"}]

TARGET SENTENCE: {sentence}"""

    private const val TARGET_SENTENCE = "TARGET SENTENCE:"

    private const val CREDIT_HEADER = "📌 Credit:"

    /** The line that follows the credit in a description. */
    private const val AFTER_CREDIT = "👍"

    private const val PROMPT_TEMPLATE = """STRICT OPERATING MODE: Generate the requested content and immediately stop. Do not include any conversational filler, follow-up suggestions, or questions. Any text following the hashtags is a violation of this instruction.

You are a YouTube content creator helping generate titles and descriptions for language learning videos. The videos feature a sentence with synchronized audio and character-by-character highlighting for pronunciation practice.

IMPORTANT RULES:

ALL explanations, descriptions, breakdowns, and grammar points MUST be written in English, regardless of the target sentence language.

NEVER ask follow-up questions - generate the complete output immediately based on the given sentence.

BOLD FORMATTING RULE: Use SINGLE asterisks (*text*) for bold, never double asterisks. YouTube only renders bold when both asterisks are surrounded by whitespace or a line boundary — an asterisk touching any punctuation (e.g. "(*word*)") renders as a literal asterisk. Therefore NEVER place asterisks adjacent to parentheses, quotes, commas, or other punctuation. In breakdowns and grammar points, bold ONLY the original-script headword; leave the phonetics inside parentheses unformatted. Correct: *メッセージ* (めっせーじ) = Message. Wrong: メッセージ (*めっせーじ*) = Message. Always use ASCII parentheses ( ) with a space before the opening parenthesis — NEVER full-width parentheses （）, which would touch the asterisk and break the bold. In prose sections (intro, Study Tip, translation), NEVER bold a term that sits inside parentheses or quotes — write it there unformatted. Wrong: a simple connective (*word*). Correct: a simple connective (word), or restructure so the bolded term stands free with spaces on both sides.

Identify the language automatically.

CRITICAL: Keep the title under 100 characters (strict limit).

Provide accurate phonetics (if applicable: Japanese→Hiragana, Korean→Romanization, Chinese→Pinyin, etc.). For Japanese, ALWAYS use Hiragana for pronunciations instead of Romaji.

TRANSLATION RULE: If the target sentence is NOT English, you MUST include the "English Translation" section. If the target sentence IS English, you MUST DELETE the "English Translation" section entirely.

MANDATORY FORMATTING for Breakdowns/Grammar: You must start with the [Original Script], followed by the [Phonetics/Hiragana/Romanization/IPA] in parentheses, then the English meaning.

Example for English Breakdown: *Word* (IPA Phonetics) = English Meaning.

Break down the sentence (explanations in English). Be SELECTIVE, not exhaustive — this is not a word-by-word gloss. Include vocabulary, collocations, set phrases, and idioms that an intermediate learner of the target language would plausibly not know. Set the bar by frequency: exclude a candidate only when it ranks among roughly the 500 most frequent words of the target language (numbers, pronouns, greetings, everyday nouns, basic function words, the most elementary verbs). Everything less frequent than that belongs in the breakdown — including mid-frequency verbs, adverbs, and set adverbial phrases that an intermediate learner recognizes but could not confidently produce. Do not skip such an item just because its meaning looks plain once translated. Note that a multi-word phrase is judged as a unit: it can deserve an entry even when each word in it is common.

Give verbs and adjectives in their dictionary/base form, not the inflected form used in the sentence — the inflection itself belongs under Grammar Points.

COVER ALL WORD TYPES, not just the visually complex ones: verbs, adjectives, adverbs, and adverbial phrases deserve entries just as much as compound nouns and technical terms. A sentence whose breakdown contains only nouns has almost certainly missed something.

When a content word is fused with a grammar pattern, list BOTH: the content word in its dictionary form under Breakdown, and the pattern under Grammar Points. Explaining the pattern NEVER excuses omitting the word it attaches to. This applies only to words carrying independent lexical meaning — if a word's whole role in the sentence is to build a pattern already covered under Grammar Points (auxiliaries, light or helper verbs, helper adjectives, copulas), leave it out of Breakdown entirely.

Prefer meaningful multi-word chunks over splitting them into their parts: when a phrase's meaning or usage is not obvious from its individual words, list the whole phrase as one item rather than each word separately. List items in the order they appear in the sentence. Where useful, append a short nuance note after the meaning (register, formality, common pairings).

Highlight 2-4 key grammar points (explanations in English). Sort by type: purely grammatical machinery (particles, conjugations and verb forms, tense/aspect/mood markers, conditionals, sentence endings, connectors, agreement patterns — whatever the target language uses) goes under Grammar Points and NEVER under Breakdown; content vocabulary and lexical chunks go under Breakdown. Never print the same item in both sections — but a content word and a pattern attached to it are two different items, so covering the pattern does not remove the word from Breakdown. Do not drop an item just because it could fit either section. Watch especially for patterns in which an ordinary noun, verb, or preposition carries an abstract structural meaning (equivalents of "in the course of", "as far as", "on the grounds that"): they look like plain vocabulary and are the easiest points to overlook.

COVERAGE CHECK before you output: re-read the sentence from beginning to end and confirm that every word or phrase above beginner level appears in one of the two sections. Add anything you skipped.

Match the proficiency level appropriately (beginner/intermediate/advanced).

Use natural, encouraging tone.

Include relevant hashtags for the specific language.

Terminate the response immediately after the final hashtag. Do not include any text, sign-offs, or questions after the hashtags.

Given a target sentence, generate:

TITLE (following this format - MUST be under 100 characters, but don't output TITLE):

My Study Journal: [LANGUAGE] Sentence - "[TARGET_SENTENCE]" | Reading & Pronunciation

DESCRIPTION with these sections (don't output DESCRIPTION):

📚 Study Journal Entry

[Brief intro about learning this sentence today - MUST be in English]

📝 Today's Sentence:

[TARGET_SENTENCE in original language]

([Phonetics/Hiragana/Romanization/IPA if applicable])

📖 English Translation:[ONLY include this section if the target language is NOT English. If English, remove this entire section]

"[Translation in English]"

🔤 Breakdown:

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) = [Meaning in English]

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) = [Meaning in English]

📚 Grammar Points:

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) - [Explanation in English]

• *[Original Script]* ([Phonetics/Hiragana/Romanization/IPA]) - [Explanation in English]

🎯 Perfect for:

• [Proficiency level] learners

• [Learning goal 1]

• [Learning goal 2]

💡 Study Tip:

[Helpful context or usage note about this sentence - in English]

📌 Credit:

This sentence is sourced from another creator's content. All credit goes to the original author.

👍 Enjoyed this study session? Please give it a thumbs up!

🔔 Subscribe to follow my language learning journey and practice together!

☕ Want to support more learning content? Scan the QR code (bottom-left corner)—my cat thanks you! 😺

#[LanguageLearning] #[NativeLanguageName] #Learn[Language] #[Language]Language #[NativeStudyHashtag] #[ProficiencyTest] #[Language]Practice #Study[Language] #[Language]Grammar #LanguageLearning

Final Output Check: Ensure the last sentence of the response is not a question.

TARGET SENTENCE: {sentence}"""

    /**
     * The prompt for title and description. With [items], the two sections that explain
     * the sentence are held to exactly those; without, the model chooses by itself.
     */
    fun prompt(sentence: String, items: List<StudyItem>? = null): String {
        val template = if (items == null) {
            PROMPT_TEMPLATE
        } else {
            // Just before the sentence, so that it overrides the rules above it
            val sentenceAt = PROMPT_TEMPLATE.lastIndexOf(TARGET_SENTENCE)
            PROMPT_TEMPLATE.substring(0, sentenceAt) +
                itemsOverrideBlock(items) + "\n\n" +
                PROMPT_TEMPLATE.substring(sentenceAt)
        }
        return template.replace("{sentence}", sentence.trim())
    }

    fun extractionPrompt(sentence: String): String =
        EXTRACTION_TEMPLATE.replace("{sentence}", sentence.trim())

    /** Tells the model which items the user approved, and that no others may appear. */
    fun itemsOverrideBlock(items: List<StudyItem>): String {
        fun bullets(grammar: Boolean): String =
            items.filter { it.isGrammar == grammar }
                .joinToString("\n") { item ->
                    val phonetics = if (item.phonetics.isNotEmpty()) " (${item.phonetics})" else ""
                    "- ${item.term}$phonetics: ${item.meaning}"
                }
                .ifEmpty { "(none - output this section's header with no bullet items)" }

        return "APPROVED ITEMS OVERRIDE: The user has hand-picked the items below after " +
            "reviewing an extraction pass. The Breakdown section must contain exactly the " +
            "approved vocabulary items and the Grammar Points section exactly the approved " +
            "grammar items - every listed item, in the given order, with no additions and " +
            "no omissions. This overrides the selection rules above (frequency bar, " +
            "selectivity, the 2-4 grammar points guideline, and the coverage check). All " +
            "formatting rules still apply; you may polish the phonetics and the wording of " +
            "meanings and explanations, but never change which items appear.\n\n" +
            "Approved vocabulary items:\n" + bullets(grammar = false) + "\n\n" +
            "Approved grammar items:\n" + bullets(grammar = true)
    }

    /**
     * Reads the list of items out of the model's answer. Whatever surrounds the list -
     * a code fence, a friendly sentence - is ignored, and entries that are not usable
     * are skipped. Throws [MetadataException] when nothing usable is left.
     */
    fun parseItems(rawText: String): List<StudyItem> {
        val text = rawText.trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start == -1 || end <= start) {
            throw MetadataException("Could not extract items - the AI response had no item list")
        }

        val entries = try {
            JsonParser.parseString(text.substring(start, end + 1)).asJsonArray
        } catch (e: RuntimeException) {
            throw MetadataException("Could not extract items - the AI response was not valid JSON", e)
        }

        val items = entries.mapNotNull { entry ->
            if (!entry.isJsonObject) return@mapNotNull null
            val fields = entry.asJsonObject
            fun field(name: String): String =
                fields.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()

            val term = field("term")
            val meaning = field("meaning")
            if (term.isEmpty() || meaning.isEmpty()) return@mapNotNull null
            StudyItem(
                isGrammar = field("kind").lowercase() == "grammar",
                term = term,
                phonetics = field("phonetics"),
                meaning = meaning
            )
        }
        if (items.isEmpty()) {
            throw MetadataException("Could not extract items - try again or use a different provider")
        }
        return items
    }

    /**
     * Puts [credit] in place of the general credit in [description], word for word. The
     * model is not involved: the text between the credit's heading and the line after it
     * is replaced. A description without those two gets the credit as a section at its end.
     */
    fun applyCredit(description: String, credit: String): String {
        val header = description.indexOf(CREDIT_HEADER)
        if (header != -1) {
            val after = description.indexOf(AFTER_CREDIT, header)
            if (after != -1) {
                return description.substring(0, header + CREDIT_HEADER.length) +
                    "\n\n${credit.trim()}\n\n" +
                    description.substring(after)
            }
        }
        return "${description.trimEnd()}\n\n$CREDIT_HEADER\n\n${credit.trim()}"
    }

    private val HASHTAG = Regex("""#([\p{L}\p{N}_]+)""")

    /** YouTube takes at most this many characters of tags, separators included. */
    private const val MAX_TAGS_LENGTH = 450

    /** The hashtags of [description] as tags for the upload, each of them once. */
    fun tagsOf(description: String): List<String> {
        val tags = mutableListOf<String>()
        var length = 0
        for (tag in HASHTAG.findAll(description).map { it.groupValues[1] }.distinct()) {
            if (length + tag.length + 1 > MAX_TAGS_LENGTH) break
            tags += tag
            length += tag.length + 1
        }
        return tags
    }

    private val FULL_TITLE = Regex(
        """My Study Journal:\s*([a-zA-Z]+)\s+Sentence\s*-\s*["\u201c](.*?)["\u201d]\s*\|""",
        RegexOption.IGNORE_CASE
    )
    private val TITLE_LANGUAGE = Regex(
        """My Study Journal:\s*([a-zA-Z]+)\s+Sentence\s*-""",
        RegexOption.IGNORE_CASE
    )
    private val QUOTED = Regex("""["\u201c](.*?)["\u201d]""")
    private val BETWEEN_DASH_AND_BAR = Regex("""-\s*(.*?)\s*\|""")
    private val DESCRIPTION_LABEL = Regex("""^DESCRIPTION:\s*\n?""", RegexOption.IGNORE_CASE)

    /**
     * Splits the model's answer into title and description. The title is rebuilt from
     * its parts, which guarantees the format and the 100 character limit whatever the
     * model wrote.
     */
    fun parse(rawText: String, originalSentence: String): Pair<String, String> {
        val lines = rawText.trim().split("\n")

        // The first line with text is the title, the rest is the description
        val titleIndex = lines.indexOfFirst { it.isNotBlank() }
        val rawTitle = if (titleIndex >= 0) lines[titleIndex].trim() else ""
        val description = (if (titleIndex >= 0) lines.drop(titleIndex + 1) else emptyList())
            .joinToString("\n")
            .trim()
            .replace(DESCRIPTION_LABEL, "")
            .trim()

        var title = rawTitle
        if (title.startsWith("title:", ignoreCase = true)) {
            title = title.substring("title:".length).trim()
        }
        title = title.replace("**", "").replace("__", "").trim()

        val language: String?
        val sentence: String?
        val full = FULL_TITLE.find(title)
        if (full != null) {
            language = full.groupValues[1].trim()
            sentence = full.groupValues[2].trim()
        } else {
            language = TITLE_LANGUAGE.find(title)?.groupValues?.get(1)?.trim()

            // The last pair of quotes, so that an apostrophe such as "I'll" does no harm
            val quoted = QUOTED.findAll(title).lastOrNull()?.groupValues?.get(1)?.trim()
            sentence = if (!quoted.isNullOrEmpty()) {
                quoted
            } else {
                BETWEEN_DASH_AND_BAR.find(title)?.groupValues?.get(1)
                    ?.trim()?.trim('"', '\u201c', '\u201d')?.trim()
            }
        }

        if (language.isNullOrEmpty() && sentence.isNullOrEmpty()) {
            // Nothing recognisable: use the line as it is
            return limited(title) to description
        }

        val prefix = "My Study Journal: ${language.orEmpty().ifEmpty { "Language" }} Sentence - \""
        val suffix = "\" | Reading & Pronunciation"
        val shown = sentence.orEmpty().ifEmpty { wholeCharacters(originalSentence, 50) }

        val finalTitle = if (prefix.length + shown.length + suffix.length > MAX_TITLE_LENGTH) {
            val room = MAX_TITLE_LENGTH - prefix.length - suffix.length - 3
            if (room > 0) {
                prefix + wholeCharacters(shown, room).trim() + "..." + suffix
            } else {
                limited(prefix + shown + suffix)
            }
        } else {
            prefix + shown + suffix
        }
        return finalTitle to description
    }

    private fun limited(title: String): String =
        if (title.length <= MAX_TITLE_LENGTH) title else wholeCharacters(title, MAX_TITLE_LENGTH - 3) + "..."

    /** The first [length] code units of [text], without cutting a character in half. */
    private fun wholeCharacters(text: String, length: Int): String {
        if (text.length <= length) return text
        val cut = if (length > 0 && Character.isHighSurrogate(text[length - 1])) length - 1 else length
        return text.substring(0, cut)
    }
}
