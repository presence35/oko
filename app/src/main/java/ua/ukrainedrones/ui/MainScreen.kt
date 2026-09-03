package ua.ukrainedrones
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.distanceFlat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription as semanticsContentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

private enum class Screen { MAP, SETTINGS, GUIDE, SHELTERS, LOGS }

private val UkraineBlue = Color(0xFF005BBB)
private val UkraineYellow = Color(0xFFFFD500)
private val AlertRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val now by viewModel.now.collectAsState()
    val context = LocalContext.current

    var screen by remember { mutableStateOf(Screen.MAP) }
    // Notification tap: navigate to the map screen regardless of current tab.
    val navigateToMapTick by viewModel.navigateToMapTick.collectAsState()
    LaunchedEffect(navigateToMapTick) {
        if (navigateToMapTick > 0) screen = Screen.MAP
    }
    // The neutralizing card + map death flourish only run while the map is the visible
    // screen — off-map (Settings/Shelters/Guide) the popup just closes silently.
    LaunchedEffect(screen) { viewModel.setMapVisible(screen == Screen.MAP) }
    // Foreground tracking for the flyby gate: unlike the tab state above, this follows the
    // process — a backgrounded app must not "play" the animation nobody can see.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setAppForeground(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setAppForeground(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showConnectionInfo by remember { mutableStateOf(false) }
    var showZonesSheet by remember { mutableStateOf(false) }
    var activeExplainer by remember { mutableStateOf<Explainer?>(null) }

    // The Settings-open update check surfaces here as a snackbar with a Download action.
    val updateReminderTick by viewModel.updateReminderTick.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(updateReminderTick) {
        if (updateReminderTick > 0) {
            val s = Strings.get(uiState.language)
            val result = snackbarHostState.showSnackbar(
                message = String.format(s.updateAvailableOnOpen, uiState.latestVersion.orEmpty()),
                actionLabel = s.updateDownload,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.showDownloadScreen()
        }
    }

    // Only one overlay can be up at a time: opening any of them closes the others (and the
    // threat popup), and an arriving update dialog outranks everything.
    LaunchedEffect(showConnectionInfo, showZonesSheet, uiState.update) {
        if (showConnectionInfo || showZonesSheet || uiState.update !is UpdateState.Idle) {
            viewModel.selectThreat(null)
        }
        if (showConnectionInfo) {
            showZonesSheet = false
            activeExplainer = null
        }
        if (showZonesSheet) showConnectionInfo = false
        if (uiState.update !is UpdateState.Idle) {
            showConnectionInfo = false
            showZonesSheet = false
            activeExplainer = null
        }
    }

    // The System-status sheet never lingers over the map: it closes when an alert starts and
    // whenever the user navigates to Settings/Guide, so returning to the map is always clean.
    LaunchedEffect(screen, uiState.activeZone, uiState.focusOblastAlertActive) {
        if (screen != Screen.MAP || uiState.activeZone != null || uiState.focusOblastAlertActive) {
            showConnectionInfo = false
        }
    }

    // The settings heart pulses gently until Settings has been opened 10 times.
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPrefs(context.applicationContext) }
    var settingsHintRemaining by remember { mutableStateOf(0) }
    var shelterTipStage by remember { mutableStateOf(0) }
    var guideFeatureId by remember { mutableStateOf<String?>(null) }
    var guideFromSettings by remember { mutableStateOf(false) }
    var sheltersFromSettings by remember { mutableStateOf(false) }
    var scrollToThreatsTick by remember { mutableStateOf(0) }
    var wizardFromSettings by remember { mutableStateOf(false) }
    var headerHeightPx by remember { mutableStateOf(0) }
    // The wizard owns the screen while it's up — the map is not even composed then (no tile
    // flash before it covers the viewport). While prefs are still loading (wizardCompleted
    // == null) neither map nor wizard composes, so the real pref decides — never a default.
    val prefsLoaded = uiState.wizardCompleted != null
    val wizardShown = uiState.wizardCompleted == false
    val wizardOwnsScreen = wizardShown && !wizardFromSettings
    val settingsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var settingsCollapse by rememberSaveable(stateSaver = SettingsCollapseState.Saver) { mutableStateOf(SettingsCollapseState()) }
    // The zones sheet edits whatever the map is currently showing: night settings while the
    // night window is active and separate night zones are enabled, day settings otherwise.
    val editingNight = uiState.nightActive && uiState.nightUseCustomZones
    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — the user can tap again if denied */ }
    fun armOrRequestPermission(armed: Boolean, action: (Boolean) -> Unit) {
        if (armed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        action(armed)
    }
    LaunchedEffect(Unit) {
        settingsHintRemaining = prefs.settingsHintRemaining().first()
        shelterTipStage = prefs.shelterTipStage().first()
    }

    val onExit: () -> Unit = {
        scope.launch { prefs.setMonitoringEnabled(false) }
        AlertService.stop(context)
        val activity = context as? Activity
        if (activity != null) activity.finishAffinity()
    }

    val openSettings: () -> Unit = {
        if (settingsHintRemaining > 0) {
            settingsHintRemaining--
            scope.launch { prefs.setSettingsHintRemaining(settingsHintRemaining) }
        }
        screen = Screen.SETTINGS
        // Settings always opens fully collapsed — no saved open/closed state across visits.
        settingsCollapse = SettingsCollapseState()
        viewModel.checkForUpdatesOnSettingsOpen()
    }

    // A notification tap reveals a threat on the map: leave Settings and show it.
    LaunchedEffect(uiState.revealRequest?.tick) {
        if (uiState.revealRequest != null && screen != Screen.MAP) screen = Screen.MAP
    }

    // Tally-tap replay flourish: open the map and close every modal so nothing steals focus
    // from the shot-down show (and nothing overlaps it).
    LaunchedEffect(uiState.flourish?.tick) {
        if (uiState.flourish != null) {
            screen = Screen.MAP
            showZonesSheet = false
            showConnectionInfo = false
            activeExplainer = null
            viewModel.selectThreat(null)
        }
    }

    // The map stays composed under the Settings overlay so its camera and tiles are never
    // destroyed — returning from Settings used to reset the world into a low-zoom grid.
    CompositionLocalProvider(LocalHapticsEnabled provides uiState.hapticsEnabled) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (!prefsLoaded) {
            // DataStore hasn't emitted yet — blank dark frame instead of guessing the state.
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        if (prefsLoaded && !wizardOwnsScreen) {
        MapScreen(
            uiState = uiState,
            selection = viewModel.selectionUi,
            selectedThreatId = viewModel.selectedThreatId,
            now = now,
            settingsOpen = screen == Screen.SETTINGS,
            mapVisible = screen == Screen.MAP && !wizardShown,
            onOpenSettings = openSettings,
            onOpenThreatSettings = {
                openSettings()
                // The shortcut scrolls to the night/threats card: expand it so the landing
                // isn't an empty collapsed header.
                settingsCollapse = if (uiState.nightActive) settingsCollapse.copy(nightMode = true)
                else settingsCollapse.copy(threats = true)
                scrollToThreatsTick++
            },
                        onThreatTapped = { showConnectionInfo = false; showZonesSheet = false; viewModel.selectThreat(it) },
            onFlourishEjected = viewModel::notifyFlourishEjected,
            onThreatStripTap = { viewModel.panToThreat(it) },
            onDismissPopup = { viewModel.selectThreat(null) },
            onMapTapped = { viewModel.selectThreat(null) },
            onSlowRedChange = { if (editingNight) viewModel.setNightSlowRedKm(it) else viewModel.setSlowRedKm(it) },
            onSlowYellowChange = { if (editingNight) viewModel.setNightSlowYellowKm(it) else viewModel.setSlowYellowKm(it) },
            onFastRedChange = { if (editingNight) viewModel.setNightFastRedMin(it) else viewModel.setFastRedMin(it) },
            onFastYellowChange = { if (editingNight) viewModel.setNightFastYellowMin(it) else viewModel.setFastYellowMin(it) },
            onSlowRedArmedChange = { armOrRequestPermission(it) { v -> if (editingNight) viewModel.setNightSlowRedArmed(v) else viewModel.setSlowRedArmed(v) } },
            onSlowYellowArmedChange = { armOrRequestPermission(it) { v -> if (editingNight) viewModel.setNightSlowYellowArmed(v) else viewModel.setSlowYellowArmed(v) } },
            onFastRedArmedChange = { armOrRequestPermission(it) { v -> if (editingNight) viewModel.setNightFastRedArmed(v) else viewModel.setFastRedArmed(v) } },
            onFastYellowArmedChange = { armOrRequestPermission(it) { v -> if (editingNight) viewModel.setNightFastYellowArmed(v) else viewModel.setFastYellowArmed(v) } },
            onThreatCardSizeChange = { viewModel.setThreatCardSize(it) },
            onNeutralize = { id -> viewModel.neutralizeThreat(id) },
            onFlybyFinished = { id -> viewModel.onFlybyFinished(id) },
            showConnectionInfo = showConnectionInfo,
            onShowConnectionInfoChange = { showConnectionInfo = it },
            showZonesSheet = showZonesSheet,
            onShowZonesSheetChange = { showZonesSheet = it },
            onOpenShelters = {
                sheltersFromSettings = false
                screen = Screen.SHELTERS
            },
            onOpenLogs = {
                showConnectionInfo = false
                screen = Screen.LOGS
            },
            onShelterModeChange = { viewModel.setShelterModeActive(it) },
            shelterTipStage = shelterTipStage,
            settingsHintRemaining = settingsHintRemaining,
            onHeaderHeightChange = { headerHeightPx = it },
            onShelterTipAdvance = {
                val next = (shelterTipStage + 1).coerceAtMost(6)
                shelterTipStage = next
                scope.launch { prefs.setShelterTipStage(next) }
            }
        )
        }
        if (screen == Screen.SETTINGS) {
            // Composed after MapScreen, so its handler is checked first on Back.
            BackHandler { screen = Screen.MAP }
            SettingsScreen(
                lang = uiState.language,
                listState = settingsListState,
                onThreatsScrollHandled = { scrollToThreatsTick = 0 },
                scrollToThreatsTick = scrollToThreatsTick,
                scrollToNightMode = uiState.nightActive,
                collapse = settingsCollapse,
                onCollapseChange = { settingsCollapse = it },
                hiddenTypes = uiState.hiddenTypes,
                silencedTypes = uiState.silencedTypes,
                officialAlertsEnabled = uiState.officialAlertsEnabled,
                officialAlertCityScope = uiState.officialAlertCityScope,
                sirenOverride = uiState.sirenOverride,
                criticalOfflineOverride = uiState.criticalOfflineOverride,
                criticalOfflineBypassSilent = uiState.criticalOfflineBypassSilent,
                nightEnabled = uiState.nightEnabled,
                nightStartMin = uiState.nightStartMin,
                nightEndMin = uiState.nightEndMin,
                nightUseCustomZones = uiState.nightUseCustomZones,
                slowRedKm = uiState.slowRedKm,
                slowYellowKm = uiState.slowYellowKm,
                fastRedMin = uiState.fastRedMin,
                fastYellowMin = uiState.fastYellowMin,
                nightSlowRedKm = uiState.nightSlowRedKm,
                nightSlowYellowKm = uiState.nightSlowYellowKm,
                nightFastRedMin = uiState.nightFastRedMin,
                nightFastYellowMin = uiState.nightFastYellowMin,
                nightSlowRedArmed = uiState.nightSlowRedArmed,
                nightSlowYellowArmed = uiState.nightSlowYellowArmed,
                nightFastRedArmed = uiState.nightFastRedArmed,
                nightFastYellowArmed = uiState.nightFastYellowArmed,
                nightZoneSirenOverride = uiState.nightZoneSirenOverride,
                nightOfficialSirenOverride = uiState.nightOfficialSirenOverride,
                disclaimerCollapsed = uiState.disclaimerCollapsed,
                disclaimerReadCount = uiState.disclaimerReadCount,
                followMe = uiState.followMe,
                pinnedCity = uiState.pinnedCity,
                threatCardSize = uiState.threatCardSize,
                iconSet = uiState.iconSet,
                showMapScale = uiState.showMapScale,
                showMediumCities = uiState.showMediumCities,
                showSmallCities = uiState.showSmallCities,
                sheltersEnabled = uiState.sheltersEnabled,
                periodicGps = uiState.periodicGps,
                calmMessagesEnabled = uiState.calmMessagesEnabled,
                hapticsEnabled = uiState.hapticsEnabled,
                deathAnimationEnabled = uiState.deathAnimationEnabled,
                flybyAnimationEnabled = uiState.flybyAnimationEnabled,
                followBullet = uiState.followBullet,
                neutralizedTallyEnabled = uiState.neutralizedTallyEnabled,
                neutralizedTallyAllUkraine = uiState.neutralizedTallyAllUkraine,
                fastGroupCollapsed = uiState.fastGroupCollapsed,
                slowGroupCollapsed = uiState.slowGroupCollapsed,
                versionName = BuildConfig.VERSION_NAME,
                isChecking = uiState.update is UpdateState.Checking,
                latestVersion = uiState.latestVersion,
                onBack = { screen = Screen.MAP },
                activeExplainer = activeExplainer,
                onExplainerChange = { activeExplainer = it },
                onLanguageChange = { viewModel.setLanguage(it) },
                onThreatMapToggle = { type, visible -> viewModel.setThreatMapVisible(type, visible) },
                onThreatAlertToggle = { type, enabled -> viewModel.setThreatAlertsEnabled(type, enabled) },
                onThreatMapToggleAll = { types, visible -> viewModel.setGroupThreatMapVisible(types, visible) },
                onThreatAlertToggleAll = { types, enabled -> viewModel.setGroupThreatAlertsEnabled(types, enabled) },
                onOfficialAlertsChange = { viewModel.setOfficialAlertsEnabled(it) },
                onOfficialAlertCityScopeChange = { viewModel.setOfficialAlertCityScope(it) },
                onSirenOverrideChange = { viewModel.setSirenOverride(it) },
                onCriticalOfflineOverrideChange = { viewModel.setCriticalOfflineOverride(it) },
                onCriticalOfflineBypassSilentChange = { viewModel.setCriticalOfflineBypassSilent(it) },
                onNightEnabledChange = { viewModel.setNightEnabled(it) },
                onNightStartChange = { viewModel.setNightStartMin(it) },
                onNightEndChange = { viewModel.setNightEndMin(it) },
                onNightUseCustomZonesChange = { viewModel.setNightUseCustomZones(it) },
                onNightSlowRedChange = { viewModel.setNightSlowRedKm(it) },
                onNightSlowYellowChange = { viewModel.setNightSlowYellowKm(it) },
                onNightFastRedChange = { viewModel.setNightFastRedMin(it) },
                onNightFastYellowChange = { viewModel.setNightFastYellowMin(it) },
                onNightSlowRedArmedChange = { armOrRequestPermission(it) { v -> viewModel.setNightSlowRedArmed(v) } },
                onNightSlowYellowArmedChange = { armOrRequestPermission(it) { v -> viewModel.setNightSlowYellowArmed(v) } },
                onNightFastRedArmedChange = { armOrRequestPermission(it) { v -> viewModel.setNightFastRedArmed(v) } },
                onNightFastYellowArmedChange = { armOrRequestPermission(it) { v -> viewModel.setNightFastYellowArmed(v) } },
                onNightZoneSirenOverrideChange = { viewModel.setNightZoneSirenOverride(it) },
                onNightOfficialSirenOverrideChange = { viewModel.setNightOfficialSirenOverride(it) },
                onFollowMeChange = { viewModel.setFollowMe(it) },
                onPinnedCityChange = { viewModel.setPinnedCity(it) },
                onPeriodicGpsChange = { viewModel.setPeriodicGps(it) },
                onCalmMessagesChange = { viewModel.setCalmMessagesEnabled(it) },
                onHapticsEnabledChange = { viewModel.setHapticsEnabled(it) },
                onDisclaimerCollapse = { viewModel.setDisclaimerCollapsed(it) },
                onDisclaimerShown = { viewModel.onDisclaimerShown() },
                onThreatCardSizeChange = { viewModel.setThreatCardSize(it) },
                onIconSetChange = { viewModel.setThreatIconSet(it) },
                onShowMapScaleChange = { viewModel.setShowMapScale(it) },
                onShowMediumCitiesChange = { viewModel.setShowMediumCities(it) },
                onShowSmallCitiesChange = { viewModel.setShowSmallCities(it) },
                onSheltersEnabledChange = { viewModel.setSheltersEnabled(it) },
                onOpenShelterList = {
                sheltersFromSettings = true
                screen = Screen.SHELTERS
            },
                justFunMasterEnabled = uiState.justFunMasterEnabled,
                onJustFunMasterChange = { viewModel.setJustFunEnabled(it) },
                onDeathAnimationChange = { viewModel.setDeathAnimationEnabled(it) },
                onFlybyAnimationChange = { viewModel.setFlybyAnimationEnabled(it) },
                onFollowBulletChange = { viewModel.setFollowBullet(it) },
                onNeutralizedTallyChange = { viewModel.setNeutralizedTallyEnabled(it) },
                onNeutralizedTallyAllUkraineChange = { viewModel.setNeutralizedTallyAllUkraine(it) },
                onFastGroupCollapse = { viewModel.setFastGroupCollapsed(it) },
                onSlowGroupCollapse = { viewModel.setSlowGroupCollapsed(it) },
                onExit = onExit,
                onCheckUpdate = { viewModel.checkForUpdates() },
                onRelaunchSetup = {
                    viewModel.relaunchSetup()
                    wizardFromSettings = true
                },
                onResetTips = {
                    viewModel.resetAllTips()
                    // Re-arm the in-memory hint counters so the gear re-pulses immediately.
                    settingsHintRemaining = 3
                    showToast(context, Strings.get(uiState.language).tipsResetToast, cardVisible = false)
                },
                onOpenGuide = {
                    guideFromSettings = true
                    guideFeatureId = null
                    screen = Screen.GUIDE
                }
            )
        }
        if (screen == Screen.GUIDE) {
            BackHandler { screen = if (guideFromSettings) Screen.SETTINGS else Screen.MAP }
            FeatureGuideScreen(
                s = Strings.get(uiState.language),
                initialFeatureId = guideFeatureId,
                onBack = { screen = if (guideFromSettings) Screen.SETTINGS else Screen.MAP }
            )
        }
        if (screen == Screen.SHELTERS) {
            BackHandler { screen = if (sheltersFromSettings) Screen.SETTINGS else Screen.MAP }
            ShelterScreen(
                lang = uiState.language,
                focus = uiState.focusLocation,
                index = uiState.shelterIndex,
                withKids = uiState.sheltersWithKids,
                onWithKidsChange = { viewModel.setSheltersWithKidsEnabled(it) },
                sheltersEnabled = uiState.sheltersEnabled,
                onSheltersEnabledChange = { viewModel.setSheltersEnabled(it) },
                now = now,
                onBack = { screen = if (sheltersFromSettings) Screen.SETTINGS else Screen.MAP }
            )
        }
        if (screen == Screen.LOGS) {
            BackHandler { screen = Screen.MAP }
            LogsScreen(
                s = Strings.get(uiState.language),
                lang = uiState.language,
                iconSet = uiState.iconSet,
                onBack = { screen = Screen.MAP }
            )
        }
        SwipeableSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
        ToastHost(topInset = with(LocalDensity.current) { headerHeightPx.toDp() })
    }

    // Auto-launch the installer once the APK is downloaded and permission is granted.
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onInstallResult(result.resultCode != Activity.RESULT_OK)
    }

    val updateState = uiState.update
    LaunchedEffect(updateState) {
        if (updateState is UpdateState.Downloaded && !uiState.needsInstallPermission) {
            val intent = viewModel.installIntent()
            if (intent != null) installLauncher.launch(intent)
        }
    }

    UpdateDialog(
        state = updateState,
        needsInstallPermission = uiState.needsInstallPermission,
        lang = uiState.language,
        onDownload = { viewModel.downloadUpdate() },
        onInstall = {
            val intent = viewModel.installIntent()
            if (intent != null) installLauncher.launch(intent)
        },
        onRetry = { viewModel.retryDownload() },
        onLater = { viewModel.dismissUpdate() },
        onOpenSettings = { viewModel.openInstallPermissionSettings() }
    )

    // First-install: a full-screen wizard — language (+tips), icon pack, which threats matter,
    // then a feature preview. Alerts are delivered as notifications/toasts while the wizard
    // is up, but the wizard no longer ejects — the user finishes onboarding first.
    if (wizardShown) {
        FirstLaunchWizard(
            current = uiState.language,
            iconSet = uiState.iconSet,
            hiddenTypes = uiState.hiddenTypes,
            silencedTypes = uiState.silencedTypes,
            followMe = uiState.followMe,
            pinnedCity = uiState.pinnedCity,
            slowRedKm = uiState.slowRedKm,
            slowYellowKm = uiState.slowYellowKm,
            slowRedArmed = uiState.slowRedArmed,
            slowYellowArmed = uiState.slowYellowArmed,
            fastRedArmed = uiState.fastRedArmed,
            fastYellowArmed = uiState.fastYellowArmed,
            sheltersEnabled = uiState.sheltersEnabled,
            justFun = uiState.justFunMasterEnabled,
            calmMessagesEnabled = uiState.calmMessagesEnabled,
            flybyAnimationEnabled = uiState.flybyAnimationEnabled,
            deathAnimationEnabled = uiState.deathAnimationEnabled,
            followBullet = uiState.followBullet,
            neutralizedTallyEnabled = uiState.neutralizedTallyEnabled,
            neutralizedTallyAllUkraine = uiState.neutralizedTallyAllUkraine,
            iconSetForFun = uiState.iconSet,
            onChoose = { viewModel.setLanguage(it) },
            onThreatEnabledToggle = { type, enabled -> viewModel.setThreatEnabled(type, enabled) },
            onFollowMeChange = { viewModel.setFollowMe(it) },
            onPinnedCityChange = { viewModel.setPinnedCity(it) },
            onJustFunChange = { viewModel.setJustFunEnabled(it) },
            onCalmMessagesChange = { viewModel.setCalmMessagesEnabled(it) },
            onFlybyAnimationChange = { viewModel.setFlybyAnimationEnabled(it) },
            onDeathAnimationChange = { viewModel.setDeathAnimationEnabled(it) },
            onFollowBulletChange = { viewModel.setFollowBullet(it) },
            onNeutralizedTallyChange = { viewModel.setNeutralizedTallyEnabled(it) },
            onNeutralizedTallyAllUkraineChange = { viewModel.setNeutralizedTallyAllUkraine(it) },
            onIconSetChangeForFun = { viewModel.setThreatIconSet(it) },
            onSlowRedChange = { viewModel.setSlowRedKm(it) },
            onSlowYellowChange = { viewModel.setSlowYellowKm(it) },
            onSlowRedArmedChange = { armOrRequestPermission(it) { v -> viewModel.setSlowRedArmed(v) } },
            onSlowYellowArmedChange = { armOrRequestPermission(it) { v -> viewModel.setSlowYellowArmed(v) } },
            onComplete = {
                viewModel.skipLanguageChoose()
                if (wizardFromSettings) {
                    wizardFromSettings = false
                    screen = Screen.MAP
                }
            },
            onLater = {
                viewModel.laterLanguageChoose()
                if (wizardFromSettings) {
                    wizardFromSettings = false
                    screen = Screen.MAP
                }
            }
        )
    }

    // Battery prompt: kept but not immediately after first launch. Shown only after the
    // wizard has been completed for a while, so first-run isn't a wall of system dialogs.
    val batteryExempt = remember { BatteryOptimization.isIgnoringBatteryOptimizations(context) }
    var batteryDeferDone by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.wizardCompleted) {
        if (uiState.wizardCompleted == true) {
            delay(120_000)
            batteryDeferDone = true
        }
    }
    LaunchedEffect(uiState.wizardCompleted, uiState.batteryOnboardShown, batteryExempt) {
        if (uiState.wizardCompleted == true && !uiState.batteryOnboardShown && batteryExempt) {
            viewModel.setBatteryOnboardShown(true)
        }
    }
    if (batteryDeferDone && uiState.wizardCompleted == true && !uiState.batteryOnboardShown && !batteryExempt) {
        BatteryOnboardingDialog(
            s = Strings.get(uiState.language),
            onAllow = {
                viewModel.setBatteryOnboardShown(true)
                BatteryOptimization.requestExemption(context)
            },
            onLater = { viewModel.setBatteryOnboardShown(true) }
        )
    }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen(
    uiState: UiState,
    selection: StateFlow<SelectionUi>,
    selectedThreatId: StateFlow<String?>,
    now: Long,
    settingsOpen: Boolean,
    mapVisible: Boolean,
    onOpenSettings: () -> Unit,
    onOpenThreatSettings: () -> Unit,
    onThreatTapped: (NormalizedThreat) -> Unit,
    onThreatStripTap: (NormalizedThreat) -> Unit,
    onDismissPopup: () -> Unit,
    onMapTapped: () -> Unit,
    onSlowRedChange: (Int) -> Unit,
    onSlowYellowChange: (Int) -> Unit,
    onFastRedChange: (Int) -> Unit,
    onFastYellowChange: (Int) -> Unit,
    onSlowRedArmedChange: (Boolean) -> Unit,
    onSlowYellowArmedChange: (Boolean) -> Unit,
    onFastRedArmedChange: (Boolean) -> Unit,
    onFastYellowArmedChange: (Boolean) -> Unit,
    onThreatCardSizeChange: (ThreatCardSize) -> Unit,
    onNeutralize: (String) -> Unit,
    onFlourishEjected: () -> Unit,
    onFlybyFinished: (String) -> Unit,
    showConnectionInfo: Boolean,
    onShowConnectionInfoChange: (Boolean) -> Unit,
    showZonesSheet: Boolean,
    onShowZonesSheetChange: (Boolean) -> Unit,
    onOpenShelters: () -> Unit,
    onOpenLogs: () -> Unit,
    onShelterModeChange: (Boolean) -> Unit,
    shelterTipStage: Int,
    onShelterTipAdvance: () -> Unit,
    settingsHintRemaining: Int = 0,
    onHeaderHeightChange: (Int) -> Unit = {}
) {
    val s = Strings.get(uiState.language)
    val context = LocalContext.current

    val lastPreciseFixMs by LocationTracker.lastPreciseFixAtMs.collectAsState()
    // The settings gear pulses gently while the "open Settings" hint is active (a rotation
    // reads as "loading"). Infinite transition = always animating, so the value is continuously
    // observed; the pulse is only applied while the hint counter is still positive.
    val gearHintActive = settingsHintRemaining > 0
    val gearPulseTransition = rememberInfiniteTransition(label = "gearPulse")
    val gearPulse by gearPulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gearPulse"
    )
    var fitUkraineTick by remember { mutableStateOf(0) }
    var scaleMpp by remember { mutableStateOf(0.0) }
    var zoomZone by remember { mutableStateOf<ThreatZone?>(null) }
    var zoomTick by remember { mutableStateOf(0) }
    var fitZonesTick by remember { mutableStateOf(0) }
    var shelterZoomTick by remember { mutableStateOf(0) }
    var shelterSelectTick by remember { mutableStateOf(0) }
    var showNearbyShelters by remember { mutableStateOf(false) }
    var selectedShelter by remember { mutableStateOf<NearestShelter?>(null) }
    var deathActive by remember { mutableStateOf(false) }
    var replayProgress by remember { mutableStateOf<ReplayProgress?>(null) }

    // Surface shelter-mode to the ViewModel so the resolved-threat flourish/card is
    // suppressed while the shelter overlay is up.
    LaunchedEffect(showNearbyShelters) { onShelterModeChange(showNearbyShelters) }

    val fineLocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LocationTracker.forceRefresh()
        }
    }

    val onToggleShelters: () -> Unit = {
        if (shelterTipStage < 6) {
            val tipText = when (shelterTipStage) {
                0, 1 -> s.shelterTapTip
                4, 5 -> s.shelterLongPressTip
                else -> null
            }
            if (tipText != null) {
                showToast(context, tipText, cardVisible = false)
            }
            onShelterTipAdvance()
        }
        val willShow = !showNearbyShelters
        showNearbyShelters = willShow
        if (!willShow) {
            selectedShelter = null
        } else {
            shelterZoomTick++
            // A fix younger than 5 minutes is fine to reuse — repeated toggling in a red
            // alert shouldn't hammer the GPS; the shelter list screen can force a fresh fix.
            val fixAgeMs = lastPreciseFixMs?.let { now - it }
            if (fixAgeMs == null || fixAgeMs >= 5 * 60_000L) {
                showToast(
                    context,
                    s.updatingPreciseGpsToast,
                    cardVisible = selection.value.selected != null || selectedShelter != null || showZonesSheet
                )
                val hasFine = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasFine) {
                    fineLocLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                LocationTracker.forceRefresh()
            }
        }
    }

    // The zones sheet edits whatever the map is currently showing.
    val editingNight = uiState.nightActive && uiState.nightUseCustomZones

    // Opening the panel also asks the map to centre + zoom to the full yellow zone.
    val openZonesPanel: () -> Unit = {
        onShowZonesSheetChange(true)
        fitZonesTick++
    }

    val openSettings: () -> Unit = {
        onShowZonesSheetChange(false)
        onOpenSettings()
    }

    val openThreatSettings: () -> Unit = {
        onShowZonesSheetChange(false)
        onOpenThreatSettings()
    }

    // Back closes the shelter card first; the threat popup installs its own back handler
    // inside ThreatCardHost (scoped to the selection flow, so taps don't rebuild this scope).
    BackHandler(enabled = selectedShelter != null) { selectedShelter = null }
    BackHandler(enabled = showZonesSheet) { onShowZonesSheetChange(false) }

    Scaffold(
        topBar = {
            val activeZone = uiState.activeZone
            val officialOnly = uiState.focusOblastAlertActive && activeZone == null
            val borderColor = when (activeZone) {
                ThreatZone.INNER -> AlertRed
                ThreatZone.OUTER -> Color(0xFFF9A825)
                null -> if (officialOnly) AlertRed else Color.Transparent
            }
            val pinnedCityName = if (uiState.followMe) null else uiState.pinnedCity?.let {
                if (uiState.language == AppLanguage.UA) it.nameUa else it.nameEn
            }
            val alertText = when (activeZone) {
                ThreatZone.INNER -> s.redZoneAlert
                ThreatZone.OUTER -> s.yellowZoneAlert
                null -> when {
                    officialOnly -> String.format(s.alertBannerFormat, uiState.focusBannerCity)
                    pinnedCityName != null -> pinnedCityName
                    else -> s.appTitle
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.5.dp, borderColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .onGloballyPositioned { coords ->
                        onHeaderHeightChange(coords.size.height)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                UkraineEmblem(
                    active = uiState.focusOblastAlertActive,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val titleInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = alertText,
                        modifier = Modifier
                            .pressTick(titleInteraction)
                            .clickable(
                                interactionSource = titleInteraction,
                                indication = ripple(),
                                onClick = { fitUkraineTick++ }
                            )
                            .semantics { semanticsContentDescription = s.fitMapLabel },
                        style = when {
                            activeZone != null -> MaterialTheme.typography.titleMedium.copy(color = Color.White)
                            officialOnly -> MaterialTheme.typography.titleMedium.copy(color = Color(0xFFE57373))
                            else -> MaterialTheme.typography.titleMedium.copy(
                                brush = Brush.linearGradient(
                                    listOf(UkraineBlue, UkraineYellow)
                                )
                            )
                        }
                    )
                }
                ConnectionStatus(
                    neptunDown = uiState.neptunDown,
                    degraded = uiState.degraded,
                    onOpenLogs = onOpenLogs,
                    showInfo = showConnectionInfo,
                    onShowInfoChange = onShowConnectionInfoChange,
                    s = s,
                    modifier = Modifier.padding(end = 4.dp)
                )
                val gearButtonInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = openSettings,
                    modifier = Modifier.size(32.dp).pressTick(gearButtonInteraction),
                    interactionSource = gearButtonInteraction
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_ua),
                        contentDescription = s.settingsButton,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                if (gearHintActive) {
                                    val scale = 1f + 0.12f * gearPulse
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = 0.55f + 0.45f * gearPulse
                                }
                            }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    NeptunMapView(
                        uiState = uiState,
                        selectedThreatId = selectedThreatId,
                        lang = uiState.language,
                        iconSet = uiState.iconSet,
                        onScaleChange = { scaleMpp = it },
                        onThreatTapped = {
                            showNearbyShelters = false
                            selectedShelter = null
                            onThreatTapped(it)
                        },
                        onMapTapped = {
                            selectedShelter = null
                            onMapTapped()
                        },
                        fitUkraineTick = fitUkraineTick,
                        zoomZone = zoomZone,
                        zoomTick = zoomTick,
                        fitZonesTick = fitZonesTick,
                        zonesSheetOpen = showZonesSheet,
                        revealRequest = uiState.revealRequest,
                        paused = settingsOpen,
                        mapVisible = mapVisible,
                        shelterZoomTick = shelterZoomTick,
                        shelterSelectTick = shelterSelectTick,
                        onNeutralize = onNeutralize,
                        showNearbyShelters = showNearbyShelters,
                        shelterIndex = uiState.shelterIndex,
                        selectedShelter = selectedShelter,
                        onShelterTapped = {
                            onDismissPopup()
                            selectedShelter = it
                            shelterSelectTick++
                        },
                        onExitShelterMode = {
                            showNearbyShelters = false
                            selectedShelter = null
                        },
                        onDeathActiveChange = { deathActive = it },
                        onReplayProgressChange = { replayProgress = it },
                        onFlourishEjected = onFlourishEjected,
                        modifier = Modifier.fillMaxSize()
                    )
                    uiState.flyby?.let { show ->
                        AviationFlybyOverlay(
                            show = show,
                            iconSet = uiState.iconSet,
                            onFinished = onFlybyFinished
                        )
                    }
                    // Basemap attribution (required by the CARTO basemap free tier) stacked
                    // under the scale bar so the pair matches the floating buttons' height.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .width(92.dp)
                            .padding(start = 12.dp, bottom = 4.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        if (uiState.showMapScale) {
                            ScaleIndicator(
                                metersPerPixel = scaleMpp,
                                lang = uiState.language
                            )
                        }
                    }
                    Text(
                        "© CARTO",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val shelterFocus = uiState.focusLocation
                        val shelterIndex = uiState.shelterIndex
                        if (uiState.sheltersEnabled && shelterIndex != null && shelterFocus != null &&
                            shelterIndex.withinRegion(shelterFocus.lat, shelterFocus.lon)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(modifier = Modifier.size(width = 16.dp, height = 18.dp))
                                ShelterCircle(
                                    alertActive = uiState.focusOblastAlertActive,
                                    active = showNearbyShelters,
                                    contentDescription = s.shelterButtonLabel,
                                    onClick = onToggleShelters,
                                    onLongClick = onOpenShelters
                                )
                            }
                        }
                        ZoneButtons(
                            redArmed = uiState.activeSlowRedArmed || uiState.activeFastRedArmed,
                            yellowArmed = uiState.activeSlowYellowArmed || uiState.activeFastYellowArmed,
                            lang = uiState.language,
                            notificationsDisabled = uiState.notificationsDisabledBySystem,
                            onZoneTap = { zone ->
                                showNearbyShelters = false
                                selectedShelter = null
                                zoomZone = zone
                                zoomTick++
                            },
                            onEditZones = openZonesPanel
                        )
                    }
                }

                Surface(tonalElevation = 2.dp) {
                    ThreatStripFooter(
                        inner = uiState.threatsInner,
                        outer = uiState.threatsOuter,
                        hiddenTypes = uiState.hiddenTypes,
                        silencedTypes = uiState.silencedTypes,
                        focusLocation = uiState.focusLocation,
                        iconSet = uiState.iconSet,
                        language = uiState.language,
                        calmMessagesEnabled = uiState.calmMessagesEnabled,
                        deathActive = deathActive,
                        replayProgress = replayProgress,
                        s = s,
                        onThreatStripTap = onThreatStripTap
                    )
                }
            }

            // Threat popup: the full interactive card while a threat is selected, crossfading
            // into the compact neutralized card the instant it resolves (so the popup never pops
            // out), which then fades out across the map explosion, clearing the selection.
            // The small card hugs the top-left corner and stays narrow; the large card is
            // top-centred and full-width. The measured height feeds the map so a selected or
            // struck threat is centred in the viewport left visible below the card.
            // Scoped to its own composable collecting `selection` — a tap recomposes ONLY this
            // host, not the map/header/footer scopes around it.
            ThreatCardHost(
                selection = selection,
                language = uiState.language,
                iconSet = uiState.iconSet,
                followMe = uiState.followMe,
                pinnedCity = uiState.pinnedCity,
                threatLevel = uiState.threatLevel,
                cardSize = uiState.threatCardSize,
                silencedTypes = uiState.silencedTypes,
                s = s,
                onDismiss = onDismissPopup,
                onThreatCardSizeChange = onThreatCardSizeChange
            )

            // Shelter info card: tapping a shelter marker on the map opens it here (the same
            // data as the list rows); tapping the map or the back button closes it.
            selectedShelter?.let { sh ->
                ShelterPopupCard(
                    lang = uiState.language,
                    shelter = sh,
                    withKids = uiState.sheltersWithKids,
                    onDismiss = { selectedShelter = null },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                )
            }

            // Alert-zone editor: a non-modal bottom panel over the live map so the
            // red/yellow circles update while you drag, and the map above stays pannable.
            // Every control (sliders + Fast/Slow group toggles) is visible at once.
            // Slides up/down like the connection sheet; snaps when system animations are off.
            val animsOff = animationsOff()
            AnimatedVisibility(
                visible = showZonesSheet,
                enter = if (animsOff) EnterTransition.None
                else slideInVertically(tween(200)) { it } + fadeIn(tween(100)),
                exit = if (animsOff) ExitTransition.None
                else slideOutVertically(tween(250)) { it } + fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (editingNight) NightSectionBg else Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (editingNight) NightSectionBorder else Color(0xFF3A3A3A)
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val density = LocalDensity.current
                        val dismissThresholdPx = with(density) { 80.dp.toPx() }
                        var dragAccum by remember { mutableFloatStateOf(0f) }
                        val closeSheet = { onShowZonesSheetChange(false) }
                        val sheetDismissInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressTick(sheetDismissInteraction)
                                .clickable(
                                    interactionSource = sheetDismissInteraction,
                                    indication = ripple(bounded = true),
                                    onClick = closeSheet
                                )
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (dragAccum > dismissThresholdPx) closeSheet()
                                            dragAccum = 0f
                                        },
                                        onDragCancel = { dragAccum = 0f }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        dragAccum += dragAmount
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SheetDragHandle()
                        }
                        ZonesPanel(
                            slowRedKm = if (editingNight) uiState.nightSlowRedKm else uiState.slowRedKm,
                            slowYellowKm = if (editingNight) uiState.nightSlowYellowKm else uiState.slowYellowKm,
                            fastRedMin = if (editingNight) uiState.nightFastRedMin else uiState.fastRedMin,
                            fastYellowMin = if (editingNight) uiState.nightFastYellowMin else uiState.fastYellowMin,
                            slowRedArmed = if (editingNight) uiState.nightSlowRedArmed else uiState.slowRedArmed,
                            slowYellowArmed = if (editingNight) uiState.nightSlowYellowArmed else uiState.slowYellowArmed,
                            fastRedArmed = if (editingNight) uiState.nightFastRedArmed else uiState.fastRedArmed,
                            fastYellowArmed = if (editingNight) uiState.nightFastYellowArmed else uiState.fastYellowArmed,
                            lang = uiState.language,
                            nightActive = editingNight,
                            useNightZones = uiState.nightUseCustomZones,
                            nightEnabled = uiState.nightEnabled,
                            daySlowRedKm = uiState.slowRedKm,
                            daySlowYellowKm = uiState.slowYellowKm,
                            dayFastRedMin = uiState.fastRedMin,
                            dayFastYellowMin = uiState.fastYellowMin,
                            onSlowRedChange = onSlowRedChange,
                            onSlowYellowChange = onSlowYellowChange,
                            onFastRedChange = onFastRedChange,
                            onFastYellowChange = onFastYellowChange,
                            onSlowRedArmedChange = onSlowRedArmedChange,
                            onSlowYellowArmedChange = onSlowYellowArmedChange,
                            onFastRedArmedChange = onFastRedArmedChange,
                            onFastYellowArmedChange = onFastYellowArmedChange,
                            onOpenThreatSettings = openThreatSettings,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Threat popup host. Collects [selection] here — the ONLY reactive reader of selection in the
 * tree — so a tap recomposes just this scope: header, map body and footer never see it.
 */
@Composable
private fun ThreatCardHost(
    selection: StateFlow<SelectionUi>,
    language: AppLanguage,
    iconSet: ThreatIconSet,
    followMe: Boolean,
    pinnedCity: City?,
    threatLevel: Double,
    cardSize: ThreatCardSize,
    silencedTypes: Set<ThreatType>,
    s: Strings.StringSet,
    onDismiss: () -> Unit,
    onThreatCardSizeChange: (ThreatCardSize) -> Unit
) {
    val sel = selection.collectAsState().value
    SideEffect {
        sel.selected?.let {
            if (BuildConfig.DEBUG) android.util.Log.d("PerfTrace", "card composed id=${it.id} t=${System.currentTimeMillis()}")
        }
    }
    // Back closes the popup first, then exits — fixes "back stuck on home page".
    BackHandler(enabled = sel.selected != null || sel.neutralized != null) { onDismiss() }

    val smallCard = cardSize == ThreatCardSize.SMALL
    AnimatedContent(
        targetState = when {
            sel.selected != null -> 1
            sel.neutralized != null -> 2
            else -> 0
        },
        // Card appears/disappears in one frame — tap must feel instant. Selection
        // motion is the map's bullet + the card's icon pop; the slower fade below is
        // reserved for the neutralized state's death-window exit.
        transitionSpec = {
            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        },
        label = "threatCardSwap",
        modifier = Modifier
            .padding(top = 12.dp, start = 16.dp, end = 16.dp)
    ) { state ->
        when (state) {
            1 -> sel.selected?.let { threat ->
                Column {
                    ThreatPopupCard(
                        threat = threat,
                        lang = language,
                        iconSet = iconSet,
                        proximity = sel.proximity,
                        pinnedCity = if (followMe) null else pinnedCity,
                        threatLevel = threatLevel,
                        cardSize = cardSize,
                        alertsOff = threat.type.toThreatType() in silencedTypes,
                        onDismiss = onDismiss,
                        fakeNeutralize = sel.fakeNeutralize,
                        modifier = if (smallCard) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        ThreatCardSizeControl(
                            current = cardSize,
                            contentDescription = s.cardSizeLabel,
                            onClick = { onThreatCardSizeChange(nextThreatCardSize(cardSize)) },
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            2 -> sel.neutralized?.let { threat ->
                val fade = remember { Animatable(1f) }
                var neutralizing by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(DEATH_EXPLOSION_START_MS)
                    neutralizing = false
                }
                // A short readable hold, then one smooth, clearly visible alpha ramp that
                // runs across the whole death window — never a hard hide at impact.
                LaunchedEffect(Unit) {
                    val holdMs = 700L
                    delay(holdMs)
                    fade.animateTo(
                        0f,
                        tween((DEATH_EXPLOSION_START_MS + DEATH_EXPLOSION_LEN_MS - holdMs).toInt())
                    )
                    onDismiss()
                }
                Box(modifier = Modifier.graphicsLayer { alpha = fade.value }) {
                    ThreatPopupCard(
                        threat = threat,
                        lang = language,
                        iconSet = iconSet,
                        proximity = null,
                        pinnedCity = null,
                        threatLevel = 0.0,
                        cardSize = cardSize,
                        interactive = false,
                        neutralized = true,
                        neutralizing = neutralizing,
                        onDismiss = onDismiss,
                        fakeNeutralize = sel.fakeNeutralize,
                        modifier = if (smallCard) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Footer strip / calm message / replay progress bar. Owns its per-type cycle state so
 *  unrelated recompositions of the surrounding scope don't re-run grouping or sorting. */
@Composable
private fun ThreatStripFooter(
    inner: List<NormalizedThreat>,
    outer: List<NormalizedThreat>,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    focusLocation: LatLng?,
    iconSet: ThreatIconSet,
    language: AppLanguage,
    calmMessagesEnabled: Boolean,
    deathActive: Boolean,
    replayProgress: ReplayProgress?,
    s: Strings.StringSet,
    onThreatStripTap: (NormalizedThreat) -> Unit
) {
    val innerCounts = inner.groupingBy { it.type.toThreatType() }.eachCount()
    val outerCounts = outer.groupingBy { it.type.toThreatType() }.eachCount()
    val total = ThreatType.values().sumOf {
        (innerCounts[it] ?: 0) + (outerCounts[it] ?: 0)
    }
    val replay = replayProgress
    if (replay != null) {
        // A tally replay owns the whole footer while it runs — the per-group
        // "Resolving threat X of N" replaces even the threat strip, with a
        // slim overall-progress bar along the footer's bottom edge; the strip
        // returns the moment the show ends.
        val barFraction by animateFloatAsState(
            targetValue = replay.fraction,
            animationSpec = tween(250),
            label = "flourishProgress"
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(
                resolvingThreatsPhrase(replay.groupSize, language),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF9A825),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            // Slim progress bar: faint track, amber fill easing across the
            // bottom edge as the show advances.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFF9A825))
                )
            }
        }
    } else if (total == 0) {
        val footerText = when {
            deathActive -> s.neutralizingLabel
            else -> remember(calmMessagesEnabled) { noThreatsMessage(language, calmMessagesEnabled) }
        }
        Text(
            footerText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (deathActive) Color(0xFFF9A825) else Color(0xFF4CAF50),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    } else {
        // Last strip-tapped threat id per type, so repeated taps cycle through each of that type.
        val stripCycle = remember { mutableStateMapOf<ThreatType, String>() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThreatType.values().forEach { type ->
                val count = (innerCounts[type] ?: 0) + (outerCounts[type] ?: 0)
                val visible = type !in hiddenTypes
                val alerting = type !in silencedTypes
                if (count > 0 && visible && alerting) {
                    val list = (inner + outer)
                        .filter { it.type.toThreatType() == type }
                        .sortedBy {
                            if (focusLocation != null) distanceFlat(focusLocation.lat, focusLocation.lon, it.lat, it.lon)
                            else 0.0
                        }
                    ThreatStatusCell(
                        type = type,
                        count = count,
                        enabled = true,
                        iconSet = iconSet,
                        onClick = {
                            list.firstOrNull()?.let { nearest ->
                                val current = stripCycle[type]
                                val next = if (current == null || list.size == 1) {
                                    nearest
                                } else {
                                    val idx = list.indexOfFirst { it.id == current }
                                    if (idx in 0 until list.size - 1) list[idx + 1] else list[0]
                                }
                                stripCycle[type] = next.id
                                onThreatStripTap(next)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UkraineEmblem(active: Boolean, modifier: Modifier = Modifier, contentDesc: String? = null) {
    val red = AlertRed
    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (active) {
            // Soft red halo so the emblem reads as "glowing red" during an official alert.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(listOf(red.copy(alpha = 0.55f), Color.Transparent)),
                        CircleShape
                    )
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_trident),
            contentDescription = contentDesc,
            colorFilter = if (active) ColorFilter.tint(red) else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun ScaleIndicator(metersPerPixel: Double, lang: AppLanguage, modifier: Modifier = Modifier) {
    if (metersPerPixel <= 0.0) return
    val s = Strings.get(lang)
    val density = LocalDensity.current
    // Pick the largest "nice" round distance that fits ~84dp, and draw its bar at real scale.
    val candidateMeters = listOf(
        50.0, 100.0, 200.0, 300.0, 500.0, 1000.0,
        2000.0, 5000.0, 10000.0, 20000.0, 50000.0
    )
    val targetPx = with(density) { 84.dp.toPx() }
    val chosen = candidateMeters.lastOrNull { (it / metersPerPixel) <= targetPx }
        ?: candidateMeters.first()
    val barPx = (chosen / metersPerPixel).toFloat().coerceAtMost(targetPx)
    val barDp = with(density) { barPx.toDp() }
    val label = if (chosen >= 1000.0) "${(chosen / 1000.0).roundToInt()} ${s.kmUnit}"
    else "${chosen.roundToInt()} ${s.meterUnit}"

    // Google-Maps-style scale: label above a thin alternating bar. The label is drawn
    // in a bold white font (no background) so it stays readable over the map tiles.
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier
                .border(width = 1.dp, color = Color.White)
                .height(3.dp)
                .width(barDp)
        ) {
            val seg = barDp / 4f
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .width(seg)
                        .height(3.dp)
                        .background(
                            if (i % 2 == 0) Color.Black
                            else Color.White
                        )
                )
            }
        }
    }
}

/** Fixed-size shelter button — a 48dp circle matching the gear button, immune to font scaling.
 *  Shows the map's teardrop shelter-pin icon (stroke-only), contentDescription carries the label
 *  for TalkBack. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelterCircle(
    alertActive: Boolean,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val fg = when {
        alertActive -> Color.White
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = when {
        alertActive -> null
        active -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val bg = if (alertActive) AlertRed else MaterialTheme.colorScheme.surface
    val baseModifier = modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(bg.copy(alpha = if (isPressed) 0.8f else 1f))
        .semantics { semanticsContentDescription = contentDescription }
        .pressTick(interactionSource)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true, color = fg.copy(alpha = 0.3f)),
            onClick = onClick,
            onLongClick = onLongClick
        )
    Box(
        modifier = if (border != null) baseModifier.border(border, CircleShape) else baseModifier,
        contentAlignment = Alignment.Center
    ) {
        TeardropShelterIcon(modifier = Modifier.size(16.dp), tint = fg)
    }
}

/** The map's shelter pin as a Compose icon — stroke-only teardrop, tip anchored at the bottom,
 *  mirroring the shelter marker bitmap drawn in MapView. The path is scaled to the canvas size
 *  (and the stroke to its width) so the pin fills the given box on any screen density. */
@Composable
internal fun TeardropShelterIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier, onDraw = {
        val w = size.width
        val h = size.height
        val cw = 16f
        val ch = 18f
        val scale = minOf(w / cw, h / ch)
        val dx = (w - cw * scale) / 2f
        val dy = (h - ch * scale) / 2f
        val cx = dx + 8f * scale
        val r = 8f * scale
        val bottom = dy + 18f * scale
        val top = bottom - 18f * scale
        val bulbMidY = top + r
        drawPath(
            path = Path().apply {
                moveTo(cx, bottom)
                quadraticTo(cx + r, bulbMidY + r * 0.6f, cx + r, bulbMidY)
                quadraticTo(cx + r, top, cx, top)
                quadraticTo(cx - r, top, cx - r, bulbMidY)
                quadraticTo(cx - r, bulbMidY + r * 0.6f, cx, bottom)
            },
            color = tint,
            style = Stroke(width = 2.6f * scale)
        )
    })
}

@Composable
internal fun ZoneButtons(
    redArmed: Boolean,
    yellowArmed: Boolean,
    lang: AppLanguage,
    notificationsDisabled: Boolean = false,
    onZoneTap: (ThreatZone) -> Unit,
    onEditZones: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = Strings.get(lang)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!redArmed && !yellowArmed) {
            AllAlertsOffWarning(label = s.allAlertsOffLabel, onClick = onEditZones)
            Spacer(Modifier.height(6.dp))
        } else if (notificationsDisabled) {
            AllAlertsOffWarning(label = s.notificationsDisabledLabel, onClick = onEditZones)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ZoneButton(ThreatZone.INNER, redArmed, s.zoneButtonRed, onZoneTap)
            ZoneButton(ThreatZone.OUTER, yellowArmed, s.zoneButtonYellow, onZoneTap)
            val gearInteraction = remember { MutableInteractionSource() }
            val gearPressed by gearInteraction.collectIsPressedAsState()
            val gearRotation = animateFloatAsState(
                targetValue = if (gearPressed) -15f else 0f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                label = "gearRotation"
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .semantics { semanticsContentDescription = s.editZonesLabel }
                    .pressTick(gearInteraction)
                    .clickable(
                        interactionSource = gearInteraction,
                        indication = null,
                        onClick = onEditZones
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = gearRotation.value }
                )
            }
        }
    }
}

@Composable
private fun AllAlertsOffWarning(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier
            .pressTick(interactionSource)
            .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            onClick = onClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = null,
                tint = Color(0xFF777777),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = null,
                tint = Color(0xFF777777),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFF9A825),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ZoneButton(
    zone: ThreatZone,
    armed: Boolean,
    contentDescription: String,
    onZoneTap: (ThreatZone) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(width = 16.dp, height = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!armed) {
                // Red crossed bell floating above the pill signals this zone's alerts are off.
                AlertsOffBell(size = 16.dp)
            }
        }
        ZonePill(zone, armed, contentDescription, onZoneTap)
    }
}

@Composable
private fun ZonePill(
    zone: ThreatZone,
    armed: Boolean,
    contentDescription: String,
    onZoneTap: (ThreatZone) -> Unit
) {
    val zoneColor = when (zone) {
        ThreatZone.INNER -> AlertRed
        ThreatZone.OUTER -> Color(0xFFF9A825)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgAlpha = animateFloatAsState(
        targetValue = if (isPressed) 0.75f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "zonePillBg"
    )
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "zonePillScale"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = bgAlpha.value
            }
            .background(if (armed) zoneColor else Color(0xFF2A2A2A))
            .border(2.dp, if (armed) zoneColor else Color(0xFF666666), CircleShape)
            .semantics { semanticsContentDescription = contentDescription }
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onZoneTap(zone) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_zoom_in),
            contentDescription = null,
            tint = if (armed) Color.White else Color(0xFF777777),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ThreatStatusCell(
    type: ThreatType,
    count: Int,
    enabled: Boolean,
    iconSet: ThreatIconSet,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPressed) 0.12f else 0.06f))
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThreatIcon(
            type = type,
            set = iconSet,
            size = 28.dp,
            tint = if (enabled) Color.Unspecified else Color(0xFF9E9E9E)
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else Color(0xFF9E9E9E)
        )
    }
}

/** Popup card-size stepper: SMALL → LARGE → SMALL… */
private fun nextThreatCardSize(current: ThreatCardSize): ThreatCardSize {
    val values = ThreatCardSize.values()
    return values[(current.ordinal + 1) % values.size]
}


/** Two stacked lines (thin/thick) under the popup; tap cycles the card size. */
@Composable
private fun ThreatCardSizeControl(
    current: ThreatCardSize,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "cardSizeScale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isPressed) 0.95f else 0.85f))
            .semantics { semanticsContentDescription = contentDescription }
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        listOf(2.dp, 6.dp).forEachIndexed { i, thickness ->
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .height(thickness)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (i == current.ordinal) MaterialTheme.colorScheme.primary
                        else Color(0xFF9E9E9E)
                    )
            )
            if (i < 1) Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateState,
    needsInstallPermission: Boolean,
    lang: AppLanguage,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onLater: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val s = Strings.get(lang)
    when (state) {
        is UpdateState.Available -> {
            val notes = if (lang == AppLanguage.UA) state.info.notesUa else state.info.notesEn
            AlertDialog(
                onDismissRequest = onLater,
                title = { Text(s.updateAvailableTitle) },
                text = {
                    Column {
                        Text(
                            "${s.updateVersionLabel}: ${state.info.versionName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(s.updateNotesTitle, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            notes.split('\n').filter { it.isNotBlank() }.forEachIndexed { i, line ->
                                if (i > 0) Spacer(Modifier.height(4.dp))
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDownload) { Text(s.updateDownload) } },
                dismissButton = { TextButton(onClick = onLater) { Text(s.updateLater) } }
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(s.updateDownload) },
                text = {
                    Column {
                        Text(
                            String.format(s.updateDownloading, (state.progress * 100).toInt().coerceIn(0, 100)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { state.progress })
                    }
                },
                confirmButton = {},
                dismissButton = {}
            )
        }
        is UpdateState.Downloaded -> {
            if (needsInstallPermission) {
                AlertDialog(
                    onDismissRequest = onLater,
                    title = { Text(s.updateInstallPermissionTitle) },
                    text = { Text(s.updateInstallPermissionBody) },
                    confirmButton = { TextButton(onClick = onOpenSettings) { Text(s.updateOpenSettings) } },
                    dismissButton = { TextButton(onClick = onInstall) { Text(s.updateInstall) } }
                )
            } else {
                AlertDialog(
                    onDismissRequest = onLater,
                    title = { Text(s.updateReadyToInstallTitle) },
                    text = { Text(s.updateReadyToInstallBody) },
                    confirmButton = { TextButton(onClick = onInstall) { Text(s.updateInstall) } },
                    dismissButton = { TextButton(onClick = onLater) { Text(s.updateLater) } }
                )
            }
        }
        is UpdateState.Failed -> {
            AlertDialog(
                onDismissRequest = onLater,
                title = { Text(s.updateFailedTitle) },
                text = { Text(state.message.orEmpty()) },
                confirmButton = { TextButton(onClick = onRetry) { Text(s.updateRetry) } },
                dismissButton = { TextButton(onClick = onLater) { Text(s.updateLater) } }
            )
        }
        else -> Unit
    }
}

/** Snackbar host whose snackbars can be dismissed by swiping sideways. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        key(data) {
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) data.dismiss()
                    true
                }
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {},
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true,
                content = { Snackbar(snackbarData = data) }
            )
        }
    }
}
