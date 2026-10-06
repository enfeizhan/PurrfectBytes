package com.purrfectbytes.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class YouTubeMetadataFormatTest {

    private class Case(val answer: String, val sentence: String, val title: String, val description: String)

    // What the web app's parser (youtube_metadata_service.py, _parse_response) makes of
    // the same answers. The Android parser must agree with it.
    private val cases = listOf(
        Case(
            answer = "My Study Journal: Japanese Sentence - \"今日は天気がいいですね\" | Reading & Pronunciation\n\n📚 Study Journal Entry\n\nToday I learned this.\n\n#Japanese",
            sentence = "今日は天気がいいですね",
            title = "My Study Journal: Japanese Sentence - \"今日は天気がいいですね\" | Reading & Pronunciation",
            description = "📚 Study Journal Entry\n\nToday I learned this.\n\n#Japanese"
        ),
        Case(
            answer = "TITLE: My Study Journal: English Sentence - \"I'll tee up the next one\" | Reading & Pronunciation\nDESCRIPTION:\nBody line 1\nBody line 2",
            sentence = "I'll tee up the next one",
            title = "My Study Journal: English Sentence - \"I'll tee up the next one\" | Reading & Pronunciation",
            description = "Body line 1\nBody line 2"
        ),
        Case(
            answer = "**My Study Journal: Korean Sentence - \"안녕하세요\" | Reading & Pronunciation**\n\nDesc",
            sentence = "안녕하세요",
            title = "My Study Journal: Korean Sentence - \"안녕하세요\" | Reading & Pronunciation",
            description = "Desc"
        ),
        Case(
            answer = "My Study Journal: French Sentence - “Bonjour tout le monde” | Reading & Pronunciation\nDesc here",
            sentence = "Bonjour tout le monde",
            title = "My Study Journal: French Sentence - \"Bonjour tout le monde\" | Reading & Pronunciation",
            description = "Desc here"
        ),
        Case(
            answer = "My Study Journal: German Sentence - \"Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort \" | Reading & Pronunciation\nLong one",
            sentence = "Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort Wort ",
            title = "My Study Journal: German Sentence - \"Wort Wort Wort Wort Wort Wort Wor...\" | Reading & Pronunciation",
            description = "Long one"
        ),
        Case(
            answer = "Just a plain first line with no format\nand a description",
            sentence = "whatever",
            title = "Just a plain first line with no format",
            description = "and a description"
        ),
        Case(
            answer = "\n\n   \nMy Study Journal: Mandarin Chinese Sentence - \"你好\" | Reading & Pronunciation\nDesc",
            sentence = "你好",
            title = "My Study Journal: Language Sentence - \"你好\" | Reading & Pronunciation",
            description = "Desc"
        ),
        Case(
            answer = "My Study Journal: Spanish Sentence - Hola mundo | Reading & Pronunciation\nDesc",
            sentence = "Hola mundo",
            title = "My Study Journal: Spanish Sentence - \"Hola mundo\" | Reading & Pronunciation",
            description = "Desc"
        ),
        Case(
            answer = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\nDesc",
            sentence = "s",
            title = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx...",
            description = "Desc"
        ),
        Case(
            answer = "Only a title line",
            sentence = "s",
            title = "Only a title line",
            description = ""
        ),
        Case(
            answer = "",
            sentence = "s",
            title = "",
            description = ""
        ),
        Case(
            answer = "title: My Study Journal: Italian Sentence - \"Ciao\" | Reading\ndescription:   \nReal body",
            sentence = "Ciao",
            title = "My Study Journal: Italian Sentence - \"Ciao\" | Reading & Pronunciation",
            description = "Real body"
        )
    )

    @Test
    fun `answers are split the way the web app splits them`() {
        cases.forEachIndexed { index, case ->
            val (title, description) = YouTubeMetadataFormat.parse(case.answer, case.sentence)
            assertEquals("title of case $index", case.title, title)
            assertEquals("description of case $index", case.description, description)
        }
    }

    @Test
    fun `a title never exceeds the limit of YouTube`() {
        val long = "とても長い文章です。".repeat(30)
        val answers = cases.map { it.answer } + listOf(
            "My Study Journal: Japanese Sentence - \"$long\" | Reading & Pronunciation\nBody",
            "My Study Journal: ${"A".repeat(120)} Sentence - \"x\" | Reading & Pronunciation\nBody",
            "😀".repeat(80) + "\nBody"
        )
        answers.forEach { answer ->
            val (title, _) = YouTubeMetadataFormat.parse(answer, long)
            assertTrue("\"$title\" has ${title.length} characters", title.length <= YouTubeMetadataFormat.MAX_TITLE_LENGTH)
        }
    }

    @Test
    fun `a shortened title does not end in half a character`() {
        // Every emoji takes two code units, so a cut at an odd position would split one
        val (title, _) = YouTubeMetadataFormat.parse("a" + "😀".repeat(80) + "\nBody", "s")
        assertTrue(title.endsWith("..."))
        assertFalse(Character.isHighSurrogate(title[title.length - 4]))
    }

    @Test
    fun `the sentence is put into the prompt`() {
        val prompt = YouTubeMetadataFormat.prompt("吾輩は猫である")
        assertTrue(prompt.endsWith("TARGET SENTENCE: 吾輩は猫である"))
        assertFalse(prompt.contains("{sentence}"))
    }

    @Test
    fun `the prompt asks for bold with single asterisks`() {
        // The asterisks got lost once, which left the rule without its example
        val prompt = YouTubeMetadataFormat.prompt("x")
        assertTrue(prompt.contains("Use SINGLE asterisks (*text*) for bold"))
        assertTrue(prompt.contains("ALWAYS use Hiragana"))
    }

    // ------------------------------------------------- the web app's prompts

    /**
     * The web app's prompts, read from its source when it lies next to this project.
     * Braces are doubled there, where Python would otherwise take them for a gap to fill.
     */
    private fun webTemplate(name: String): String? {
        val source = File("../../PurrfectBytesWeb/src/services/youtube_metadata_service.py")
        if (!source.isFile) return null
        val text = source.readText()
        val start = text.indexOf("$name = \"\"\"")
        if (start == -1) return null
        val from = start + name.length + " = \"\"\"".length
        return text.substring(from, text.indexOf("\"\"\"", from))
    }

    private val changed = "The web app's prompt has changed. Copy it to YouTubeMetadataFormat.kt, " +
        "so that both apps write the same kind of description."

    @Test
    fun `the prompt is the one of the web app`() {
        val template = webTemplate("YOUTUBE_PROMPT_TEMPLATE")
        assumeTrue("the web app is not next to this project", template != null)

        assertEquals(changed, template!!.replace("{sentence}", "猫です"), YouTubeMetadataFormat.prompt("猫です"))
    }

    @Test
    fun `the prompt that asks for the items is the one of the web app`() {
        val template = webTemplate("EXTRACTION_PROMPT_TEMPLATE")
        assumeTrue("the web app is not next to this project", template != null)

        assertEquals(
            changed,
            template!!.replace("{{", "{").replace("}}", "}").replace("{sentence}", "猫です"),
            YouTubeMetadataFormat.extractionPrompt("猫です")
        )
    }

    @Test
    fun `the prompt for the items asks for a list and names the sentence`() {
        val prompt = YouTubeMetadataFormat.extractionPrompt("  吾輩は猫である \n")

        assertTrue(prompt.endsWith("TARGET SENTENCE: 吾輩は猫である"))
        assertTrue(prompt.contains("""[{"kind": "vocabulary", "term": "original script""""))
        assertTrue(prompt.contains("LEAN INCLUSIVE"))
        assertFalse(prompt.contains("{sentence}"))
    }

    // -------------------------------------------------------- approved items

    private val approved = listOf(
        StudyItem(isGrammar = false, term = "吾輩", phonetics = "わがはい", meaning = "I (archaic, pompous)"),
        StudyItem(isGrammar = false, term = "名前", phonetics = "なまえ", meaning = "name"),
        StudyItem(isGrammar = true, term = "である", phonetics = "", meaning = "formal copula")
    )

    // What the web app (_items_override_block) writes for the same items
    private val rule = "APPROVED ITEMS OVERRIDE: The user has hand-picked the items below after reviewing an " +
        "extraction pass. The Breakdown section must contain exactly the approved vocabulary items and " +
        "the Grammar Points section exactly the approved grammar items - every listed item, in the given " +
        "order, with no additions and no omissions. This overrides the selection rules above (frequency " +
        "bar, selectivity, the 2-4 grammar points guideline, and the coverage check). All formatting " +
        "rules still apply; you may polish the phonetics and the wording of meanings and explanations, " +
        "but never change which items appear."
    private val none = "(none - output this section's header with no bullet items)"

    @Test
    fun `approved items are listed the way the web app lists them`() {
        assertEquals(
            "$rule\n\nApproved vocabulary items:\n- 吾輩 (わがはい): I (archaic, pompous)\n- 名前 (なまえ): name" +
                "\n\nApproved grammar items:\n- である: formal copula",
            YouTubeMetadataFormat.itemsOverrideBlock(approved)
        )
        assertEquals(
            "$rule\n\nApproved vocabulary items:\n$none\n\nApproved grammar items:\n- である: formal copula",
            YouTubeMetadataFormat.itemsOverrideBlock(approved.takeLast(1))
        )
        assertEquals(
            "$rule\n\nApproved vocabulary items:\n$none\n\nApproved grammar items:\n$none",
            YouTubeMetadataFormat.itemsOverrideBlock(emptyList())
        )
    }

    @Test
    fun `approved items stand just before the sentence`() {
        val plain = YouTubeMetadataFormat.prompt("吾輩は猫である。名前はまだ無い。")
        val withItems = YouTubeMetadataFormat.prompt("吾輩は猫である。名前はまだ無い。", approved)

        val ending = "\n\nTARGET SENTENCE: 吾輩は猫である。名前はまだ無い。"
        assertTrue(plain.endsWith(ending))
        assertEquals(
            plain.removeSuffix(ending.trimStart()) + YouTubeMetadataFormat.itemsOverrideBlock(approved) + ending,
            withItems
        )
        assertEquals("the prompt without items is as it was", plain, YouTubeMetadataFormat.prompt("吾輩は猫である。名前はまだ無い。", null))
    }

    @Test
    fun `no item approved is not the same as no review`() {
        val prompt = YouTubeMetadataFormat.prompt("猫です", emptyList())

        assertTrue(prompt.contains("APPROVED ITEMS OVERRIDE"))
        assertFalse(YouTubeMetadataFormat.prompt("猫です").contains("APPROVED ITEMS OVERRIDE"))
    }

    @Test
    fun `an item is shown with its reading and its meaning`() {
        assertEquals("吾輩 (わがはい) = I (archaic, pompous)", approved[0].label)
        assertEquals("である = formal copula", approved[2].label)
    }

    // ------------------------------------------------------- the list of items

    private fun failureOf(block: () -> Unit): MetadataException =
        try {
            block()
            fail("expected a MetadataException")
            throw IllegalStateException()
        } catch (e: MetadataException) {
            e
        }

    @Test
    fun `the list is found inside whatever surrounds it`() {
        val answer = """Here are the items you asked for:
```json
[
  {"kind": "vocabulary", "term": "元気", "phonetics": "げんき", "meaning": "healthy; well"},
  {"kind": "grammar", "term": "ですか", "phonetics": "", "meaning": "polite question ending"}
]
```
Let me know if you need anything else!"""

        assertEquals(
            listOf(
                StudyItem(isGrammar = false, term = "元気", phonetics = "げんき", meaning = "healthy; well"),
                StudyItem(isGrammar = true, term = "ですか", phonetics = "", meaning = "polite question ending")
            ),
            YouTubeMetadataFormat.parseItems(answer)
        )
    }

    // What the web app (_parse_items_response) makes of the same entries
    @Test
    fun `entries are tidied the way the web app tidies them`() {
        val items = YouTubeMetadataFormat.parseItems(
            """[
              {"kind": "GRAMMAR", "term": " は ", "phonetics": null, "meaning": " topic marker "},
              {"kind": " Grammar ", "term": "が", "meaning": "subject marker"},
              {"kind": "idiom", "term": "猫の手", "phonetics": "ねこのて", "meaning": "a helping hand"},
              {"term": "四十二", "phonetics": 42, "meaning": 7.5}
            ]"""
        )

        assertEquals(
            listOf(
                StudyItem(isGrammar = true, term = "は", phonetics = "", meaning = "topic marker"),
                StudyItem(isGrammar = true, term = "が", phonetics = "", meaning = "subject marker"),
                StudyItem(isGrammar = false, term = "猫の手", phonetics = "ねこのて", meaning = "a helping hand"),
                StudyItem(isGrammar = false, term = "四十二", phonetics = "42", meaning = "7.5")
            ),
            items
        )
    }

    @Test
    fun `a value that is a list or an object is taken as missing`() {
        val items = YouTubeMetadataFormat.parseItems(
            """[{"kind": ["grammar"], "term": "も", "phonetics": {"a": 1}, "meaning": "also"},
                {"term": ["猫"], "meaning": "cat"}]"""
        )

        assertEquals(listOf(StudyItem(isGrammar = false, term = "も", phonetics = "", meaning = "also")), items)
    }

    @Test
    fun `entries that cannot be used are left out`() {
        val items = YouTubeMetadataFormat.parseItems(
            """[{"term": "", "meaning": "nothing"}, 42, "text", null, ["list"],
                {"term": "猫"}, {"meaning": "cat"}, {"term": "猫", "meaning": "cat"}]"""
        )

        assertEquals(listOf(StudyItem(isGrammar = false, term = "猫", phonetics = "", meaning = "cat")), items)
    }

    @Test
    fun `a list inside an object is found as well`() {
        val items = YouTubeMetadataFormat.parseItems("""{"items": [{"term": "猫", "meaning": "cat"}]}""")

        assertEquals("猫", items.single().term)
    }

    @Test
    fun `an answer without a list is a failure`() {
        listOf("Sorry, I can't do that.", "][", "", "[{\"term\": \"猫\", \"meaning\": \"cat\"}").forEach { answer ->
            assertEquals(
                "\"$answer\"",
                "Could not extract items - the AI response had no item list",
                failureOf { YouTubeMetadataFormat.parseItems(answer) }.message
            )
        }
    }

    @Test
    fun `a list that cannot be read is a failure`() {
        assertEquals(
            "Could not extract items - the AI response was not valid JSON",
            failureOf { YouTubeMetadataFormat.parseItems("""[{"term": "猫" "meaning": "cat"}]""") }.message
        )
    }

    @Test
    fun `a list without usable entries is a failure`() {
        assertEquals(
            "Could not extract items - try again or use a different provider",
            failureOf { YouTubeMetadataFormat.parseItems("""[{"term": ""}, 42]""") }.message
        )
        assertEquals(
            "Could not extract items - try again or use a different provider",
            failureOf { YouTubeMetadataFormat.parseItems("[]") }.message
        )
    }

    // ------------------------------------------------------------ the credit

    private val described = "📚 Study Journal Entry\n\nToday's sentence.\n\n📌 Credit:\n\n" +
        "This sentence is sourced from another creator's content. All credit goes to the original author." +
        "\n\n👍 If this helped you, please like and subscribe!\n\n#Japanese #JLPT"

    // What the web app (_apply_credit) makes of the same descriptions
    @Test
    fun `the credit of a saved source takes the place of the general one`() {
        assertEquals(
            "📚 Study Journal Entry\n\nToday's sentence.\n\n📌 Credit:\n\n" +
                "From 新完全マスター N1 by 3A Corporation." +
                "\n\n👍 If this helped you, please like and subscribe!\n\n#Japanese #JLPT",
            YouTubeMetadataFormat.applyCredit(described, "  From 新完全マスター N1 by 3A Corporation.\n")
        )
    }

    @Test
    fun `the credit is put in word for word`() {
        val credit = """Costs ${'$'}1 \ "quoted" \1 ${'$'}{x} 👍"""

        val result = YouTubeMetadataFormat.applyCredit(described, credit)

        assertTrue(result.contains("📌 Credit:\n\n$credit\n\n👍 If this helped you"))
    }

    @Test
    fun `a description without a place for the credit gets it at its end`() {
        assertEquals(
            "Just a description\n\n📌 Credit:\n\nFrom my textbook.",
            YouTubeMetadataFormat.applyCredit("Just a description\n\n", "From my textbook.")
        )
        // The heading is there, but not the line that follows the credit
        assertEquals(
            "Intro\n\n📌 Credit:\n\nGeneral credit.\n\n📌 Credit:\n\nFrom my textbook.",
            YouTubeMetadataFormat.applyCredit("Intro\n\n📌 Credit:\n\nGeneral credit.", "From my textbook.")
        )
    }

    // --------------------------------------------------------------- the tags

    @Test
    fun `the hashtags of the description are the tags of the upload`() {
        assertEquals(
            listOf("Japanese", "JLPT_N1", "日本語", "한국어", "Study2026"),
            YouTubeMetadataFormat.tagsOf("Intro #Japanese #JLPT_N1\n#日本語, #한국어! and #Study2026.")
        )
    }

    @Test
    fun `every tag is sent once and a lone sign is none`() {
        assertEquals(
            listOf("Japanese", "japanese"),
            YouTubeMetadataFormat.tagsOf("#Japanese #japanese #Japanese # #-x")
        )
        assertEquals(emptyList<String>(), YouTubeMetadataFormat.tagsOf("No tags here"))
    }

    @Test
    fun `tags stop where YouTube stops taking them`() {
        val description = (1..100).joinToString(" ") { "#LanguageLearning$it" }

        val tags = YouTubeMetadataFormat.tagsOf(description)

        assertTrue(tags.size in 1..99)
        assertTrue(tags.sumOf { it.length + 1 } <= 450)
        assertEquals("LanguageLearning1", tags.first())
    }
}
