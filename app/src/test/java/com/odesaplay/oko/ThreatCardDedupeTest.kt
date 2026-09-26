package com.odesaplay.oko

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
                shownRegion = "Kobleve · Mykolaiv oblast"
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
                shownRegion = "Коблеве · Миколаївська область"
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

    @Test
    fun translatedCourseVersusTransliteratedRegionIsHidden() {
        // Real pipeline: NEPTUN sends Ukrainian course + region; the EN card renders both
        // before deduping. Comparing a translated course against a raw region (the old bug)
        // never matched, so the duplicate survived in EN/RU.
        val course = translateCourseAssessment(
            "БпЛА — Олександрія, Кіровоградська область", AppLanguage.EN
        )!!
        val region = Transliteration.transliterate("Олександрія · Кіровоградська область")
        assertTrue(repeatsShownInfo(course, "Drone", "Drone", region))
    }

    @Test
    fun transliteratedTypeNameHidden() {
        // The glossary has no "дрон" entry, so "FPV-дрон" reaches the EN course by
        // transliteration ("FPV-dron"). Canonicalizing script makes it match the UA label.
        val course = translateCourseAssessment("FPV-дрон — Радківка", AppLanguage.EN)!!
        assertTrue(repeatsShownInfo(course, "FPV drone", "FPV drone", "Radkivka"))
    }

    @Test
    fun areaAdvisoryTemplateRecognized() {
        assertTrue(
            isAreaAdvisory("БпЛА — Чернігівська область: попередження по області, точка невідома")
        )
        assertTrue(
            isAreaAdvisory("Drone — Chernihivska oblast: попередження по території, точка не відома")
        )
    }

    @Test
    fun realCourseIsNotAreaAdvisory() {
        assertFalse(isAreaAdvisory("Шахеди курсом на Чорноморськ"))
        assertFalse(isAreaAdvisory(null))
    }

    @Test
    fun reconSynonymRestatingTypeAndPlaceIsHidden() {
        // NEPTUN names the recon type "Розвідувальний дрон", not the catalog label "Розвідка".
        // Its romanized form reaches the EN card as "Rozviduvalnyi Drone", which shares no whole
        // word with "Reconnaissance" — only a root, which the dedupe must still catch.
        assertTrue(
            repeatsShownInfo(
                "Rozviduvalnyi Drone — Ivanivka, Khersonska oblast",
                typeLabel = "Reconnaissance",
                labelEn = "Reconnaissance",
                shownRegion = "Ivanivka · Khersonska oblast"
            )
        )
    }

    @Test
    fun ukrainianReconSynonymRestatingTypeAndPlaceIsHidden() {
        assertTrue(
            repeatsShownInfo(
                "Розвідувальний дрон — Іванівка, Херсонська область",
                typeLabel = "Розвідка",
                labelEn = "Reconnaissance",
                shownRegion = "Іванівка · Херсонська область"
            )
        )
    }

    @Test
    fun reconSynonymWithRealCourseSurvives() {
        assertFalse(
            repeatsShownInfo(
                "Rozviduvalnyi dron patrolling over the sea",
                typeLabel = "Reconnaissance",
                labelEn = "Reconnaissance",
                shownRegion = "Kobleve · Mykolaiv oblast"
            )
        )
    }

    @Test
    fun ukrainianDroneWordRestatingTypeAndPlaceIsHidden() {
        assertTrue(
            repeatsShownInfo(
                "Дрон — Іванівка, Херсонська область",
                typeLabel = "БпЛА",
                labelEn = "Drone",
                shownRegion = "Іванівка · Херсонська область"
            )
        )
    }
}