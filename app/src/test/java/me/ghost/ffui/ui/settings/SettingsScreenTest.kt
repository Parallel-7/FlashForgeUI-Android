package me.ghost.ffui.ui.settings

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import me.ghost.ffui.FfuiApplication
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.theme.Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real Settings screen over the real app graph (FfuiApplication → session manager,
 * DataStore, Room) under Robolectric and pins the background-monitoring section: the master
 * toggle renders, and its nested controls (battery nudge + throttle toggle) stay hidden until
 * background monitoring is switched on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = FfuiApplication::class)
class SettingsScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun setContent() {
        val app = ApplicationProvider.getApplicationContext<FfuiApplication>()
        val viewModel = MainViewModel(app)
        composeTestRule.setContent {
            Theme { SettingsScreen(viewModel) }
        }
    }

    /** Scrolls the settings LazyColumn until the background-monitoring row is on screen. */
    private fun scrollToBackgroundSection() {
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Keep monitoring in background", substring = true))
    }

    @Test
    fun `background monitoring section renders its master toggle`() {
        setContent()
        scrollToBackgroundSection()
        composeTestRule.onNodeWithText("Background").assertExists()
        composeTestRule.onNodeWithText("Keep monitoring in background").assertExists()
        composeTestRule.onNodeWithText("Stay connected for alerts even when the app is closed").assertExists()
    }

    @Test
    fun `nested background controls stay hidden while monitoring is off`() {
        setContent()
        scrollToBackgroundSection()
        // Fresh DataStore → background monitoring off → the throttle toggle and battery nudge
        // (nested under `if (backgroundEnabled)`) must not render.
        composeTestRule.onNodeWithText("Slow updates in background").assertDoesNotExist()
        composeTestRule.onNodeWithText("Allow background activity").assertDoesNotExist()
    }
}
