package com.odesaplay.oko

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's control flow, with no Compose in it.
 *
 * The gate assertion is deliberately per-page and exhaustive rather than "the location page blocks
 * Next": the bug that produced this file was a location rule left on the intensity page's index,
 * which nothing noticed because a bare number reads the same on every page.
 */
class WizardFlowTest {

    @Test
    fun `pages are walked in order and stop at both ends`() {
        assertEquals(WizardPage.WHAT, WizardPage.values().first())
        assertEquals(WizardPage.ALPHA, WizardPage.values().last())
        assertEquals(WizardPage.COUNT, WizardPage.values().size)

        // next() past the end and previous() before the start must not be reachable from the UI:
        // BackHandler and the Next button both guard on isFirst/isLast.
        assertTrue(WizardPage.WHAT.isFirst)
        assertTrue(WizardPage.ALPHA.isLast)
        assertFalse(WizardPage.TIPS.isFirst)
        assertFalse(WizardPage.TIPS.isLast)

        var page = WizardPage.WHAT
        val walked = mutableListOf(page)
        while (!page.isLast) {
            page = page.next()
            walked += page
        }
        assertEquals(WizardPage.values().toList(), walked)
    }

    @Test
    fun `only the location page can block Next`() {
        for (page in WizardPage.values()) {
            val noPosition = page.isNextEnabled(locationMode = null, hasPinnedCity = false)
            val withPosition = page.isNextEnabled(locationMode = "follow", hasPinnedCity = false)
            if (page == WizardPage.LOCATION) {
                assertFalse("$page must wait for a position", noPosition)
                assertTrue("$page is answerable once a mode is chosen", withPosition)
            } else {
                assertTrue("$page must always be answerable", noPosition)
                assertTrue("$page must always be answerable", withPosition)
            }
        }
    }

    @Test
    fun `the pin mode needs a city, follow-me does not`() {
        assertFalse(WizardPage.LOCATION.isNextEnabled("pin", hasPinnedCity = false))
        assertTrue(WizardPage.LOCATION.isNextEnabled("pin", hasPinnedCity = true))
        assertTrue(WizardPage.LOCATION.isNextEnabled("follow", hasPinnedCity = false))
    }

    @Test
    fun `every page has a header, and none of them is blank`() {
        for (lang in AppLanguage.values()) {
            val s = Strings.get(lang)
            for (page in WizardPage.values()) {
                assertTrue("${page.name} in $lang", page.title(s).isNotBlank())
            }
        }
    }

    /** The prompt is asked on the page where the user decides what to be alarmed by — and only
     *  where the platform actually has the permission to ask for. */
    @Test
    fun `the notification prompt is asked on the care page, on API 33 and up, once`() {
        assertTrue(
            shouldAskForNotifications(Build.VERSION_CODES.TIRAMISU, permissionGranted = false)
        )
        assertFalse(
            "already granted, so no second prompt",
            shouldAskForNotifications(Build.VERSION_CODES.TIRAMISU, permissionGranted = true)
        )
        // The suite pins Robolectric to one SDK, so this branch is only reachable through this
        // parameter. Without it the pre-33 path is never executed by anything.
        assertFalse(
            shouldAskForNotifications(Build.VERSION_CODES.S, permissionGranted = false)
        )
        assertFalse(
            shouldAskForNotifications(Build.VERSION_CODES.M, permissionGranted = false)
        )
    }

    /** The presets page pre-selects Normal, so leaving it must write something — never nothing. */
    @Test
    fun `only the presets page commits a setup on the way out`() {
        for (page in WizardPage.values()) {
            assertEquals(
                "${page.name} commit rule",
                page == WizardPage.INTENSITY,
                page.commitsIntensityOnLeave()
            )
        }
        assertFalse(
            "the presets page is not the last one, so Next advances instead of finishing",
            WizardPage.INTENSITY.isLast
        )
    }
}