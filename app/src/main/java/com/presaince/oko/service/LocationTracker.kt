package com.presaince.oko

import com.presaince.oko.engine.LatLng

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared, battery-first device location. One listener owned by the foreground service so the
 * UI and the alert logic read the same fix. The red/yellow zones are km-scale, so a coarse
 * fix is plenty: passive copies of fixes other apps request are always live (zero extra
 * radio). Our own network subscription is only kept while the screen is on (2-min / 0-m,
 * so it stays live while you're moving the phone) and dropped when the screen is off —
 * polling in your pocket all night adds no zone value. Falls back to the last known
 * persisted fix so zone circles keep drawing while indoors.
 *
 * When periodic GPS is enabled, wakes GPS for a few seconds every 15 minutes — and only
 * while the screen is on — to calibrate and prevent cell-tower drift.
 */
object LocationTracker {
    private const val UPDATE_INTERVAL_MS = 120_000L
    private const val MIN_DISTANCE_METERS = 250f
    private const val PERIODIC_GPS_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    private const val GPS_ATTEMPT_MS = 8_000L
    private const val MAX_GPS_ATTEMPTS = 3
    private const val NETWORK_FALLBACK_MS = 6_000L
    private const val MAX_NETWORK_SEED_ATTEMPTS = 4
    private const val NETWORK_SEED_INTERVAL_MS = 8_000L
    const val MAX_LOCATION_AGE_MS = 15 * 60 * 1000L // 15 minutes freshness threshold

    // Last-known fix persisted so a force-stopped relaunch knows where the user was
    // instantly: the focus/token resolve from yesterday's fix (staleness is fine —
    // a possibly-old position beats a null one) while a live fix is re-acquired.
    private const val PERSIST_PREFS = "oko_last_location"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_FIX_MS = "fix_ms"

    private val _location = MutableStateFlow<LatLng?>(null)
    val location: StateFlow<LatLng?> = _location.asStateFlow()

    private val _lastFixAtMs = MutableStateFlow<Long?>(null)
    val lastFixAtMs: StateFlow<Long?> = _lastFixAtMs.asStateFlow()

    private val _lastReceivedAtMs = MutableStateFlow<Long?>(null)
    val lastReceivedAtMs: StateFlow<Long?> = _lastReceivedAtMs.asStateFlow()

    private val _lastPreciseFixAtMs = MutableStateFlow<Long?>(null)
    val lastPreciseFixAtMs: StateFlow<Long?> = _lastPreciseFixAtMs.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var periodicJob: Job? = null

    @Volatile
    private var started = false
    private var appContext: Context? = null
    private var listener: LocationListener? = null
    private var networkListener: LocationListener? = null
    private var screenReceiver: BroadcastReceiver? = null

    private fun isScreenOn(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    fun isFresh(now: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_LOCATION_AGE_MS): Boolean {
        val fixTime = _lastFixAtMs.value ?: return false
        val rxTime = _lastReceivedAtMs.value ?: return false
        return (now - fixTime) in 0..maxAgeMs && (now - rxTime) in 0..maxAgeMs
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        if (started) return
        if (!hasPermission(app)) return

        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                recordFix(loc)
            }
        }
        listener = l

        try {
            // Persisted fix first (survives force-stop), then the platform last-known.
            hydratePersisted(app)
            pickLastKnown(app)?.let { recordFix(it) }

            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val looper = Looper.getMainLooper()

            // Passive copies of other apps' fixes are always live (zero extra radio).
            // Our own network subscription is only kept while the screen is on — polling
            // in your pocket all night adds no zone value and the cheap periodic GPS
            // sync still covers the drift case.
            val passiveOk = subscribeProvider(lm, LocationManager.PASSIVE_PROVIDER, l, looper)
            if (passiveOk) started = true
            applyScreenState(lm, looper)

            // No fresh fix → retry a cheap network one-shot (cell-tower baseline) until the provider warms
            // at cold start, and while following kick the precise GPS retry so the fix upgrades
            // once satellites lock.
            if (!isFresh()) {
                // Retry the network seed so it catches a cell fix once the provider warms at
                // cold start.  GPS kick runs in parallel via forceRefresh and has its own
                // retry loop; the network loop stops as soon as a fresh fix lands (from either).
                scope.launch {
                    repeat(MAX_NETWORK_SEED_ATTEMPTS) {
                        if (isFresh()) return@launch
                        requestNetworkFix(app)
                        delay(NETWORK_SEED_INTERVAL_MS)
                    }
                }
                scope.launch {
                    if (UserPrefs(app).preferences.first().followMe) forceRefresh()
                }
            }

            registerScreenReceiver(lm, app)
            // Periodic 15-min GPS sync loop when user enabled it
            startPeriodicGpsLoop(app)
        } catch (_: SecurityException) {
            _location.value = null
        }
    }

    private fun subscribeProvider(lm: LocationManager, provider: String, l: LocationListener, looper: Looper, minDistance: Float = MIN_DISTANCE_METERS): Boolean =
        runCatching {
            if (lm.isProviderEnabled(provider)) {
                lm.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, minDistance, l, looper)
                true
            } else {
                false
            }
        }.getOrDefault(false)

    /** Own network subscription: 2-min while the screen is on (responsive), dropped when off. */
    private fun applyScreenState(lm: LocationManager, looper: Looper) {
        val ctx = appContext ?: return
        val on = isScreenOn(ctx)
        if (on && networkListener == null) {
            val net = object : LocationListener {
                override fun onLocationChanged(loc: Location) { recordFix(loc) }
            }
            networkListener = net
            subscribeProvider(lm, LocationManager.NETWORK_PROVIDER, net, looper, minDistance = 0f)
            if (!isFresh()) snapNow()
        } else if (!on && networkListener != null) {
            val net = networkListener
            networkListener = null
            net?.let { runCatching { lm.removeUpdates(it) } }
        }
    }

    /** Instant fix from platform last-known + fresh network one-shot — screen-on path. */
    private fun snapNow() {
        val ctx = appContext ?: return
        pickLastKnown(ctx)?.let { recordFix(it) }
        requestNetworkFix(ctx)
    }

    private fun registerScreenReceiver(lm: LocationManager, app: Context) {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val i = intent ?: return
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> applyScreenState(lm, Looper.getMainLooper())
                    Intent.ACTION_SCREEN_OFF -> {
                        networkListener?.let {
                            runCatching { lm.removeUpdates(it) }
                            networkListener = null
                        }
                    }
                }
            }
        }
        runCatching { app.registerReceiver(screenReceiver, f) }
    }

    private fun startPeriodicGpsLoop(app: Context) {
        periodicJob?.cancel()
        val prefs = UserPrefs(app)
        periodicJob = scope.launch {
            prefs.preferences.map { it.periodicGps }.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) {
                    while (isActive) {
                        delay(PERIODIC_GPS_INTERVAL_MS)
                        // Never grab a satellite lock with the screen off — pocket battery drain.
                        if (isScreenOn(app)) forceRefresh()
                    }
                }
            }
        }
    }

    /** Cheap network one-shot for a fast baseline fix at start (no GPS, coarse is plenty). */
    private fun requestNetworkFix(app: Context) {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cs = CancellationSignal()
                lm.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER,
                    cs,
                    ContextCompat.getMainExecutor(app)
                ) { loc ->
                    if (loc != null) recordFix(loc)
                }
                scope.launch {
                    delay(NETWORK_FALLBACK_MS)
                    cs.cancel()
                }
            } else {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                    override fun onLocationChanged(loc: Location) { recordFix(loc) }
                }, Looper.getMainLooper())
            }
        }
    }

    /** Requests a precise one-shot fix (GPS, falling back to network) for shelters / calibration. */
    fun forceRefresh(onComplete: (() -> Unit)? = null) {
        val ctx = appContext ?: run {
            onComplete?.invoke()
            return
        }
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            onComplete?.invoke()
            return
        }

        _isRefreshing.value = true
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fun finish(loc: Location?) {
            if (completed.compareAndSet(false, true)) {
                _isRefreshing.value = false
                if (loc != null) recordFix(loc)
                onComplete?.invoke()
            }
        }

        fun tryNetworkFallback() {
            if (completed.get()) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        CancellationSignal(),
                        ContextCompat.getMainExecutor(ctx)
                    ) { loc -> finish(loc) }
                } else {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                        override fun onLocationChanged(loc: Location) { finish(loc) }
                        override fun onProviderDisabled(provider: String) { finish(null) }
                    }, Looper.getMainLooper())
                }
            }.onFailure {
                finish(null)
            }
        }

        if (fine) {
            // A continuous GPS listener beats short one-shots: it keeps the radio armed
            // the whole window instead of cancelling every attempt, so a cold/poor signal
            // (old devices, indoors) actually has time to lock. Removed once a fix lands
            // or the window ends, then network fallback.
            scope.launch {
                val gpsListener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (loc != null) finish(loc)
                    }

                    override fun onProviderDisabled(provider: String) { }
                }
                runCatching {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, gpsListener, Looper.getMainLooper())
                }
                delay(GPS_ATTEMPT_MS * MAX_GPS_ATTEMPTS)
                runCatching { lm.removeUpdates(gpsListener) }
                if (!completed.get()) {
                    tryNetworkFallback()
                    delay(NETWORK_FALLBACK_MS)
                    if (!completed.get()) finish(null)
                }
            }
        } else {
            // Coarse-only: straight to the network one-shot, single timeout
            tryNetworkFallback()
            scope.launch {
                delay(NETWORK_FALLBACK_MS)
                if (!completed.get()) finish(null)
            }
        }
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        val ctx = appContext ?: return
        listener?.let {
            runCatching {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(it)
            }
        }
        listener = null
        networkListener?.let {
            runCatching {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(it)
            }
        }
        networkListener = null
        screenReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        screenReceiver = null
        started = false
    }

    private fun recordFix(loc: Location) {
        if (!isInsideUkraine(loc.latitude, loc.longitude)) return
        _location.value = LatLng(loc.latitude, loc.longitude)
        val now = System.currentTimeMillis()
        val fixTime = if (loc.time > 0L) loc.time else now
        _lastFixAtMs.value = fixTime
        _lastReceivedAtMs.value = now
        val isGps = loc.provider == LocationManager.GPS_PROVIDER || (loc.hasAccuracy() && loc.accuracy < 35f)
        if (isGps) {
            _lastPreciseFixAtMs.value = fixTime
        }
        _isRefreshing.value = false
        persistFix(loc.latitude, loc.longitude, fixTime)
    }

    /** Restore the last fix recorded before the process died (force-stop / reboot).
     *  Received-time is deliberately left at the fix time so [isFresh] still reports
     *  stale — callers alert on the old position, they just know it's old. */
    private fun hydratePersisted(app: Context) {
        if (_location.value != null) return
        runCatching {
            val prefs = app.getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON) || !prefs.contains(KEY_FIX_MS)) return
            val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return
            val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return
            val fixMs = prefs.getString(KEY_FIX_MS, null)?.toLongOrNull() ?: return
            if (!lat.isFinite() || !lon.isFinite()) return
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            if (!isInsideUkraine(lat, lon)) return
            _location.value = LatLng(lat, lon)
            _lastFixAtMs.value = fixMs
            _lastReceivedAtMs.value = fixMs
        }
    }

    private fun persistFix(lat: Double, lon: Double, fixMs: Long) {
        val app = appContext ?: return
        runCatching {
            app.getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAT, lat.toString())
                .putString(KEY_LON, lon.toString())
                .putString(KEY_FIX_MS, fixMs.toString())
                .apply()
        }
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pickLastKnown(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?.takeIf { isInsideUkraine(it.latitude, it.longitude) }
        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?.takeIf { isInsideUkraine(it.latitude, it.longitude) }
        return when {
            net == null -> gps
            gps == null -> net
            gps.time > net.time -> gps
            else -> net
        }
    }
}
