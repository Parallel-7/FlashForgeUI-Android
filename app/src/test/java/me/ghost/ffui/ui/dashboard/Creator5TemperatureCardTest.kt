package me.ghost.ffui.ui.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffui.ui.theme.Theme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior test for the Creator 5 temperature card's chamber gating (wave 2): the CHAMBER cell —
 * and its Set/Off commands — must be omitted on units without the chamber sensor (the `-108`
 * sentinel units), while tool cells T1–T4 and BED always render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Creator5TemperatureCardTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val detail = FFPrinterDetail(
        nozzleTemps = listOf(200f, 0f, 0f, 0f),
        nozzleTargetTemps = listOf(210f, 0f, 0f, 0f),
        platTemp = 55f,
        platTargetTemp = 60f,
        chamberTemp = 40f,
        chamberTargetTemp = 45f,
    )

    private fun setContent(hasChamberSensor: Boolean) {
        composeTestRule.setContent {
            Theme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Creator5TemperatureCard(
                        detail = detail,
                        hasChamberSensor = hasChamberSensor,
                        onCreate5SetTool = { _, _ -> },
                        onCreate5CancelTool = { },
                        onCreate5SetBed = { },
                        onCreate5CancelBed = { },
                        onCreate5SetChamber = { },
                        onCreate5CancelChamber = { },
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }

    @Test
    fun `chamber cell renders when the unit has the sensor`() {
        setContent(hasChamberSensor = true)
        composeTestRule.onNodeWithText("CHAMBER").assertExists()
        listOf("T1", "T2", "T3", "T4", "BED").forEach { label ->
            composeTestRule.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun `chamber cell is omitted when the unit has no sensor`() {
        setContent(hasChamberSensor = false)
        assertEquals(0, composeTestRule.onAllNodesWithText("CHAMBER").fetchSemanticsNodes().size)
        listOf("T1", "T2", "T3", "T4", "BED").forEach { label ->
            composeTestRule.onNodeWithText(label).assertExists()
        }
    }
}
