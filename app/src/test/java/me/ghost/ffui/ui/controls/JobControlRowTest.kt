package me.ghost.ffui.ui.controls

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import me.ghost.ffui.ui.theme.Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for the stop-confirmation flow on [JobControlRow]: tapping Stop asks before
 * killing a print, Cancel backs out without stopping, and the confirmed Stop invokes the cancel
 * path exactly once. Also pins the Pause/Resume swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JobControlRowTest {

    @get:Rule val composeTestRule = createComposeRule()

    private var cancelled = 0
    private var paused = 0
    private var resumed = 0

    private fun setContent(isPrinting: Boolean = true, isPaused: Boolean = false, isPausing: Boolean = false) {
        composeTestRule.setContent {
            Theme {
                JobControlRow(
                    isPrinting = isPrinting,
                    isPaused = isPaused,
                    isPausing = isPausing,
                    onPause = { paused++ },
                    onResume = { resumed++ },
                    onCancel = { cancelled++ },
                )
            }
        }
    }

    @Test
    fun `stop tap asks for confirmation before cancelling`() {
        setContent()
        composeTestRule.onNodeWithText("Stop this print?").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Stop").performClick()
        composeTestRule.onNodeWithText("Stop this print?").assertExists()
        composeTestRule.onNodeWithText("The print cannot resume after you stop it.").assertExists()
        // The dialog opened — but nothing stopped yet.
        org.junit.Assert.assertEquals(0, cancelled)
    }

    @Test
    fun `cancel on the confirmation dialog backs out without stopping`() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Stop").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Stop this print?").assertDoesNotExist()
        org.junit.Assert.assertEquals(0, cancelled)
    }

    @Test
    fun `confirming the dialog stops the print exactly once`() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Stop").performClick()
        composeTestRule.onNodeWithText("Stop").performClick() // the dialog's confirm button (text, not cd)
        composeTestRule.onNodeWithText("Stop this print?").assertDoesNotExist()
        org.junit.Assert.assertEquals(1, cancelled)
    }

    @Test
    fun `paused row swaps Pause for Resume`() {
        setContent(isPaused = true)
        composeTestRule.onNodeWithText("RESUME").assertExists()
        composeTestRule.onNodeWithText("PAUSE").assertDoesNotExist()
        composeTestRule.onNodeWithText("RESUME").performClick()
        org.junit.Assert.assertEquals(1, resumed)
        org.junit.Assert.assertEquals(0, paused)
    }

    @Test
    fun `printing row pauses via the Pause button`() {
        setContent()
        composeTestRule.onNodeWithText("PAUSE").performClick()
        org.junit.Assert.assertEquals(1, paused)
        org.junit.Assert.assertEquals(0, resumed)
    }

    @Test
    fun `exactly one stop affordance is offered while printing`() {
        setContent()
        composeTestRule.onAllNodesWithContentDescription("Stop").assertCountEquals(1)
    }
}
