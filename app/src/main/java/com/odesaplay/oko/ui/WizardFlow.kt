package com.odesaplay.oko

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The first-launch wizard's rules, with no Compose and no Android framework in them.
 *
 * These used to live inline in [FirstLaunchWizard], which is why inserting the intensity page could
 * silently leave the location page's "you must pick a position" gate sitting on the intensity page's
 * index: nothing about a bare `0..6` makes a reader notice that `2` now means something else. Named
 * pages plus functions that take their inputs is the smallest change that makes that mistake
 * impossible to make quietly — [WizardFlowTest] fails the moment a rule lands on the wrong page.
 */
internal enum class WizardPage {
    WHAT, TIPS, INTENSITY, LOCATION, CARE, ZONES, ALPHA;

    fun next(): WizardPage = values()[ordinal + 1]
    fun previous(): WizardPage = values()[ordinal - 1]
    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == values().size - 1

    companion object {
        val COUNT: Int = values().size
    }
}

/** The header each page shows. */
internal fun WizardPage.title(s: Strings.StringSet): String = when (this) {
    WizardPage.WHAT -> s.wizardPageTitle
    WizardPage.TIPS -> s.onboardingTipsTitle
    WizardPage.INTENSITY -> s.wizardIntensityTitle
    WizardPage.LOCATION -> s.wizardLocationTitle
    WizardPage.CARE -> s.wizardCareTitle
    WizardPage.ZONES -> s.wizardZonesTitle
    WizardPage.ALPHA -> s.wizardAlphaTitle
}

/**
 * Only the location page has a prerequisite: a position. Everything else is a plain Next —
 * including the presets page, which pre-selects Normal and is therefore always answerable.
 *
 * `pin` additionally needs a city, because that is the mode with nothing behind it until the user
 * picks one.
 */
internal fun WizardPage.isNextEnabled(locationMode: String?, hasPinnedCity: Boolean): Boolean =
    this != WizardPage.LOCATION || (locationMode != null && (locationMode != "pin" || hasPinnedCity))

/**
 * The notification prompt belongs to [WizardPage.CARE] — asked once there is something to notify
 * about, which is the page where the user picks what to be alarmed by.
 *
 * [sdkInt] is a parameter rather than read from [Build] so the pre-33 branch is testable at all:
 * the suite pins Robolectric to one SDK, which would leave the old branch permanently unexercised.
 */
internal fun shouldAskForNotifications(sdkInt: Int, permissionGranted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted

/**
 * Leaving the presets page writes the setup the page shows. It pre-selects Normal, so tapping
 * straight through must land a real default rather than leave the untouched stored defaults —
 * which are not quite Normal.
 */
internal fun WizardPage.commitsIntensityOnLeave(): Boolean = this == WizardPage.INTENSITY

/** The one place the wizard reads a system permission, so the rule above has a single caller. */
internal fun notificationsPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED