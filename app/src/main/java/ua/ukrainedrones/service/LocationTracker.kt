package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared, battery-first device location. One listener owned by the foreground service so the
 * UI and the alert logic read the same fix. The red/yellow zones are km-scale, so a coarse
 * fix is plenty: ~2-minute updates, only when the device actually moves >250 m. Falls back to
 * the last known fix so zone circles keep drawing while indoors.
 *
 * When periodic GPS is enabled, wakes GPS for a few seconds every 15 minutes to calibrate and
 * prevent cell-tower drift.
 */
object LocationTracker {
    private const val UPDATE_INTERVAL_MS = 120_000L
    private const val MIN_DISTANCE_METERS = 250f
    private const val PERIODIC_GPS_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    const val MAX_LOCATION_AGE_MS = 15 * 60 * 1000L // 15 minutes freshness threshold

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
            pickLastKnown(app)?.let { recordFix(it) }

            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val looper = Looper.getMainLooper()

            // Network provider only: the alert zones are km-scale, so a coarse fix is
            // plenty, and skipping GPS keeps the radio off (battery-cheapest).
            val requested = runCatching {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_INTERVAL_MS, MIN_DISTANCE_METERS, l, looper)
            }
            if (requested.isSuccess) {
                started = true
            }

            // Periodic 15-min GPS sync loop when user enabled it
            startPeriodicGpsLoop(app)
        } catch (_: SecurityException) {
            _location.value = null
        }
    }

    private fun startPeriodicGpsLoop(app: Context) {
        periodicJob?.cancel()
        val prefs = UserPrefs(app)
        periodicJob = scope.launch {
            prefs.periodicGps().collectLatest { enabled ->
                if (enabled) {
                    while (isActive) {
                        delay(PERIODIC_GPS_INTERVAL_MS)
                        forceRefresh()
                    }
                }
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
        if (!fine) {
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

        // Safety timeout for GPS (8s), falling back to network (4s)
        scope.launch {
            delay(8_000L)
            if (!completed.get()) {
                tryNetworkFallback()
                delay(4_000L)
                finish(null)
            }
        }

        val requestedGps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(ctx)
                ) { loc ->
                    if (loc != null) finish(loc) else tryNetworkFallback()
                }
            } else {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, object : LocationListener {
                    override fun onLocationChanged(loc: Location) { finish(loc) }
                    override fun onProviderDisabled(provider: String) { tryNetworkFallback() }
                }, Looper.getMainLooper())
            }
        }

        if (requestedGps.isFailure) {
            tryNetworkFallback()
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
        started = false
    }

    private fun recordFix(loc: Location) {
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
    }

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pickLastKnown(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        return when {
            net == null -> gps
            gps == null -> net
            gps.time > net.time -> gps
            else -> net
        }
    }
}
