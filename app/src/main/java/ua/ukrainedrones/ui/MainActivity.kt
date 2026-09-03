package ua.ukrainedrones

import ua.ukrainedrones.connection.ConnectionHolder

import android.content.res.Configuration
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.ukrainedrones.theme.DarkThemePlugin

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private companion object {
        const val REQUEST_LOCATION = 1
        const val REQUEST_NOTIFICATIONS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        cleanLegacyOsmdroidCache()
        ConnectionLog.attach(applicationContext)
        DebugLog.attach(applicationContext)
        lifecycleScope.launch {
            val monitoringEnabled = UserPrefs(applicationContext).monitoringEnabled().first()
            if (monitoringEnabled) {
                AlertService.start(this@MainActivity)
            }
        }
        setContent {
            // Cap the system font scale so extreme accessibility sizes can't break the layout;
            // the popup/banner still wrap and scroll up to this ceiling.
            val baseDensity = LocalDensity.current
            val fontScale = min(baseDensity.fontScale, 1.5f)
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale)
            ) {
                MaterialTheme(colorScheme = DarkThemePlugin.colors) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen()
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
        val n = minOf(lats.size, lons.size, types.size)
        if (n == 0) return
        val records = buildList {
            for (i in 0 until n) {
                val lat = lats[i]
                val lon = lons[i]
                if (!lat.isFinite() || !lon.isFinite() ||
                    lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0
                ) continue
                val type = runCatching { ThreatType.valueOf(types[i]) }.getOrNull() ?: continue
                add(FlourishRecord(lat, lon, type))
            }
        }
        if (records.isNotEmpty()) viewModel.triggerFlourish(records)
        // Reset the tally now that the show was replayed — same reset as swiping it away.
        runCatching {
            startService(
                Intent(this, AlertService::class.java)
                    .setAction(NeutralizedTally.ACTION_NEUTRALIZED_DISMISS)
            )
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
            if (prefs.legacyCacheCleaned().first()) return@launch
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
        ConnectionHolder.getClient(this).onForeground()
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
     * and (for new users) the battery prompt have resolved. Returning users who already
     * finished onboarding get the request immediately.
     */
    private fun deferPermissionRequests() {
        lifecycleScope.launch {
            val prefs = UserPrefs(applicationContext)
            // Re-arm for this session — a previous "Later" deferral only lasts one launch.
            prefs.setPermissionPromptDeferred(false)
            val wizardDone = prefs.wizardCompleted().first()
            val ready = if (wizardDone && prefs.batteryOnboardShown().first()) {
                !prefs.permissionPromptDeferred().first()
            } else {
                combine(
                    prefs.wizardCompleted(),
                    prefs.batteryOnboardShown(),
                    prefs.permissionPromptDeferred()
                ) { w, b, d -> w && b && !d }.first { it }
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
