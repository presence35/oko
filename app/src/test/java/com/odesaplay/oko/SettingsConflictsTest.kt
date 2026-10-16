package com.odesaplay.oko

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conflict rules. Two things matter here and neither is cosmetic: an unachievable intent is
 * reported, and a deliberate choice is not.
 */
class SettingsConflictsTest {

    private fun prefs(
        sirenOverride: Boolean = false,
        nightZoneSirenOverride: Boolean = false,
        nightOfficialSirenOverride: Boolean = false
    ) = UserPreferences.DEFAULT.copy(
        sirenOverride = sirenOverride,
        nightZoneSirenOverride = nightZoneSirenOverride,
        nightOfficialSirenOverride = nightOfficialSirenOverride
    )

    private fun notif(
        enabled: Boolean = true,
        muted: List<String> = emptyList(),
        policyAccess: Boolean = false,
        filter: Int = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    ) = NotificationState(
        enabled = enabled,
        channels = muted.map {
            NotifChannel(it, NotificationManager.IMPORTANCE_NONE, hasSound = false)
        },
        policyAccess = policyAccess,
        interruptionFilter = filter,
        ringerMode = AudioManager.RINGER_MODE_NORMAL,
        suspended = false
    )

    @Test
    fun `a clean configuration reports nothing`() {
        assertEquals(emptyList<SettingsConflict>(), settingsConflicts(prefs(), notif()))
    }

    /**
     * The loud switches are the user saying "wake me". If the OS will not deliver, nothing else
     * about the configuration matters, so this is reported alone rather than beside finer warnings.
     */
    @Test
    fun `notifications off is reported alone, whatever else is set`() {
        assertEquals(
            listOf(SettingsConflict.NOTIFICATIONS_OFF),
            settingsConflicts(
                prefs(sirenOverride = true),
                notif(enabled = false, muted = listOf("alerts_outer"))
            )
        )
    }

    /**
     * The residual silence case: the app-level switch is on, so no banner would appear, and a
     * single silenced channel quietly costs an alert.
     */
    @Test
    fun `a single silenced channel is reported while the app switch is on`() {
        assertEquals(
            listOf(SettingsConflict.MUTED_CHANNEL),
            settingsConflicts(prefs(), notif(muted = listOf("alerts_outer")))
        )
    }

    @Test
    fun `dnd is reported only when the user asked to be woken`() {
        val dnd = notif(policyAccess = true, filter = NotificationManager.INTERRUPTION_FILTER_NONE)

        assertEquals(
            "a silent-mode user who never asked for sound is not in conflict",
            emptyList<SettingsConflict>(),
            settingsConflicts(prefs(), dnd)
        )
        assertEquals(
            listOf(SettingsConflict.DND_BLOCKS_LOUD),
            settingsConflicts(prefs(sirenOverride = true), dnd)
        )
        assertEquals(
            "the night switches are the same intent, after dark",
            listOf(SettingsConflict.DND_BLOCKS_LOUD),
            settingsConflicts(prefs(nightZoneSirenOverride = true), dnd)
        )
    }

    /**
     * The copy is indexed by [SettingsConflict.ordinal], so a language that is missing a title or a
     * body would silently render nothing for that conflict rather than fail. Guard the shape.
     */
    @Test
    fun `every language words every conflict`() {
        AppLanguage.entries.forEach { lang ->
            val s = Strings.get(lang)
            assertEquals(
                "$lang titles",
                SettingsConflict.entries.size,
                s.settingsConflictTitles.size
            )
            assertEquals(
                "$lang bodies",
                SettingsConflict.entries.size,
                s.settingsConflictBodies.size
            )
            (s.settingsConflictTitles + s.settingsConflictBodies).forEach { line ->
                assertTrue("$lang has a blank conflict string", line.isNotBlank())
            }
        }
    }

    @Test
    fun `muted channel and dnd stack when both are true`() {
        assertEquals(
            listOf(SettingsConflict.MUTED_CHANNEL, SettingsConflict.DND_BLOCKS_LOUD),
            settingsConflicts(
                prefs(sirenOverride = true),
                notif(
                    muted = listOf("alerts_inner"),
                    policyAccess = true,
                    filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            )
        )
    }
}
