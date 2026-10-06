package com.purrfectbytes.android.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private var errorsDismissed = 0
    private var successesDismissed = 0

    private fun show(error: String? = null, success: String? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MessageBanner(
                errorMessage = error,
                successMessage = success,
                onDismissError = { errorsDismissed++ },
                onDismissSuccess = { successesDismissed++ }
            )
        }
    }

    @Test
    fun `an error stays until it is dismissed`() {
        show(error = "Failed to encode video.")

        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText("❌ Failed to encode video.").assertIsDisplayed()
        assertEquals(0, errorsDismissed)

        compose.onNodeWithContentDescription("Dismiss error").performClick()
        assertEquals(1, errorsDismissed)
    }

    @Test
    fun `a success message leaves by itself`() {
        show(success = "Video generated successfully!")

        compose.onNodeWithText("✅ Video generated successfully!").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(0, successesDismissed)

        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(1, successesDismissed)
    }

    @Test
    fun `a success message does not take the error with it`() {
        show(error = "The playlist could not be read", success = "Text added to input field")

        compose.mainClock.advanceTimeBy(10_000)

        assertEquals(1, successesDismissed)
        assertEquals(0, errorsDismissed)
        compose.onNodeWithText("❌ The playlist could not be read").assertIsDisplayed()
    }
}
