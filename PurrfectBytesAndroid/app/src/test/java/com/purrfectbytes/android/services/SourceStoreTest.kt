package com.purrfectbytes.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "saved_sources.json") }

    // 28 September 2026, 08:34:00 where the phone is
    private val now = 1_790_584_440_000L - TimeZone.getDefault().getOffset(1_790_584_440_000L)

    private fun store() = SourceStore(file, clock = { now })

    @Test
    fun `a saved source is there when the app is started again`() {
        val saved = store().add("Shin Kanzen Master N1", "From 新完全マスター N1 by 3A Corporation.")

        assertEquals(listOf(saved), store().list())
        assertEquals(saved, store().find(saved.id))
        assertEquals("Shin Kanzen Master N1", saved.name)
        assertEquals("From 新完全マスター N1 by 3A Corporation.", saved.credit)
        assertEquals("2026-09-28T08:34:00", saved.createdAt)
        assertTrue(saved.id, Regex("[0-9a-f]{8}").matches(saved.id))
    }

    @Test
    fun `sources are listed in the order they were saved`() {
        val store = store()
        val names = listOf("Textbook", "Drama", "Podcast")

        names.forEach { store.add(it, "From $it.") }

        assertEquals(names, store.list().map { it.name })
        assertEquals(3, store.list().map { it.id }.distinct().size)
    }

    @Test
    fun `spaces around name and credit are not kept`() {
        val saved = store().add("  Textbook \n", "\n From my textbook.  ")

        assertEquals("Textbook", saved.name)
        assertEquals("From my textbook.", saved.credit)
    }

    @Test
    fun `a source needs a name and a credit`() {
        val store = store()

        assertEquals(
            "Source name is required",
            assertThrows(SourceException::class.java) { store.add("  ", "From my textbook.") }.message
        )
        assertEquals(
            "Credit line is required",
            assertThrows(SourceException::class.java) { store.add("Textbook", "\n") }.message
        )
        assertEquals(emptyList<TextSource>(), store.list())
        assertFalse(file.exists())
    }

    @Test
    fun `a source can be deleted`() {
        val store = store()
        val textbook = store.add("Textbook", "From my textbook.")
        val drama = store.add("Drama", "From a drama.")

        assertTrue(store.delete(textbook.id))

        assertEquals(listOf(drama), store.list())
        assertNull(store.find(textbook.id))
        assertFalse("it is gone already", store.delete(textbook.id))
        assertFalse(store.delete("unknown"))
    }

    // What the web app (source_store.py) writes
    private val fileOfTheWebApp = """[
  {
    "id": "3f9a1c2e",
    "name": "Shin Kanzen Master N1",
    "credit": "This sentence comes from the textbook 新完全マスター N1 by 3A Corporation.",
    "created_at": "2026-09-27T21:15:02"
  },
  {
    "id": "b7d40a19",
    "name": "Korean drama \"Winter\"",
    "credit": "From the drama \"Winter\" - all rights with its makers.",
    "created_at": "2026-09-28T07:03:44"
  }
]"""

    @Test
    fun `the file of the web app is read`() {
        file.writeText(fileOfTheWebApp)

        assertEquals(
            listOf(
                TextSource(
                    "3f9a1c2e",
                    "Shin Kanzen Master N1",
                    "This sentence comes from the textbook 新完全マスター N1 by 3A Corporation.",
                    "2026-09-27T21:15:02"
                ),
                TextSource(
                    "b7d40a19",
                    "Korean drama \"Winter\"",
                    "From the drama \"Winter\" - all rights with its makers.",
                    "2026-09-28T07:03:44"
                )
            ),
            store().list()
        )
    }

    @Test
    fun `the file is written the way the web app writes it`() {
        file.writeText(fileOfTheWebApp)
        val store = store()

        // Writes the file anew, with the sources it read
        val added = store.add("Textbook", "From <my> textbook & more.")
        store.delete(added.id)

        assertEquals(fileOfTheWebApp, file.readText())
    }

    @Test
    fun `a file that cannot be read holds no sources, and the next source replaces it`() {
        listOf("not json at all", "{\"id\": \"3f9a1c2e\"}", "[{\"id\": \"3f9a1c2e\", \"name\": \"Half", "").forEach { text ->
            file.writeText(text)
            assertEquals("\"$text\"", emptyList<TextSource>(), store().list())
        }

        store().add("Textbook", "From my textbook.")
        assertEquals(listOf("Textbook"), store().list().map { it.name })
    }

    @Test
    fun `entries that are no sources are left out`() {
        file.writeText(
            """[42, "text", null, {"id": "", "name": "No id", "credit": "c"},
                {"id": "1", "credit": "No name"}, {"id": "2", "name": "No credit", "credit": ""},
                {"id": "3", "name": ["a list"], "credit": "c"},
                {"id": "4", "name": "Textbook", "credit": "From my textbook."}]"""
        )

        assertEquals(listOf(TextSource("4", "Textbook", "From my textbook.", "")), store().list())
    }
}
