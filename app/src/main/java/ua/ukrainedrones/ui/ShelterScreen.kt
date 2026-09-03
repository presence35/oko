package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** Nearest-shelter list: ranks the Odesa dataset by distance to the focus point and hands the
 *  chosen one off to the system map app. The header shows how fresh the location fix is and
 *  forces a precise GPS one-shot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelterScreen(
    lang: AppLanguage,
    focus: LatLng?,
    index: ShelterIndex?,
    withKids: Boolean,
    onWithKidsChange: (Boolean) -> Unit,
    sheltersEnabled: Boolean,
    onSheltersEnabledChange: (Boolean) -> Unit,
    now: Long,
    onBack: () -> Unit
) {
    val s = Strings.get(lang)
    val context = LocalContext.current
    val lastFixMs by LocationTracker.lastFixAtMs.collectAsState()
    val lastPreciseFixMs by LocationTracker.lastPreciseFixAtMs.collectAsState()
    val trackerRefreshing by LocationTracker.isRefreshing.collectAsState()
    var gpsRefreshing by remember { mutableStateOf(false) }

    // Reset GPS spinner when a new fix arrives or tracker finishes
    LaunchedEffect(lastFixMs, trackerRefreshing) {
        if (!trackerRefreshing) {
            gpsRefreshing = false
        }
    }

    // Auto-reset GPS spinner after 10s timeout if no fix received
    LaunchedEffect(gpsRefreshing) {
        if (gpsRefreshing) {
            delay(10_000)
            gpsRefreshing = false
        }
    }

    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    // Android 12+ drops a re-request of ACCESS_FINE_LOCATION alone once the user already
    // picked approximate (COARSE granted) — it must be requested together with COARSE to
    // show the Precise/Approximate upgrade dialog.
    var showSettingsFallback by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            showSettingsFallback = false
            gpsRefreshing = true
            LocationTracker.forceRefresh { gpsRefreshing = false }
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(context as Activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // System no longer shows the dialog — route the user to Settings.
            showSettingsFallback = true
        }
    }
    val forceGps: () -> Unit = {
        showSettingsFallback = false
        if (fineGranted) {
            gpsRefreshing = true
            LocationTracker.forceRefresh { gpsRefreshing = false }
        } else {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }
    val near = if (focus != null && index != null) index.nearest(focus.lat, focus.lon, 30) else emptyList()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.shelterScreenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.backButton)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GpsHeaderRow(
                    lastFixMs = lastFixMs,
                    lastPreciseFixMs = lastPreciseFixMs,
                    isRefreshing = gpsRefreshing || trackerRefreshing,
                    now = now,
                    lang = lang,
                    s = s,
                    withKids = withKids,
                    onWithKidsChange = onWithKidsChange,
                    sheltersEnabled = sheltersEnabled,
                    onSheltersEnabledChange = onSheltersEnabledChange,
                    onForceRefresh = forceGps,
                    showSettingsFallback = showSettingsFallback,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }
                )
            }
            if (near.isEmpty()) {
                item {
                    Text(
                        s.shelterEmpty,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp)
                    )
                }
            } else {
                items(near) { row ->
                    val point = row.shelter
                    val uri = "geo:${point.lat},${point.lon}?q=${point.lat},${point.lon}"
                    ShelterCard(
                        lang = lang,
                        row = row,
                        s = s,
                        withKids = withKids,
                        onOpenInMaps = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GpsHeaderRow(
    lastFixMs: Long?,
    lastPreciseFixMs: Long?,
    isRefreshing: Boolean,
    now: Long,
    lang: AppLanguage,
    s: Strings.StringSet,
    withKids: Boolean,
    onWithKidsChange: (Boolean) -> Unit,
    sheltersEnabled: Boolean,
    onSheltersEnabledChange: (Boolean) -> Unit,
    onForceRefresh: () -> Unit,
    showSettingsFallback: Boolean,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val onCalibrate: () -> Unit = {
        showToast(context, s.calibratingGps, cardVisible = false)
        onForceRefresh()
    }
    val label = if (lastPreciseFixMs != null) {
        val ageMin = ((now - lastPreciseFixMs) / 60_000).coerceAtLeast(0)
        if (ageMin <= 0) s.gpsFixFresh else preciseGpsAgePhrase(ageMin, lang)
    } else if (lastFixMs != null) {
        s.networkLocationOnly
    } else {
        s.shelterGpsUnknown
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Place,
                    contentDescription = null,
                    tint = if (lastPreciseFixMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isRefreshing) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onCalibrate,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            s.calibrateGpsNow,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Map,
                    contentDescription = null,
                    tint = if (sheltersEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    s.shelterShowOnMap,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = sheltersEnabled,
                    onCheckedChange = onSheltersEnabledChange
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (adultIcon, childIcon) = walkIconPair(withKids)
                val tint = if (withKids) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.width(26.dp)
                ) {
                    WalkFigureIcon(adultIcon, height = 22.dp, tint = tint)
                    if (childIcon != null) {
                        WalkFigureIcon(childIcon, height = 13.dp, tint = tint)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        s.shelterWithKidsTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        s.shelterWithKidsDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = withKids,
                    onCheckedChange = onWithKidsChange
                )
            }
            if (showSettingsFallback) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.gpsPreciseBlocked,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onOpenSettings) {
                        Text(s.gpsOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelterCard(
    lang: AppLanguage,
    row: NearestShelter,
    s: Strings.StringSet,
    withKids: Boolean,
    onOpenInMaps: () -> Unit
) {
    val name = if (lang == AppLanguage.EN) shelterNameEn(row.shelter.name) else row.shelter.name
    val type = row.shelter.type
    val typeColor = when (type) {
        ShelterType.MOBILE -> androidx.compose.ui.graphics.Color(0xFFFFA000)
        ShelterType.BUNKER -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        ShelterType.BASIC -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    }
    val typeLabel = when (type) {
        ShelterType.MOBILE -> s.shelterTypeMobile
        ShelterType.BUNKER -> s.shelterTypeBunker
        ShelterType.BASIC -> s.shelterTypeBasic
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(typeColor)
                    )
                    Text(
                        typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )
                }
                Text(
                    formatDistance(row.distanceMeters, s),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val (walkAdultIcon, walkChildIcon) = walkIconPair(withKids)
                    WalkRow(
                        adultIcon = walkAdultIcon,
                        childIcon = null,
                        text = String.format(s.shelterWalkMinutes, row.walkMinutesAdult),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (withKids) {
                        WalkRow(
                            adultIcon = walkAdultIcon,
                            childIcon = walkChildIcon,
                            text = String.format(s.shelterWalkMinutes, row.walkMinutesKid),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenInMaps)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        s.shelterOpenInMaps,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkRow(adultIcon: Int, childIcon: Int?, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            WalkFigureIcon(adultIcon, height = 24.dp, tint = tint)
            if (childIcon != null) {
                WalkFigureIcon(childIcon, height = 14.dp, tint = tint)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDistance(meters: Double, s: Strings.StringSet): String =
    if (meters < 1000) String.format(s.shelterDistanceM, meters.toInt())
    else String.format(s.shelterDistanceKm, meters / 1000.0)
