package com.odesaplay.oko

import android.content.res.Configuration
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.odesaplay.oko.service.MonitoringStatus
import com.odesaplay.oko.service.SuspendState
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.odesaplay.oko.theme.DarkThemePlugin

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Set once the first prefs snapshot arrives — lifts the splash, (re)arms monitoring. */
    @Volatile
    private var splashReady = false

    private companion object {
        const val REQUEST_LOCATION = 1
        const val REQUEST_NOTIFICATIONS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Branded splash (trident) kept until the first prefs snapshot clears the content
        // gate — the launch reads as "starting" instead of frozen black.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        org.maplibre.android.MapLibre.getInstance(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        cleanLegacyOsmdroidCache()
        ConnectionLog.attach(applicationContext)
        DebugLog.attach(applicationContext)
        BatteryLog.attach(applicationContext)
        // Registry first: after an OS force-stop the process is fresh and anything
        // touching AppSources.registry before init throws (immediate crash on launch).
        AppSources.ensureInit(applicationContext)
        // A launch is a new session: a Stop & exit from the last one does not outlive it.
        MonitoringStatus.sessionStarted()
        // Monitoring is always-on: "Stop Monitoring & Exit" is a session-only stop, so a
        // cold start (re)arms the service — but past the first frame, never ahead of it:
        // binder + WorkManager enqueue on the critical path is what stretches black on
        // slow phones. The UI banner already surfaces a dead state instead of going silent.
        // The splash lifts on the same signal.
        splashScreen.setKeepOnScreenCondition { !splashReady }
        lifecycleScope.launch {
            viewModel.uiState.first { it.wizardCompleted != null }
            splashReady = true
            ensureMonitoring()
            runCatching { com.odesaplay.oko.service.AlertWatchdog.schedule(applicationContext) }
            // Stop & exit cancels every resurrection path, so a cold start re-arms all of them —
            // the watchdog alone would leave the 15-minute safety net disarmed until a reboot.
            runCatching { com.odesaplay.oko.service.EmergencyResurrectionWorker.schedule(applicationContext) }
        }
        setContent {
            // Cap the system font scale so extreme accessibility sizes can't break the layout;
            // the popup/banner still wrap and scroll up to this ceiling.
            val baseDensity = LocalDensity.current
            val fontScale = min(baseDensity.fontScale, 1.5f)
            // The grade is a render setting, not screen state: one local keeps every
            // ThreatIcon in the tree in step without threading it through each screen.
            val iconGrade by produceState(ThreatIconGrade.DEFAULT) {
                viewModel.uiState
                    .map { if (it.moraleMasterEnabled) it.iconGrade else ThreatIconGrade.DEFAULT }
                    .distinctUntilChanged()
                    .collect { value = it }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
                LocalIconGrade provides iconGrade
            ) {
                MaterialTheme(colorScheme = DarkThemePlugin.colors) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(windowFocused = windowFocused.value)
                    }
                }
            }
        }
        handleReveal(intent)
        handleFlourish(intent)
        deferPermissionRequests()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReveal(intent)
        handleFlourish(intent)
    }

    /** True only while this window owns input focus. A notification tap resumes the activity
     *  while the shade is still collapsing over it, so focus is the one signal that says the
     *  map is actually on screen — the shot-down replay holds its first bullet until then. */
    private val windowFocused = mutableStateOf(false)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused.value = hasFocus
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
    }

    /**
     * A notification tap can carry the threat that triggered it. Select it and ask the map
     * to pan onto it so both the focus point (GPS/city) and the threat are on screen.
     */
    private fun handleReveal(intent: Intent?) {
        val id = intent?.getStringExtra(AlertService.EXTRA_REVEAL_ID)
        if (id != null) {
            val lat = intent.getDoubleExtra(AlertService.EXTRA_REVEAL_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(AlertService.EXTRA_REVEAL_LON, Double.NaN)
            // Reject garbage coordinates (a stale notification or a corrupted extras bundle must
            // never hand the map an out-of-range fix that could blow up the camera framing).
            if (lat.isFinite() && lon.isFinite() &&
                lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0
            ) {
                viewModel.revealThreat(id, lat, lon)
            }
        }
        if (intent?.getBooleanExtra(AlertService.EXTRA_SHOW_MAP, false) == true) {
            viewModel.navigateToMap()
        }
        if (intent?.getBooleanExtra(AlertService.EXTRA_SHOW_UPDATE, false) == true) {
            viewModel.checkForUpdates()
        }
    }

    /**
     * Tapping the "N threats resolved" tally notification replays a mock shot-down show: the
     * app opens on the map, zooms to fit every remembered resolution, and fires the bullets
     * one after another. Pure flourish — an arriving red alert ejects it in the service.
     */
    private fun handleFlourish(intent: Intent?) {
        val lats = intent?.getDoubleArrayExtra(NeutralizedTally.EXTRA_FLOURISH_LATS) ?: return
        val lons = intent.getDoubleArrayExtra(NeutralizedTally.EXTRA_FLOURISH_LONS) ?: return
        val types = intent.getStringArrayExtra(NeutralizedTally.EXTRA_FLOURISH_TYPES) ?: return
        val regions = intent.getStringArrayExtra(NeutralizedTally.EXTRA_FLOURISH_REGIONS)
        val source = intent.getStringExtra(NeutralizedTally.EXTRA_FLOURISH_SOURCE)
        val records = parseFlourishRecords(lats, lons, types, regions)
        if (records.isNotEmpty()) {
            viewModel.navigateToMap()
            viewModel.setShelterModeActive(false)
            viewModel.triggerFlourish(records)
        }
        // Reset only the store whose show was consumed — watching one replay must not
        // wipe the other's unwatched memory. Both tallies share the replay extras, and an
        // untagged (legacy) notification resets both, as before.
        runCatching {
            if (flourishResetTally(source)) {
                startService(
                    Intent(this, AlertService::class.java)
                        .setAction(NeutralizedTally.ACTION_NEUTRALIZED_DISMISS)
                )
            }
            if (flourishResetEpisode(source)) {
                startService(
                    Intent(this, AlertService::class.java)
                        .setAction(AlarmEpisodeTally.ACTION_ALARM_EPISODE_DISMISS)
                )
            }
        }
    }

    /**
     * Tiles now live in the OS cache dir; delete the pre-migration caches (internal and
     * external app dirs) that Android counted as "user data" — once, not on every launch.
     * Tiles re-download harmlessly.
     */
    private fun cleanLegacyOsmdroidCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = UserPrefs(applicationContext)
            if (prefs.preferences.first().legacyCacheCleaned) return@launch
            File(filesDir, "osmdroid").takeIf { it.exists() }?.deleteRecursively()
            getExternalFilesDir(null)?.let { base ->
                File(base, "osmdroid").takeIf { it.exists() }?.deleteRecursively()
            }
            prefs.setLegacyCacheCleaned(true)
        }
    }

    override fun onStart() {
        super.onStart()
        // Mirror the website's focus handler — refresh positions and reset the stale window.
        // Guarded: after a force-stop the registry may still be null here if init lost
        // the race with the service start — never crash the foreground on it.
        AppSources.ensureInit(applicationContext)
        runCatching { AppSources.registry.onAppForeground() }
        // Coming back is a legal moment to start a foreground service, and after the battery
        // exemption is gone it is the only one the platform still allows - the alarm and the
        // 15-minute workers are refused in the background. The splash gate keeps a cold launch
        // on its original ordering (prefs first, then the binder call).
    }

    /**
     * Re-arm on resume, never on start: the platform still counts a started-but-not-resumed
     * activity as background, and Android 16 answers such a start with "Background started FGS:
     * Disallowed" and a denied service - the exact state the red header reports.
     */
    override fun onResume() {
        super.onResume()
        if (splashReady) ensureMonitoring()
    }

    /**
     * Re-arm the monitor only when the facts say it is not running. Not gated on
     * "auto-restart after reboot": that switch is about the background, and the user is here.
     * It does answer to [MonitoringStatus], which refuses after a deliberate Stop & exit.
     */
    private fun ensureMonitoring() {
        if (SuspendState.isSuspended(this)) return
        if (!MonitoringStatus.needsRestart()) return
        runCatching { AlertService.start(this) }
    }

    /**
     * Two system permission dialogs must never be up at once — Android drops a request that
     * arrives while another is showing. Ask for location first (the map depends on it), then
     * chain the notification request once that dialog is resolved.
     */
    private fun requestLocationAndNotifications() {
        val locationGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (locationGranted) {
            LocationTracker.start(this)
            requestNotificationPermission()
        } else {
            // FINE so precise one-shots (alert / future shelter handoff) are available; the
            // dialog lets the user pick "approximate" — which still grants COARSE for the map.
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION
            )
        }
    }

    /**
     * Permission dialogs must never beat the first-run onboarding — ask only after the wizard
     * resolves. The battery prompt is contextual now (only after a real service kill), so it
     * no longer gates permissions. Returning users who already finished onboarding get the
     * request immediately.
     */
    private fun deferPermissionRequests() {
        lifecycleScope.launch {
            val prefs = UserPrefs(applicationContext)
            // Re-arm for this session — a previous "Later" deferral only lasts one launch.
            prefs.setPermissionPromptDeferred(false)
            val p = prefs.preferences.first()
            val ready = if (p.wizardCompleted) {
                !p.permissionPromptDeferred
            } else {
                prefs.preferences
                    .map { it.wizardCompleted && !it.permissionPromptDeferred }
                    .first { it }
            }
            if (ready) requestLocationAndNotifications()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                LocationTracker.start(this)
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Denied with "Don't ask again" — the system won't show the dialog anymore; route to Settings.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }
}
