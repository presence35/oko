package ua.ukrainedrones

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatCardDedupeTest {

    @Test
    fun typePlusRepeatedRegionIsHidden() {
        assertTrue(
            repeatsShownInfo(
                "Drone - Kobleve, Mykolaiv oblast",
                typeLabel = "Drone",
                labelEn = "Drone",
                regionText = "Kobleve · Mykolaiv oblast"
            )
        )
    }

    @Test
    fun realSentenceSurvives() {
        assertFalse(
            repeatsShownInfo("Drone patrolling over the sea", "Drone", "Drone", "Odesa oblast")
        )
    }

    @Test
    fun courseWithDifferentDestinationSurvives() {
        assertFalse(
            repeatsShownInfo("Drone heading toward Chornomorsk", "Drone", "Drone", "Kobleve · Mykolaiv oblast")
        )
    }

    @Test
    fun courseWithSameDestinationHidden() {
        assertTrue(
            repeatsShownInfo("Drone heading toward Chornomorsk", "Drone", "Drone", "Chornomorsk · Odesa oblast")
        )
    }

    @Test
    fun ukrainianDuplicateHidden() {
        assertTrue(
            repeatsShownInfo(
                "БпЛА - Коблеве, Миколаївська область",
                typeLabel = "БпЛА",
                labelEn = "Drone",
                regionText = "Коблеве · Миколаївська область"
            )
        )
    }

    @Test
    fun typeOnlyLineHidden() {
        assertTrue(repeatsShownInfo("Drone", "Drone", "Drone", "Odesa oblast"))
    }

    @Test
    fun regionPhraseRemovedBeforeShorterLocality() {
        assertTrue(
            repeatsShownInfo("БпЛА Київська область", "БпЛА", "Drone", "Київ · Київська область")
        )
    }

    @Test
    fun extraInfoBeyondNamesSurvives() {
        assertFalse(
            repeatsShownInfo("Drone circling, air defense active", "Drone", "Drone", "Kobleve · Mykolaiv oblast")
        )
    }

    @Test
    fun guidedBombRepeatingTypeAndPlaceHidden() {
        assertTrue(
            repeatsShownInfo("Guided bomb heading toward Khmelnytskyi", "KAB", "KAB", "Khmelnytskyi")
        )
    }
}