package com.presaince.oko

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.presaince.oko.City
import com.presaince.oko.DigestWindow
import com.presaince.oko.ThreatType
import com.presaince.oko.ZonePolicy
import com.presaince.oko.theme.AppPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    hapticsEnabled: Boolean,
    updateState: UpdateState,
    latestVersion: String?,
    nightActive: Boolean,
    listState: LazyListState,
    collapse: SettingsCollapseState,
    onCollapseChange: (SettingsCollapseState) -> Unit,
    scrollToThreatsTick: Int,
    onThreatsScrollHandled: () -> Unit,
    activeExplainer: Explainer?,
    onExplainerChange: (Explainer?) -> Unit,
    versionName: String,
    onBack: () -> Unit,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    onThreatMapToggleAll: (Set<ThreatType>, Boolean) -> Unit,
    onThreatAlertToggleAll: (Set<ThreatType>, Boolean) -> Unit,
    onOfficialAlertsChange: (Boolean) -> Unit,
    onOfficialRedAlertsChange: (Boolean) -> Unit,
    onOfficialYellowAlertsChange: (Boolean) -> Unit,
    onOfficialAlertCityScopeChange: (Boolean) -> Unit,
    onSirenOverrideChange: (Boolean) -> Unit,
    onFallingDebrisDelayChange: (Int) -> Unit,
    onCriticalOfflineOverrideChange: (Boolean) -> Unit,
    onCriticalOfflineBypassSilentChange: (Boolean) -> Unit,
    onBootRestartChange: (Boolean) -> Unit,
    onNightEnabledChange: (Boolean) -> Unit,
    onNightStartChange: (Int) -> Unit,
    onNightEndChange: (Int) -> Unit,
    onNightUseCustomZonesChange: (Boolean) -> Unit,
    onNightSlowRedChange: (Int) -> Unit,
    onNightSlowYellowChange: (Int) -> Unit,
    onNightFastRedChange: (Int) -> Unit,
    onNightFastYellowChange: (Int) -> Unit,
    onNightSlowRedArmedChange: (Boolean) -> Unit,
    onNightSlowYellowArmedChange: (Boolean) -> Unit,
    onNightFastRedArmedChange: (Boolean) -> Unit,
    onNightFastYellowArmedChange: (Boolean) -> Unit,
    onNightZoneSirenOverrideChange: (Boolean) -> Unit,
    onNightOfficialSirenOverrideChange: (Boolean) -> Unit,
    onNightOfficialAlertCityScopeChange: (Boolean) -> Unit,
    onFollowMeChange: (Boolean) -> Unit,
    onPinnedCityChange: (City?) -> Unit,
    onPeriodicGpsChange: (Boolean) -> Unit,
    onCalmMessagesChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onDisclaimerCollapse: (Boolean) -> Unit,
    onDisclaimerShown: () -> Unit,
    onThreatCardSizeChange: (ThreatCardSize) -> Unit,
    onIconSetChange: (ThreatIconSet) -> Unit,
    onOverlapModeChange: (OverlapMode) -> Unit,
    onShowMapScaleChange: (Boolean) -> Unit,
    onShowMediumCitiesChange: (Boolean) -> Unit,
    onShowSmallCitiesChange: (Boolean) -> Unit,
    onShowLargeCitiesChange: (Boolean) -> Unit,
    onAlertRegionModeChange: (AlertRegionMode) -> Unit,
    onShowBordersChange: (Boolean) -> Unit,
    onShowRegionBordersChange: (Boolean) -> Unit,
    onSheltersEnabledChange: (Boolean) -> Unit,
    onOpenShelterList: () -> Unit = {},
    onMoraleMasterChange: (Boolean) -> Unit,
    onMoraleVoiceChange: (MoraleVoice) -> Unit,
    onDeathAnimationChange: (Boolean) -> Unit,
    onFlybyAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
    onHighQualityExplosionsChange: (Boolean) -> Unit,
    onNeutralizedTallyChange: (Boolean) -> Unit,
    onNeutralizedTallyAllUkraineChange: (Boolean) -> Unit,
    onAlarmEpisodeTallyChange: (Boolean) -> Unit,
    onThreatIconZoomChange: (Boolean) -> Unit,
    onFastGroupCollapse: (Boolean) -> Unit,
    onSlowGroupCollapse: (Boolean) -> Unit,
    showThreatIdsOnMap: Boolean,
    onShowThreatIdsOnMapChange: (Boolean) -> Unit,
    zonePolicy: ZonePolicy,
    onZonePolicyChange: (ZonePolicy) -> Unit,
    digestMax: Int,
    onDigestMaxChange: (Int) -> Unit,
    digestWindow: DigestWindow,
    onDigestWindowChange: (DigestWindow) -> Unit,
    digestPerType: Boolean,
    onDigestPerTypeChange: (Boolean) -> Unit,
    onNotifyPolicyEnabledChange: (Boolean) -> Unit,
    policyWhatIf: Map<ZonePolicy, Int>,
    onExit: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenGuide: () -> Unit,
    onRelaunchSetup: () -> Unit,
    onResetTips: () -> Unit = {}
) {
    val lang = state.language
    val hiddenTypes = state.hiddenTypes
    val silencedTypes = state.silencedTypes
    val officialAlertsEnabled = state.officialAlertsEnabled
    val officialRedAlertsEnabled = state.officialRedAlertsEnabled
    val officialYellowAlertsEnabled = state.officialYellowAlertsEnabled
    val officialAlertCityScope = state.officialAlertCityScope
    val sirenOverride = state.sirenOverride
    val fallingDebrisDelaySec = state.fallingDebrisDelaySec
    val criticalOfflineOverride = state.criticalOfflineOverride
    val criticalOfflineBypassSilent = state.criticalOfflineBypassSilent
    val nightEnabled = state.nightEnabled
    val nightStartMin = state.nightStartMin
    val nightEndMin = state.nightEndMin
    val nightUseCustomZones = state.nightUseCustomZones
    val slowRedKm = state.slowRedKm
    val slowYellowKm = state.slowYellowKm
    val fastRedMin = state.fastRedMin
    val fastYellowMin = state.fastYellowMin
    val nightSlowRedKm = state.nightSlowRedKm
    val nightSlowYellowKm = state.nightSlowYellowKm
    val nightFastRedMin = state.nightFastRedMin
    val nightFastYellowMin = state.nightFastYellowMin
    val nightSlowRedArmed = state.nightSlowRedArmed
    val nightSlowYellowArmed = state.nightSlowYellowArmed
    val nightFastRedArmed = state.nightFastRedArmed
    val nightFastYellowArmed = state.nightFastYellowArmed
    val nightZoneSirenOverride = state.nightZoneSirenOverride
    val nightOfficialSirenOverride = state.nightOfficialSirenOverride
    val nightOfficialAlertCityScope = state.nightOfficialAlertCityScope
    val disclaimerCollapsed = state.disclaimerCollapsed
    val disclaimerReadCount = state.disclaimerReadCount
    val followMe = state.followMe
    val pinnedCity = state.pinnedCity
    val threatCardSize = state.threatCardSize
    val iconSet = state.iconSet
    val showMapScale = state.showMapScale
    val showMediumCities = state.showMediumCities
    val showSmallCities = state.showSmallCities
    val showLargeCities = state.showLargeCities
    val alertRegionMode = state.alertRegionMode
    val showBorders = state.showBorders
    val showRegionBorders = state.showRegionBorders
    val sheltersEnabled = state.sheltersEnabled
    val periodicGps = state.periodicGps
    val calmMessagesEnabled = state.calmMessagesEnabled
    val deathAnimationEnabled = state.deathAnimationEnabled
    val flybyAnimationEnabled = state.flybyAnimationEnabled
    val followBullet = state.followBullet
    val highQualityExplosions = state.highQualityExplosions
    val neutralizedTallyEnabled = state.neutralizedTallyEnabled
    val neutralizedTallyAllUkraine = state.neutralizedTallyAllUkraine
    val alarmEpisodeTallyEnabled = state.alarmEpisodeTallyEnabled
    val threatIconZoom = state.threatIconZoom
    val fastGroupCollapsed = state.fastGroupCollapsed
    val slowGroupCollapsed = state.slowGroupCollapsed
    val showThreatIdsOnMap = state.showThreatIdsOnMap
    val notifyPolicyEnabled = state.notifyPolicyEnabled
    val zonePolicy = state.zonePolicy
    val digestMax = state.digestMax
    val digestWindow = state.digestWindow
    val digestPerType = state.digestPerType
    val overlapMode = state.overlapMode
    val moraleMasterEnabled = state.moraleMasterEnabled
    val moraleVoice = state.moraleVoice
    val bootRestartEnabled = state.bootRestartEnabled
    val isChecking = updateState is UpdateState.Checking
    val scrollToNightMode = nightActive
    val s = Strings.get(lang)

    // Search box: filters sections + standalone actions by curated keywords, surfaces suggestion
    // chips for related concepts and "did you mean" for typos. Query is plain remember so it
    // clears itself every time the screen is reopened.
    var searchQuery by remember { mutableStateOf("") }
    val searchNormalized = searchQuery.searchNorm()
    val searchWords = searchNormalized.split(" ").filter { it.isNotBlank() }
    var showBootRestartOffConfirm by remember { mutableStateOf(false) }
    val searching = searchNormalized.isNotEmpty()
    val searchDb = remember(pinnedCity) { buildSearchDb(pinnedCity) }
    val matchedSections = remember(searchNormalized, searchDb) {
        if (searching) searchDb.sectionDirect.filterValues { matchesSearch(searchWords, it) }.keys
        else emptySet()
    }
    val matchedStandalone = remember(searchNormalized, searchDb) {
        if (searching) searchDb.standaloneDirect.filterValues { matchesSearch(searchWords, it) }.keys
        else emptySet()
    }
    val relatedChips = remember(searchNormalized, searchDb) {
        if (!searching) emptyList()
        else searchDb.related
            .filter { (words, _) -> matchesSearch(searchWords, words) }
            .flatMap { it.chips }
            .distinctBy { it.labelUa }
            .take(8)
    }
    val allSearchKeywords = remember(searchDb) {
        searchDb.sectionDirect.values.flatten() + searchDb.standaloneDirect.values.flatten() +
            searchDb.related.flatMap { it.words }
    }
    val didYouMeanChips = remember(searchNormalized, searchDb) {
        if (searching && searchWords.size == 1 && matchedSections.isEmpty() &&
            matchedStandalone.isEmpty() && relatedChips.isEmpty()
        ) {
            val q = searchWords[0]
            allSearchKeywords.asSequence()
                .filter { kotlin.math.abs(it.length - q.length) <= 2 }
                .map { it to levenshtein(it, q) }
                .filter { it.second in 1..2 }
                .sortedBy { it.second }
                .map { it.first }
                .distinct()
                .take(4)
                .map { SearchChip(it, it, it, it) }
                .toList()
        } else emptyList()
    }
    val noSearchResults = searching && matchedSections.isEmpty() && matchedStandalone.isEmpty() &&
        relatedChips.isEmpty() && didYouMeanChips.isEmpty()
    val keyboard = LocalSoftwareKeyboardController.current
    val appContext = LocalContext.current
    var batteryOptimized by remember { mutableStateOf(BatteryOptimization.isIgnoringBatteryOptimizations(appContext)) }
    val batteryOemInfo = remember { BatteryOptimization.getOemInfo() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimized = BatteryOptimization.isIgnoringBatteryOptimizations(appContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var expandedType by remember { mutableStateOf<ThreatType?>(null) }
    // One-time explainers: shown when an advanced toggle is flipped for the first time.
    val explainerPrefs = remember { UserPrefs(appContext) }
    val scope = rememberCoroutineScope()
    val explainerList = remember(s) { explainers(s) }
    var seenExplainers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingCardSize by remember { mutableStateOf<ThreatCardSize?>(null) }
    LaunchedEffect(Unit) {
        seenExplainers = explainerList.map { it.id }
            .filter { explainerPrefs.explainerSeen(it).first() }
            .toSet()
    }
    val showExplainer: (String) -> Unit = { id ->
        explainerList.firstOrNull { it.id == id }?.let { exp ->
            if (exp.id !in seenExplainers) {
                seenExplainers = seenExplainers + exp.id
                scope.launch { explainerPrefs.setExplainerSeen(exp.id, true) }
                onExplainerChange(exp)
            }
        }
    }
    // One-time explainer dismissal: the dialog covers the list, so on close the eye is lost.
    // Snap back to the top of the section the user was tapping and give that row a subtle
    // border pulse so they re-anchor where they were.
    var flashId by remember { mutableStateOf<String?>(null) }
    val sectionOfExplainer: (String) -> Int = { id -> when (id) {
        "followMe" -> SettingsSection.LOCATION.index
        "nightMode" -> SettingsSection.NIGHT.index
        "officialAlerts", "sirenOverride" -> SettingsSection.ALERTS.index
        "threatToggles" -> SettingsSection.THREATS.index
        "cardSize" -> SettingsSection.SYSTEM.index
        else -> SettingsSection.ALERTS.index
    } }
    val dismissExplainer: () -> Unit = {
        val exp = activeExplainer
        if (exp != null) {
            onExplainerChange(null)
            flashId = exp.id
            scope.launch {
                listState.animateScrollToItem(sectionOfExplainer(exp.id))
                delay(900)
                flashId = null
            }
        }
        pendingCardSize?.let { onThreatCardSizeChange(it); pendingCardSize = null }
    }

    // Collapse states are hoisted to MainScreen (rememberSaveable) so they survive screen
    // switches and process death; only the disclaimer card keeps its own remember logic.
    var disclaimerExpanded by remember { mutableStateOf(disclaimerReadCount < 3 || !disclaimerCollapsed) }
    LaunchedEffect(Unit) {
        if (disclaimerReadCount < 3) onDisclaimerShown()
    }
    val onDisclaimerClick: () -> Unit = {
        disclaimerExpanded = !disclaimerExpanded
        onDisclaimerCollapse(!disclaimerExpanded)
    }

    // Scroll to section when requested by external triggers (e.g. ZonesSheet night mode badge).
    LaunchedEffect(scrollToThreatsTick) {
        if (scrollToThreatsTick > 0) {
            listState.animateScrollToItem(
                if (scrollToNightMode) SettingsSection.NIGHT.index else SettingsSection.THREATS.index
            )
            onThreatsScrollHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(bottom = 8.dp),
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(s.settingsSearchHint) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, interactionSource = rememberHapticInteractionSource()) {
                                    Icon(Icons.Default.Close, contentDescription = s.settingsSearchClear)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, interactionSource = rememberHapticInteractionSource()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.backButton)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedVisibility(
                visible = relatedChips.isNotEmpty() || didYouMeanChips.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    if (relatedChips.isNotEmpty()) {
                        SearchChipsRow(
                            label = s.settingsSearchRelated,
                            chips = relatedChips,
                            lang = lang,
                            onChip = { searchQuery = it.query(lang) }
                        )
                    }
                    if (didYouMeanChips.isNotEmpty()) {
                        SearchChipsRow(
                            label = s.settingsDidYouMean,
                            chips = didYouMeanChips,
                            lang = lang,
                            onChip = { searchQuery = it.query(lang) }
                        )
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

            item(key = "disclaimer", contentType = "disclaimer") {
                // "Official signals come first" — first, default expanded, needs two taps to collapse.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .hapticClickable(onClick = onDisclaimerClick)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WarningTriangle()
                            Spacer(Modifier.width(10.dp))
                            Text(
                                s.disclaimerTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (disclaimerExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AnimatedVisibility(visible = disclaimerExpanded) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(12.dp))
                                val paragraphs = s.disclaimerBody.split("\n\n")
                                paragraphs.forEachIndexed { i, p ->
                                    Text(
                                        p,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (i == 0) FontWeight.Bold else null,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (i != paragraphs.lastIndex) Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (searching.not() || SettingsSection.LOCATION in matchedSections) {
            item(key = "section_location", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.locationSectionTitle,
                    icon = rememberVectorPainter(Icons.Default.LocationOn),
                    expanded = collapse.location,
                    subtitle = s.locationSubtitle(followMe, pinnedCity?.name(lang), periodicGps),
                    onToggle = { onCollapseChange(collapse.copy(location = !collapse.location)) }
                ) {
                    AlertToggleRow(
                        title = s.followMeTitle,
                        description = s.followMeDesc,
                        checked = followMe,
                        onCheckedChange = { v -> showExplainer("followMe"); onFollowMeChange(v) },
                        flash = flashId == "followMe"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AnimatedVisibility(visible = !followMe) {
                        Column {
                            PinCityRow(
                                lang = lang,
                                pinnedCity = pinnedCity,
                                onChange = onPinnedCityChange
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    AnimatedVisibility(visible = followMe) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(modifier = Modifier.padding(start = 24.dp)) {
                                AlertToggleRow(
                                    title = s.periodicGpsTitle,
                                    description = s.periodicGpsDesc,
                                    checked = periodicGps,
                                    onCheckedChange = onPeriodicGpsChange
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GpsCalibrationRow(
                        lang = lang,
                        s = s
                    )
                }
            }

            }
            if (searching.not() || SettingsSection.ALERTS in matchedSections) {
            item(key = "section_alerts", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.alertsLabel,
                    icon = rememberVectorPainter(Icons.Default.Notifications),
                    expanded = collapse.alerts,
                    subtitle = s.alertsSubtitle(
                        officialRedAlertsEnabled,
                        officialYellowAlertsEnabled,
                        sirenOverride
                    ),
                    onToggle = { onCollapseChange(collapse.copy(alerts = !collapse.alerts)) }
                ) {
                    val notifsEnabled = remember(Unit) {
                        AlertNotificationManager.areNotificationsEnabled(appContext)
                    }
                    if (!notifsEnabled) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_notifications_off),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        lang.pick("Сповіщення вимкнено", "Notifications disabled", "Notifications disabled"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    lang.pick(
                                        "Додаток не зможе показувати тривоги та сирени. Увімкніть сповіщення в налаштуваннях системи.",
                                        "The app cannot deliver sirens or alert notifications. Enable notifications in system settings.",
                                        "The app cannot deliver sirens or alert notifications. Enable notifications in system settings."
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        appContext.startActivity(intent)
                                    },
                                    interactionSource = rememberHapticInteractionSource(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        lang.pick("Увімкнути сповіщення", "Enable notifications", "Enable notifications"),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    AlertToggleRow(
                        title = s.officialAlertsTitle,
                        description = s.officialAlertsDesc,
                        checked = officialAlertsEnabled,
                        onCheckedChange = { v -> showExplainer("officialAlerts"); onOfficialAlertsChange(v) },
                        icon = painterResource(R.drawable.ic_trident),
                        iconTint = if (officialAlertsEnabled) null else MaterialTheme.colorScheme.onSurfaceVariant,
                        iconSize = 44.dp,
                        flash = flashId == "officialAlerts"
                    )
                    AnimatedVisibility(visible = officialAlertsEnabled) {
                        Column(modifier = Modifier.padding(start = 40.dp, end = 12.dp)) {
                            OfficialPairToggleRow(
                                redTitle = s.officialRedAlertsTitle,
                                redChecked = officialRedAlertsEnabled,
                                onRedChange = { v -> showExplainer("officialRedAlerts"); onOfficialRedAlertsChange(v) },
                                yellowTitle = s.officialYellowAlertsTitle,
                                yellowChecked = officialYellowAlertsEnabled,
                                onYellowChange = { v -> showExplainer("officialYellowAlerts"); onOfficialYellowAlertsChange(v) }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AlertToggleRow(
                                title = s.officialAlertScopeTitle,
                                description = s.officialAlertScopeDesc,
                                checked = officialAlertCityScope,
                                onCheckedChange = onOfficialAlertCityScopeChange
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.sirenOverrideTitle,
                        description = s.sirenOverrideDesc,
                        checked = sirenOverride,
                        onCheckedChange = { v -> showExplainer("sirenOverride"); onSirenOverrideChange(v) },
                        icon = painterResource(R.drawable.ic_volume_up),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        flash = flashId == "sirenOverride"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.notifyPolicyEnabledTitle,
                        description = s.notifyPolicyEnabledDesc,
                        checked = notifyPolicyEnabled,
                        onCheckedChange = onNotifyPolicyEnabledChange,
                        icon = painterResource(R.drawable.ic_notifications_off),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBadge = "Z"
                    )
                    AnimatedVisibility(visible = notifyPolicyEnabled) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            NotifyPolicyRow(
                                title = s.notifyPolicyTitle,
                                description = s.notifyPolicyDesc,
                                selected = zonePolicy,
                                whatIf = policyWhatIf,
                                onChange = onZonePolicyChange,
                                s = s
                            )
                            AnimatedVisibility(visible = zonePolicy == ZonePolicy.DIGEST) {
                                Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    DigestControlsRow(
                                        max = digestMax,
                                        onMaxChange = onDigestMaxChange,
                                        window = digestWindow,
                                        onWindowChange = onDigestWindowChange,
                                        perType = digestPerType,
                                        onPerTypeChange = onDigestPerTypeChange,
                                        s = s
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FallingDebrisDelayRow(
                        seconds = fallingDebrisDelaySec,
                        title = s.fallingDebrisDelayTitle,
                        description = s.fallingDebrisDelayDesc,
                        offLabel = s.fallingDebrisOffLabel,
                        onCommit = onFallingDebrisDelayChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.offlineCriticalOverrideTitle,
                        description = s.offlineCriticalOverrideDesc,
                        checked = criticalOfflineOverride,
                        onCheckedChange = onCriticalOfflineOverrideChange,
                        icon = painterResource(R.drawable.ic_notifications_off),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBadge = "Z"
                    )
                    AnimatedVisibility(visible = criticalOfflineOverride) {
                        Column(modifier = Modifier.padding(start = 40.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AlertToggleRow(
                                title = s.offlineCriticalBypassSilentTitle,
                                description = s.offlineCriticalBypassSilentDesc,
                                checked = criticalOfflineBypassSilent,
                                onCheckedChange = onCriticalOfflineBypassSilentChange,
                                icon = painterResource(R.drawable.ic_volume_up),
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.bootRestartTitle,
                        description = s.bootRestartDesc,
                        checked = bootRestartEnabled,
                        onCheckedChange = { v ->
                            if (!v) showBootRestartOffConfirm = true else onBootRestartChange(true)
                        },
                        emoji = "🛡️"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Battery Optimization
                    if (batteryOptimized) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    s.batteryGranted,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.batteryBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val title = if (batteryOemInfo.isAggressive) s.batteryOemTitle else s.batteryTitle
                        val body = if (batteryOemInfo.isAggressive) s.batteryOemBody else s.batteryBody
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { BatteryOptimization.requestExemption(appContext) },
                                interactionSource = rememberHapticInteractionSource(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(s.batteryAllowButton, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            }

            if (searching.not() || SettingsSection.NIGHT in matchedSections) {
            item(key = "section_night", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.nightModeLabel,
                    icon = painterResource(R.drawable.ic_moon),
                    expanded = collapse.nightMode,
                    subtitle = s.nightSubtitle(
                        nightEnabled,
                        nightStartMin,
                        nightEndMin,
                        nightZoneSirenOverride || nightOfficialSirenOverride,
                        nightUseCustomZones
                    ),
                    onToggle = { onCollapseChange(collapse.copy(nightMode = !collapse.nightMode)) },
                    cardColor = NightSectionBg,
                    cardBorder = NightSectionBorder,
                    trailing = {
                        Switch(
                            checked = nightEnabled,
                            onCheckedChange = { v -> showExplainer("nightMode"); onNightEnabledChange(v) },
                            interactionSource = rememberHapticInteractionSource()
                        )
                    }
                ) {
                    NightModeCard(
                        lang = lang,
                        enabled = nightEnabled,
                        startMin = nightStartMin,
                        endMin = nightEndMin,
                        useCustomZones = nightUseCustomZones,
                        slowRedKm = nightSlowRedKm,
                        slowYellowKm = nightSlowYellowKm,
                        fastRedMin = nightFastRedMin,
                        fastYellowMin = nightFastYellowMin,
                        slowRedArmed = nightSlowRedArmed,
                        slowYellowArmed = nightSlowYellowArmed,
                        fastRedArmed = nightFastRedArmed,
                        fastYellowArmed = nightFastYellowArmed,
                        zoneSirenOverride = nightZoneSirenOverride,
                        officialSirenOverride = nightOfficialSirenOverride,
                        daySirenOverride = sirenOverride,
                        dayOfficialAlertCityScope = officialAlertCityScope,
                        daySlowRedKm = slowRedKm,
                        daySlowYellowKm = slowYellowKm,
                        dayFastRedMin = fastRedMin,
                        dayFastYellowMin = fastYellowMin,
                        onStartChange = onNightStartChange,
                        onEndChange = onNightEndChange,
                        onUseCustomZonesChange = onNightUseCustomZonesChange,
                        onSlowRedChange = onNightSlowRedChange,
                        onSlowYellowChange = onNightSlowYellowChange,
                        onFastRedChange = onNightFastRedChange,
                        onFastYellowChange = onNightFastYellowChange,
                        onSlowRedArmedChange = onNightSlowRedArmedChange,
                        onSlowYellowArmedChange = onNightSlowYellowArmedChange,
                        onFastRedArmedChange = onNightFastRedArmedChange,
                        onFastYellowArmedChange = onNightFastYellowArmedChange,
                        onZoneSirenOverrideChange = onNightZoneSirenOverrideChange,
                        onOfficialSirenOverrideChange = onNightOfficialSirenOverrideChange,
                        nightOfficialAlertCityScope = nightOfficialAlertCityScope,
                        onNightOfficialAlertCityScopeChange = onNightOfficialAlertCityScopeChange
                    )
                }
            }

            }
            if (searching.not() || SettingsSection.SHELTERS in matchedSections) {
            item(key = "section_shelters", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.shelterSectionTitle,
                    icon = remember {
                        object : Painter() {
                            override val intrinsicSize = Size(24f, 24f)
                            override fun DrawScope.onDraw() {
                                val cw = 16f; val ch = 18f
                                val scale = minOf(size.width / cw, size.height / ch)
                                val dx = (size.width - cw * scale) / 2f
                                val dy = (size.height - ch * scale) / 2f
                                val cx = dx + 8f * scale; val r = 8f * scale
                                val bottom = dy + 18f * scale; val top = bottom - 18f * scale
                                val bulbMidY = top + r
                                drawPath(
                                    Path().apply {
                                        moveTo(cx, bottom)
                                        cubicTo(cx - r * 0.15f, bottom - 2f * scale, dx, bulbMidY + r * 0.5f, dx, bulbMidY)
                                        cubicTo(dx, top, dx + cw * scale, top, dx + cw * scale, bulbMidY)
                                        cubicTo(dx + cw * scale, bulbMidY + r * 0.5f, cx + r * 0.15f, bottom - 2f * scale, cx, bottom)
                                    },
                                    color = Color.Black,
                                    style = Stroke(width = 2.6f * scale)
                                )
                            }
                        }
                    },
                    expanded = collapse.shelters,
                    subtitle = s.sheltersSubtitle(sheltersEnabled),
                    onToggle = { onCollapseChange(collapse.copy(shelters = !collapse.shelters)) }
                ) {
                    AlertToggleRow(
                        title = s.shelterSettingsTitle,
                        description = s.shelterSettingsDesc,
                        checked = sheltersEnabled,
                        onCheckedChange = onSheltersEnabledChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // The directory row is always reachable, even when the map button toggle
                    // is off — turning the button off must not hide the list of shelters.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .hapticClickable { onOpenShelterList() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.shelterViewListLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                s.shelterViewListDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            }
            if (searching.not() || SettingsSection.THREATS in matchedSections) {
            item(key = "section_threats", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.threatsLabel,
                    icon = rememberVectorPainter(Icons.Default.Warning),
                    expanded = collapse.threats,
                    subtitle = s.threatsSubtitle(hiddenTypes.size, silencedTypes.size),
                    onToggle = { onCollapseChange(collapse.copy(threats = !collapse.threats)) }
                ) {
                    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
                    fastAndSlowGroups(lang, typeCatalog).forEachIndexed { index, (groupIcon, groupTitle, types) ->
                        if (index == 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        val groupMapOn = types.none { it in hiddenTypes }
                        val groupAlertsOn = types.none { it in silencedTypes }
                        val groupCollapsed = if (index == 0) fastGroupCollapsed else slowGroupCollapsed
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .hapticClickable {
                                    if (index == 0) onFastGroupCollapse(!fastGroupCollapsed)
                                    else onSlowGroupCollapse(!slowGroupCollapsed)
                                }
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = groupIcon),
                                contentDescription = if (groupIcon == R.drawable.ic_lightning) s.fastGroupIconDesc else s.slowGroupIconDesc,
                                tint = if (groupIcon == R.drawable.ic_turtle) TurtleGreen else Color.Unspecified,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                groupTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconToggle(
                                icon = Icons.Filled.Place,
                                contentDescription = s.threatMapLabel,
                                on = groupMapOn,
                                enabled = true,
                                onClick = { onThreatMapToggleAll(types, !groupMapOn) }
                            )
                            IconToggle(
                                icon = Icons.Filled.Notifications,
                                contentDescription = s.threatAlertLabel,
                                on = groupAlertsOn,
                                enabled = true,
                                onClick = { onThreatAlertToggleAll(types, !groupAlertsOn) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = if (groupCollapsed) Icons.Default.KeyboardArrowDown
                                else Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        if (!groupCollapsed) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                types.forEach { type ->
                                    ThreatSettingsCard(
                                        type = type,
                                        lang = lang,
                                        iconSet = iconSet,
                                        expanded = expandedType == type,
                                        onExpandChange = { expandedType = if (expandedType == type) null else type },
                                        hiddenTypes = hiddenTypes,
                                        silencedTypes = silencedTypes,
                                        onThreatMapToggle = { t, v -> showExplainer("threatToggles"); onThreatMapToggle(t, v) },
                                        onThreatAlertToggle = { t, v -> showExplainer("threatToggles"); onThreatAlertToggle(t, v) },
                                        flash = flashId == "threatToggles"
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(s.iconSetTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        IconSetSelector(lang = lang, selected = iconSet, onChange = onIconSetChange)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(s.overlapModeTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.overlapModeDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OverlapModeChip(OverlapMode.DEFAULT, s.overlapDefaultLabel, overlapMode, Modifier.weight(1f)) { onOverlapModeChange(OverlapMode.DEFAULT) }
                            OverlapModeChip(OverlapMode.COUNT, s.overlapCountLabel, overlapMode, Modifier.weight(1f)) { onOverlapModeChange(OverlapMode.COUNT) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(title = s.threatIconZoomTitle, description = s.threatIconZoomDesc, checked = threatIconZoom, onCheckedChange = onThreatIconZoomChange, icon = rememberVectorPainter(Icons.Default.ZoomIn), iconTint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            }
            if (searching.not() || SettingsSection.SYSTEM in matchedSections) {
            item(key = "section_system", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.systemSectionTitle,
                    icon = painterResource(id = R.drawable.ic_language),
                    expanded = collapse.system,
                    subtitle = s.systemSubtitle(threatCardSize, iconSet),
                    onToggle = { onCollapseChange(collapse.copy(system = !collapse.system)) }
                ) {
                    // Card Size & Detail
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).explainerFlash(flashId == "cardSize")) {
                        Text(
                            s.cardSizeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        ThreatCardSizeSelector(
                            lang = lang,
                            selected = threatCardSize,
                            onChange = { v ->
                                if ("cardSize" !in seenExplainers) {
                                    pendingCardSize = v
                                    showExplainer("cardSize")
                                } else {
                                    onThreatCardSizeChange(v)
                                }
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_skull),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                s.cardSkullNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.approxNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Visual map toggles
                    AlertToggleRow(
                        title = s.showMapScaleTitle,
                        description = s.showMapScaleDesc,
                        checked = showMapScale,
                        onCheckedChange = onShowMapScaleChange,
                        icon = painterResource(R.drawable.ic_scale),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CityLabelTogglesRow(
                        title = s.cityLabelsTitle,
                        description = s.cityLabelsDesc,
                        largeChecked = showLargeCities,
                        mediumChecked = showMediumCities,
                        smallChecked = showSmallCities,
                        largeLabel = s.largeCitiesChip,
                        mediumLabel = s.mediumCitiesChip,
                        smallLabel = s.smallCitiesChip,
                        onLargeChange = onShowLargeCitiesChange,
                        onMediumChange = onShowMediumCitiesChange,
                        onSmallChange = onShowSmallCitiesChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertRegionModeRow(
                        title = s.fillAlertRegionsTitle,
                        description = s.fillAlertRegionsDesc,
                        selected = alertRegionMode,
                        cityLabelsLabel = s.alertRegionModeCityLabelsChip,
                        fillLabel = s.alertRegionModeFillChip,
                        borderLabel = s.alertRegionModeBorderChip,
                        onModeChange = onAlertRegionModeChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.showBordersTitle,
                        description = s.showBordersDesc,
                        checked = showBorders,
                        onCheckedChange = onShowBordersChange,
                        icon = rememberVectorPainter(Icons.Outlined.CropFree),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AnimatedVisibility(visible = showBorders) {
                        Box(modifier = Modifier.padding(start = 40.dp)) {
                            AlertToggleRow(
                                title = s.showRegionBordersTitle,
                                description = s.showRegionBordersDesc,
                                checked = showRegionBorders,
                                onCheckedChange = onShowRegionBordersChange,
                                icon = rememberVectorPainter(Icons.Outlined.CropFree),
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                iconSize = 24.dp
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Haptic press feedback
                    AlertToggleRow(
                        title = s.hapticsTitle,
                        description = s.hapticsDesc,
                        checked = hapticsEnabled,
                        onCheckedChange = onHapticsEnabledChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Show threat IDs on map
                    AlertToggleRow(
                        title = s.showThreatIdsOnMapTitle,
                        description = s.showThreatIdsOnMapDesc,
                        checked = showThreatIdsOnMap,
                        onCheckedChange = onShowThreatIdsOnMapChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Reset tip counters
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        OutlinedButton(
                            onClick = onResetTips,
                            interactionSource = rememberHapticInteractionSource(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(s.resetTipsTitle, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.resetTipsDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            }

            if (searching.not() || SettingsSection.FLOURISH in matchedSections) {
            item(key = "section_flourish", contentType = "section") {
                CollapsibleSectionCard(
                    title = s.moraleSectionTitle,
                    icon = painterResource(R.drawable.ic_morale),
                    iconTint = Color.Unspecified,
                    expanded = collapse.flourish,
                    subtitle = s.moraleSubtitle(deathAnimationEnabled, neutralizedTallyEnabled),
                    onToggle = { onCollapseChange(collapse.copy(flourish = !collapse.flourish)) },
                    trailing = {
                        Switch(
                            checked = moraleMasterEnabled,
                            onCheckedChange = onMoraleMasterChange,
                            interactionSource = rememberHapticInteractionSource()
                        )
                    }
                ) {
                    AnimatedVisibility(visible = moraleMasterEnabled) {
                        MoraleToggles(
                            s = s,
                            voice = moraleVoice,
                            onVoiceChange = onMoraleVoiceChange,
                            calmMessagesEnabled = calmMessagesEnabled,
                            flybyAnimationEnabled = flybyAnimationEnabled,
                            deathAnimationEnabled = deathAnimationEnabled,
                            followBullet = followBullet,
                            highQualityExplosions = highQualityExplosions,
                            neutralizedTallyEnabled = neutralizedTallyEnabled,
                            neutralizedTallyAllUkraine = neutralizedTallyAllUkraine,
                            alarmEpisodeTallyEnabled = alarmEpisodeTallyEnabled,
                            onCalmMessagesChange = onCalmMessagesChange,
                            onFlybyAnimationChange = onFlybyAnimationChange,
                            onDeathAnimationChange = onDeathAnimationChange,
                            onFollowBulletChange = onFollowBulletChange,
                            onHighQualityExplosionsChange = onHighQualityExplosionsChange,
                            onNeutralizedTallyChange = onNeutralizedTallyChange,
                            onNeutralizedTallyAllUkraineChange = onNeutralizedTallyAllUkraineChange,
                            onAlarmEpisodeTallyChange = onAlarmEpisodeTallyChange
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        remember(lang, moraleVoice) {
                            moraleVoicePack(lang, resolveMoraleVoice(moraleVoice)).note
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            }
            if (noSearchResults) {
                item(key = "no_results", contentType = "message") {
                    Text(
                        s.settingsNoResults,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (searching.not() || StandaloneSetting.RELAUNCH in matchedStandalone) {
            item(key = "action_relaunch", contentType = "action") {
                OutlinedButton(
                    onClick = onRelaunchSetup,
                    interactionSource = rememberHapticInteractionSource(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(s.relaunchSetupTitle, fontWeight = FontWeight.SemiBold)
                }
            }

            }
            if (searching.not() || StandaloneSetting.GUIDE in matchedStandalone) {
            item(key = "action_guide", contentType = "action") {
                OutlinedButton(
                    onClick = onOpenGuide,
                    interactionSource = rememberHapticInteractionSource(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.guideSettingsButton, fontWeight = FontWeight.SemiBold)
                }
            }

            }
            item(key = "divider", contentType = "divider") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (searching.not() || StandaloneSetting.UPDATE in matchedStandalone) {
            item(key = "action_update", contentType = "action") {
                if (isChecking) {
                    Button(
                        onClick = onCheckUpdate,
                        interactionSource = rememberHapticInteractionSource(),
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(s.updateButton, fontWeight = FontWeight.SemiBold)
                    }
                } else if (latestVersion != null) {
                    Button(
                        onClick = onCheckUpdate,
                        interactionSource = rememberHapticInteractionSource(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${s.updateAvailableButton} · v$latestVersion",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        interactionSource = rememberHapticInteractionSource(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = s.checkForUpdates,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(s.updateButton, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            }
            if (searching.not() || StandaloneSetting.EXIT in matchedStandalone) {
            item(key = "action_exit", contentType = "action") {
                Button(
                    onClick = onExit,
                    interactionSource = rememberHapticInteractionSource(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(s.exitButton, fontWeight = FontWeight.SemiBold)
                }
            }

            }
            item(key = "footer", contentType = "footer") {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val telegramUrl = "https://t.me/odesaplay_bot"
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.hapticClickable { uriHandler.openUri(telegramUrl) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            s.madeBy,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Image(
                            painter = painterResource(R.drawable.ic_telegram),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        "v$versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

    activeExplainer?.let { exp ->
        FeatureExplainerDialog(exp, s, dismissExplainer)
    }

    if (showBootRestartOffConfirm) {
        AlertDialog(
            onDismissRequest = { showBootRestartOffConfirm = false },
            title = { Text(s.bootRestartWarningTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.bootRestartWarningBody)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WarningTriangle()
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.bootRestartWarningBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBootRestartChange(false)
                        showBootRestartOffConfirm = false
                    },
                    interactionSource = rememberHapticInteractionSource()
                ) { Text(s.bootRestartDisableButton) }
            },
            dismissButton = {
                TextButton(onClick = { showBootRestartOffConfirm = false }, interactionSource = rememberHapticInteractionSource()) { Text(s.backButton) }
            }
        )
    }

}
