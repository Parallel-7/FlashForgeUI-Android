package me.ghost.ffui.ui.discovery

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import me.ghost.ffui.R
import me.ghost.ffui.ui.theme.Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the saved-tile badge states: the green "Ready" dot (seen in the latest scan, no live
 * session) and the "Connected" pill, which always wins when both would apply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PrinterTileScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun tile(isConnected: Boolean, isReady: Boolean, file: String) {
        composeTestRule.setContent {
            Theme {
                PrinterTile(
                    name = "Adventurer 5M",
                    ip = "192.168.1.50",
                    imageRes = R.drawable.printer_ad5x,
                    isConnected = isConnected,
                    isReady = isReady,
                    onClick = {},
                    onInfoClick = {},
                    onSettingsClick = {}
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$file")
    }

    @Test
    fun offlineTile_showsNoBadge() =
        tile(isConnected = false, isReady = false, file = "printer_tile_offline.png")

    @Test
    fun readyTile_showsReadyDot() =
        tile(isConnected = false, isReady = true, file = "printer_tile_ready.png")

    @Test
    fun connectedTile_showsConnectedPill() =
        tile(isConnected = true, isReady = false, file = "printer_tile_connected.png")

    @Test
    fun connectedAndReadyTile_connectedWins() =
        tile(isConnected = true, isReady = true, file = "printer_tile_connected_wins.png")
}
