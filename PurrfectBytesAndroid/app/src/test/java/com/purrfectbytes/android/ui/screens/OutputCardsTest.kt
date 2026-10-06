package com.purrfectbytes.android.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.purrfectbytes.android.services.RecentVideo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutputCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    // 28 September 2026, 14:05 where the phone is
    private val afternoon = 1_790_604_300_000L - TimeZone.getDefault().getOffset(1_790_604_300_000L)

    private val conversation = RecentVideo(
        file = File("/cache/videos/output_2.mp4"),
        text = "직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요.",
        summary = "Conversation of 2 lines, 3 repetitions",
        seconds = 65.4,
        createdAt = afternoon
    )
    private val sentence = RecentVideo(
        file = File("/cache/videos/output_1.mp4"),
        text = "吾輩は猫である。",
        summary = "",
        seconds = 3.2,
        createdAt = afternoon - 3_600_000,
        uploadedUrl = "https://www.youtube.com/watch?v=abc123"
    )

    private fun show(selected: File?) {
        compose.setContent {
            RecentVideosCard(
                videos = listOf(conversation, sentence),
                selected = selected,
                onSelect = { calls += "select ${it.name}" },
                onDelete = { calls += "delete ${it.name}" }
            )
        }
    }

    @Test
    fun `a video is told by its text, what it repeats, its length and its time`() {
        show(selected = null)

        compose.onNodeWithText("📁 Recent Videos").assertIsDisplayed()
        compose.onNodeWithText("직원: 혼자 오셨어요?").assertIsDisplayed()
        compose.onNodeWithText("Conversation of 2 lines, 3 repetitions · 1:05 · 28 Sep 14:05").assertIsDisplayed()
        compose.onNodeWithText("吾輩は猫である。").assertIsDisplayed()
        compose.onNodeWithText("0:03 · 28 Sep 13:05").assertIsDisplayed()
        compose.onAllNodesWithText("✅ on YouTube").assertCountEquals(1)
    }

    @Test
    fun `any video but the one that is selected can be taken`() {
        show(selected = conversation.file)

        compose.onNodeWithText("Selected").assertIsDisplayed()
        compose.onAllNodesWithText("Use").assertCountEquals(1)
        compose.onNodeWithText("Use").performClick()

        assertEquals(listOf("select output_1.mp4"), calls)
    }

    @Test
    fun `every video can be deleted, the selected one as well`() {
        show(selected = conversation.file)

        compose.onNodeWithContentDescription("Delete the video of 직원: 혼자 오셨어요?").performClick()
        compose.onNodeWithContentDescription("Delete the video of 吾輩は猫である。").performClick()

        assertEquals(listOf("delete output_2.mp4", "delete output_1.mp4"), calls)
    }

    @Test
    fun `the length of a video is given in minutes and seconds`() {
        assertEquals("0:00", playingTime(0.4))
        assertEquals("0:59", playingTime(59.9))
        assertEquals("1:05", playingTime(65.4))
        assertEquals("12:00", playingTime(720.0))
    }

    @Test
    fun `a video says what it repeats and how long it plays`() {
        compose.setContent {
            VideoCard(video = conversation.file, summary = "10 repetitions", seconds = 36.0, onDismiss = { calls += "dismiss" })
        }

        compose.onNodeWithText("10 repetitions · 0:36").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss video").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `a single reading of unknown length just asks to be played`() {
        compose.setContent {
            VideoCard(video = conversation.file, summary = "", seconds = 0.0, onDismiss = {})
        }

        compose.onNodeWithText("Tap Play to watch").assertIsDisplayed()
    }

    @Test
    fun `the audio of a conversation is in as many pieces as it has lines`() {
        compose.setContent {
            AudioCard(onPlay = { calls += "play" }, onStop = { calls += "stop" }, lines = 3)
        }

        compose.onNodeWithText("One reading of the conversation, 3 lines").assertIsDisplayed()
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("play", "stop"), calls)
    }
}
