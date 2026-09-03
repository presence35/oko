package ua.ukrainedrones

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatCardDedupeTest {

    @Test
    fun typePlusRepeatedRegionIsHidden() {
        assertTrue(
            repeatsShownInfo(
                "UAV - Kobleve, Mykolaiv oblast",
                typeLabel = "UAV",
                regionText = "Kobleve · Mykolaiv oblast"
            )
        )
    }

    @Test
    fun realSentenceSurvives() {
        assertFalse(
            repeatsShownInfo("UAV patrolling over the sea", "UAV", "Odesa oblast")
        )
    }

    @Test
    fun directionalCourseSurvives() {
        assertFalse(
            repeatsShownInfo("UAV heading toward Chornomorsk", "UAV", "Chornomorsk · Odesa oblast")
        )
    }

    @Test
    fun ukrainianDuplicateHidden() {
        assertTrue(
            repeatsShownInfo(
                "БпЛА - Коблеве, Миколаївська область",
                typeLabel = "БпЛА",
                regionText = "Коблеве · Миколаївська область"
            )
        )
    }

    @Test
    fun typeOnlyLineHidden() {
        assertTrue(repeatsShownInfo("UAV", "UAV", "Odesa oblast"))
    }

    @Test
    fun regionPhraseRemovedBeforeShorterLocality() {
        assertTrue(
            repeatsShownInfo("БпЛА Київська область", "БпЛА", "Київ · Київська область")
        )
    }

    @Test
    fun extraInfoBeyondNamesSurvives() {
        assertFalse(
            repeatsShownInfo("UAV circling, air defense active", "UAV", "Kobleve · Mykolaiv oblast")
        )
    }
}
