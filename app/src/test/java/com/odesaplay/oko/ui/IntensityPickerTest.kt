package com.odesaplay.oko

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The presets picker, rendered. [WizardFlowTest] owns the rules; this owns the things a pure
 * function cannot see — that the four names are wired to the four setups, that the subtext follows
 * the selection instead of describing all four at once, and that no language leaves a tile blank.
 */
@RunWith(RobolectricTestRunner::class)
class IntensityPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val s = Strings.get(AppLanguage.EN)

    @Test
    fun `all four setups are offered by name`() {
        compose.setContent { IntensityPicker(s = s, selected = AppIntensity.BALANCED, onPick = {}) }

        compose.onNodeWithText(s.wizardIntensityReady).assertIsDisplayed()
        compose.onNodeWithText(s.wizardIntensityBalanced).assertIsDisplayed()
        compose.onNodeWithText(s.wizardIntensityQuiet).assertIsDisplayed()
        compose.onNodeWithText(s.wizardIntensityWatching).assertIsDisplayed()
    }

    @Test
    fun `only the selected setup is explained`() {
        compose.setContent { IntensityPicker(s = s, selected = AppIntensity.QUIET, onPick = {}) }

        compose.onNodeWithText(s.wizardIntensityQuietDesc).assertIsDisplayed()
        // One subtext, not four paragraphs competing for a phone screen.
        compose.onAllNodesWithText(s.wizardIntensityReadyDesc).assertCountEquals(0)
        compose.onAllNodesWithText(s.wizardIntensityBalancedDesc).assertCountEquals(0)
        compose.onAllNodesWithText(s.wizardIntensityWatchingDesc).assertCountEquals(0)
    }

    @Test
    fun `no selection means no explanation`() {
        compose.setContent { IntensityPicker(s = s, selected = null, onPick = {}) }

        compose.onAllNodesWithText(s.wizardIntensityReadyDesc).assertCountEquals(0)
        compose.onAllNodesWithText(s.wizardIntensityBalancedDesc).assertCountEquals(0)
        compose.onAllNodesWithText(s.wizardIntensityQuietDesc).assertCountEquals(0)
        compose.onAllNodesWithText(s.wizardIntensityWatchingDesc).assertCountEquals(0)
    }

    @Test
    fun `tapping a tile reports that setup, once`() {
        val picked = mutableListOf<AppIntensity>()
        compose.setContent {
            IntensityPicker(s = s, selected = AppIntensity.BALANCED, onPick = { picked += it })
        }

        compose.onNodeWithText(s.wizardIntensityWatching).performClick()

        compose.runOnIdle { assertEquals(listOf(AppIntensity.WATCHING), picked) }
    }

    /**
     * An empty quadrant with no explanation reads as a broken row, and this walks the real name→tile
     * mapping in every language rather than a copy of it — a mirrored lookup here would pass while
     * the screen showed the wrong string.
     */
    @Test
    fun `every language fills all four tiles and explains the selection`() {
        var language by mutableStateOf(AppLanguage.EN)
        compose.setContent {
            IntensityPicker(
                s = Strings.get(language),
                selected = AppIntensity.QUIET,
                onPick = {}
            )
        }

        for (candidate in AppLanguage.values()) {
            compose.runOnIdle { language = candidate }
            val strings = Strings.get(candidate)
            compose.onNodeWithText(strings.wizardIntensityReady).assertIsDisplayed()
            compose.onNodeWithText(strings.wizardIntensityBalanced).assertIsDisplayed()
            compose.onNodeWithText(strings.wizardIntensityQuiet).assertIsDisplayed()
            compose.onNodeWithText(strings.wizardIntensityWatching).assertIsDisplayed()
            compose.onNodeWithText(strings.wizardIntensityQuietDesc).assertIsDisplayed()
        }
    }
}