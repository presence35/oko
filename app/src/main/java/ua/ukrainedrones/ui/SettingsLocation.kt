package ua.ukrainedrones

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import ua.ukrainedrones.City

@Composable
internal fun PinCityRow(
    lang: AppLanguage,
    pinnedCity: City?,
    onChange: (City?) -> Unit
) {
    val s = Strings.get(lang)
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            s.pinCityTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            s.pinCityDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        CityChipGrid(lang, selected = pinnedCity, onChange = onChange)
    }
}

@Composable
internal fun GpsCalibrationRow(
    lang: AppLanguage,
    s: Strings.StringSet
) {
    val context = LocalContext.current
    val lastFixMs by LocationTracker.lastFixAtMs.collectAsState()
    val lastPreciseFixMs by LocationTracker.lastPreciseFixAtMs.collectAsState()
    val isRefreshing by LocationTracker.isRefreshing.collectAsState()
    var localRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(lastFixMs, isRefreshing) {
        if (!isRefreshing) localRefreshing = false
    }

    LaunchedEffect(localRefreshing) {
        if (localRefreshing) {
            delay(10_000)
            localRefreshing = false
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
            localRefreshing = true
            showToast(s.calibratingGps, cardVisible = false)
            LocationTracker.forceRefresh { localRefreshing = false }
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(context as Activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // System no longer shows the dialog — route the user to Settings.
            showSettingsFallback = true
        }
    }
    val forceGps: () -> Unit = {
        showSettingsFallback = false
        if (fineGranted) {
            localRefreshing = true
            showToast(s.calibratingGps, cardVisible = false)
            LocationTracker.forceRefresh { localRefreshing = false }
        } else {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val preciseFixMs = lastPreciseFixMs
    val statusText = if (preciseFixMs != null) {
        val now = System.currentTimeMillis()
        val age = formatAlertAge(now, preciseFixMs, s)
        String.format(s.lastGpsFixFormat, if (age.isBlank()) s.gpsFixJustNow else age)
    } else if (lastFixMs != null) {
        s.networkLocationOnly
    } else {
        s.shelterGpsUnknown
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = null,
                tint = if (lastPreciseFixMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.gpsStatusTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isRefreshing || localRefreshing) {
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
                    onClick = forceGps,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
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
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }
                ) {
                    Text(s.gpsOpenSettings)
                }
            }
        }
    }
}
