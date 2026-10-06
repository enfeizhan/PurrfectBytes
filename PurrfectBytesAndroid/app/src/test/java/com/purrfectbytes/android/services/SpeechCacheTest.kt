package com.purrfectbytes.android.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_790_553_600_000L

    private val store by lazy { File(folder.root, "tts_cache") }

    private fun cache(maxEntries: Int = 10) = SpeechCache(store, maxEntries, clock = { now })

    private val words = listOf(WordBoundary("Hello", 0.1, 0.6), WordBoundary("wörld 猫", 0.7, 1.4))

    private fun speech(vararg bytes: Byte, words: List<WordBoundary> = this.words): SpeechAudio {
        val file = folder.newFile()
        file.writeBytes(bytes)
        return SpeechAudio(file, words)
    }

    private fun destination() = File(folder.root, "speech/tts_${System.nanoTime()}.mp3")

    private fun keyOf(text: String) = cache().key(text, "en", "edge", "en-US-AriaNeural", "+0%")

    @Test
    fun `speech that was stored is found again, with the times of its words`() {
        val cache = cache()
        cache.store(keyOf("Hello"), speech(1, 2, 3))

        val found = cache.fetch(keyOf("Hello"), destination())

        assertNotNull(found)
        assertArrayEquals(byteArrayOf(1, 2, 3), found!!.file.readBytes())
        assertEquals(words, found.wordBoundaries)
    }

    @Test
    fun `speech without word times is stored as well`() {
        val cache = cache()
        cache.store(keyOf("Hello"), speech(1, 2, 3, words = emptyList()))

        assertEquals(emptyList<WordBoundary>(), cache.fetch(keyOf("Hello"), destination())!!.wordBoundaries)
    }

    @Test
    fun `what is found is a copy that can be deleted`() {
        val cache = cache()
        val original = speech(1, 2, 3)
        cache.store(keyOf("Hello"), original)
        original.file.delete()

        val first = cache.fetch(keyOf("Hello"), destination())!!
        first.file.delete()
        val second = cache.fetch(keyOf("Hello"), destination())!!

        assertNotEquals(first.file, second.file)
        assertArrayEquals(byteArrayOf(1, 2, 3), second.file.readBytes())
    }

    @Test
    fun `what was not stored is not found, and leaves no file`() {
        val destination = destination()

        assertNull(cache().fetch(keyOf("Hello"), destination))

        assertFalse(destination.exists())
        assertFalse(destination.parentFile!!.exists())
    }

    @Test
    fun `everything that changes the sound changes the key`() {
        val cache = cache()
        val keys = listOf(
            cache.key("Hello", "en", "edge", "en-US-AriaNeural", "+0%"),
            cache.key("Hello.", "en", "edge", "en-US-AriaNeural", "+0%"),
            cache.key("Hello", "fr", "edge", "en-US-AriaNeural", "+0%"),
            cache.key("Hello", "en", "native", "en-US-AriaNeural", "+0%"),
            cache.key("Hello", "en", "edge", "en-US-GuyNeural", "+0%"),
            cache.key("Hello", "en", "edge", "en-US-AriaNeural", "-30%"),
            // What belongs to one part must not be taken for the beginning of the next
            cache.key("Hello", "en", "edge", "en-US-AriaNeural+", "0%")
        )

        assertEquals(keys.size, keys.distinct().size)
        assertEquals(keys[0], cache.key("Hello", "en", "edge", "en-US-AriaNeural", "+0%"))
        assertTrue(keys.all { Regex("[0-9a-f]{64}").matches(it) })
    }

    @Test
    fun `an entry without its details is none`() {
        val cache = cache()
        cache.store(keyOf("Hello"), speech(1, 2, 3))
        File(store, keyOf("Hello") + ".json").delete()

        assertNull(cache.fetch(keyOf("Hello"), destination()))
    }

    @Test
    fun `details that cannot be read are no entry`() {
        val cache = cache()
        cache.store(keyOf("Hello"), speech(1, 2, 3))
        File(store, keyOf("Hello") + ".json").writeText("{\"words\": [{\"word\": \"Hel")

        assertNull(cache.fetch(keyOf("Hello"), destination()))
    }

    @Test
    fun `an entry without its audio is none, and leaves no file`() {
        val cache = cache()
        cache.store(keyOf("Hello"), speech(1, 2, 3))
        File(store, keyOf("Hello") + ".mp3").delete()
        val destination = destination()

        assertNull(cache.fetch(keyOf("Hello"), destination))
        assertFalse(destination.exists())
    }

    @Test
    fun `speech is stored again over what was there`() {
        val cache = cache()
        cache.store(keyOf("Hello"), speech(1, 2, 3))
        cache.store(keyOf("Hello"), speech(4, 5, words = emptyList()))

        val found = cache.fetch(keyOf("Hello"), destination())!!

        assertArrayEquals(byteArrayOf(4, 5), found.file.readBytes())
        assertEquals(emptyList<WordBoundary>(), found.wordBoundaries)
    }

    @Test
    fun `the entries used longest ago make room for new ones`() {
        val cache = cache(maxEntries = 3)
        listOf("one", "two", "three").forEach { text ->
            now += 1000
            cache.store(keyOf(text), speech(1))
        }
        // "one" is used again, which makes "two" the oldest
        now += 1000
        assertNotNull(cache.fetch(keyOf("one"), destination()))

        now += 1000
        cache.store(keyOf("four"), speech(1))

        assertNull(cache.fetch(keyOf("two"), destination()))
        listOf("one", "three", "four").forEach { text ->
            assertNotNull("\"$text\" is gone", cache.fetch(keyOf(text), destination()))
        }
        assertEquals(6, store.listFiles()!!.size)
    }

    @Test
    fun `nothing is left of what was cleared`() {
        val cache = cache()
        cache.store(keyOf("one"), speech(1))
        cache.store(keyOf("two"), speech(2))

        assertEquals(2, cache.clear())

        assertEquals(emptyList<File>(), store.listFiles()!!.toList())
        assertEquals(0, cache.clear())
    }

    @Test
    fun `speech that cannot be stored does no harm`() {
        val cache = cache()
        val missing = SpeechAudio(File(folder.root, "nowhere.mp3"), words)

        cache.store(keyOf("Hello"), missing)

        assertNull(cache.fetch(keyOf("Hello"), destination()))
        assertEquals(emptyList<File>(), store.listFiles().orEmpty().toList())
    }
}
