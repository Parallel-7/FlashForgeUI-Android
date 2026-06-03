package me.ghost.ffui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffui.ui.dashboard.HeaterGrid
import me.ghost.ffui.ui.dashboard.JobProgressHeader
import me.ghost.ffui.ui.dashboard.JobStatsRow
import me.ghost.ffui.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi screenshot test for the dashboard's job/heater/stat cards in a representative
 * mid-print state. Captures [JobProgressHeader] + [HeaterGrid] + [JobStatsRow] together so the
 * dark Slate-Blue theme and the orange-nozzle / blue-bed indicators are pinned against regressions.
 *
 * Record/refresh the baseline with `./gradlew recordRoborazziDebug`; a plain `./gradlew test`
 * records on first run and compares thereafter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class DashboardCardsScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun dashboardCards_printing() {
        val status = PrinterDetailResponse(
            status = "printing",
            printFileName = "benchy.gcode",
            printProgress = 0.42,
            estimatedTime = 5_400.0,   // 90 min remaining
            printLayer = 84.0,
            targetPrintLayer = 200.0,
            rightTemp = 210.0,
            rightTargetTemp = 220.0,
            platTemp = 58.0,
            platTargetTemp = 60.0
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        JobProgressHeader(fileName = status.printFileName, progress = 42, stateLabel = "printing")
                        HeaterGrid(status = status, onHeaterClick = {})
                        JobStatsRow(status = status)
                    }
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/dashboard_cards_printing.png")
    }
}
