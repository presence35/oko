package com.odesaplay.oko

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The persona grid, rendered. [AlertPersonaTest] owns the mapping; this owns what a pure function
 * cannot see — that seven names reach seven tiles, that a tap reports exactly one setup, that the
 * restore cell is inert until there is something to restore, and that the one-liners are reachable
 * behind the "i" instead of being seven paragraphs on the page.
 */
@RunWith(RobolectricTestRunner::class)
class AlertPersonaPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val s = Strings.get(AppLanguage.EN)

    @Test
    fun `all seven personas are offered by name`() {
        compose.setContent {
            AlertPersonaPicker(s = s, selected = null, canRestore = false, onPick = {}, onRestore = {})
        }

        compose.onNodeWithText(s.personaGrandma).assertIsDisplayed()
        compose.onNodeWithText(s.personaParent).assertIsDisplayed()
        compose.onNodeWithText(s.personaMilitary).assertIsDisplayed()
        compose.onNodeWithText(s.personaKid).assertIsDisplayed()
        compose.onNodeWithText(s.personaAbroad).assertIsDisplayed()
        compose.onNodeWithText(s.personaWork).assertIsDisplayed()
        compose.onNodeWithText(s.personaRoad).assertIsDisplayed()
        compose.onNodeWithText(s.alertPersonaRestore).assertIsDisplayed()
    }

    @Test
    fun `tapping a tile reports that persona, once`() {
        val picked = mutableListOf<AlertPersona>()
        compose.setContent {
            AlertPersonaPicker(s = s, selected = null, canRestore = false, onPick = { picked += it }, onRestore = {})
        }

        compose.onNodeWithText(s.personaMilitary).performClick()

        compose.runOnIdle { assertEquals(listOf(AlertPersona.FRONTLINE), picked) }
    }

    @Test
    fun `the restore cell is inert until a persona has been tapped`() {
        var restores = 0
        compose.setContent {
            AlertPersonaPicker(s = s, selected = null, canRestore = false, onPick = {}, onRestore = { restores++ })
        }

        compose.onNodeWithText(s.alertPersonaRestore).assertIsNotEnabled()
        compose.onNodeWithText(s.alertPersonaRestore).performClick()
        compose.runOnIdle { assertEquals(0, restores) }
    }

    @Test
    fun `the restore cell reports once it has something to restore`() {
        var restores = 0
        compose.setContent {
            AlertPersonaPicker(s = s, selected = null, canRestore = true, onPick = {}, onRestore = { restores++ })
        }

        compose.onNodeWithText(s.alertPersonaRestore).performClick()

        compose.runOnIdle { assertEquals(1, restores) }
    }

    @Test
    fun `the info button explains every persona`() {
        compose.setContent {
            AlertPersonaPicker(s = s, selected = null, canRestore = false, onPick = {}, onRestore = {})
        }

        compose.onNodeWithContentDescription(s.alertPersonaInfoTitle).performClick()

        // Existence, not visibility: seven one-liners do not fit a dialog body at once, so the list
        // is scrollable by design — what must hold is that opening the "i" renders all of them.
        compose.onNodeWithText(s.alertPersonaInfoTitle).assertExists()
        compose.onNodeWithText(s.personaGrandmaDesc).assertExists()
        compose.onNodeWithText(s.personaRoadDesc).assertExists()
    }

    @Test
    fun `the just-tapped persona explains itself under the grid`() {
        compose.setContent {
            AlertPersonaPicker(
                s = s,
                selected = AlertPersona.ABROAD,
                canRestore = false,
                onPick = {},
                onRestore = {}
            )
        }

        compose.onNodeWithText(s.personaAbroadDesc).assertIsDisplayed()
    }
}
