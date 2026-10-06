package com.odesaplay.oko

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.CropFree
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.odesaplay.oko.City
import com.odesaplay.oko.DigestWindow
import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.ZonePolicy
import com.odesaplay.oko.theme.AppPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    hapticsEnabled: Boolean,
    notificationsDisabled: Boolean,
    updateFlow: StateFlow<UpdateState>,
    latestVersionFlow: StateFlow<String?>,
    nightActive: Boolean,
    route: SettingsRoute,
    onRouteChange: (SettingsRoute) -> Unit,
    activeExplainer: Explainer?,
    onExplainerChange: (Explainer?) -> Unit,
    versionName: String,
    onBack: () -> Unit,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    onOfficialAlertsChange: (Boolean) -> Unit,
    onOfficialRedAlertsChange: (Boolean) -> Unit,
    onOfficialYellowAlertsChange: (Boolean) -> Unit,
    onOfficialAlertCityScopeChange: (Boolean) -> Unit,
    onSirenOverrideChange: (Boolean) -> Unit,
    onFallingDebrisDelayChange: (Int) -> Unit,
    onAutoDismissAllClearChange: (Boolean) -> Unit,
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
    onNightOfficialRedChange: (Boolean) -> Unit,
    onNightOfficialYellowChange: (Boolean) -> Unit,
    onNightSleepToggle: (Boolean) -> Unit,
    onFollowMeChange: (Boolean) -> Unit,
    onPinnedCityChange: (City?) -> Unit,
    onPeriodicGpsChange: (Boolean) -> Unit,
    onCalmMessagesChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onDisclaimerCollapse: (Boolean) -> Unit,
    onDisclaimerShown: () -> Unit,
    onThreatCardSizeChange: (ThreatCardSize) -> Unit,
    onIconSetChange: (ThreatIconSet) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
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
    onDeathAnimationStyleChange: (DeathAnimationStyle) -> Unit,
    onFlybyAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
    onHighQualityExplosionsChange: (Boolean) -> Unit,
    onNeutralizedTallyChange: (Boolean) -> Unit,
    onNeutralizedTallyAllUkraineChange: (Boolean) -> Unit,
    onAlarmEpisodeTallyChange: (Boolean) -> Unit,
    onThreatIconZoomChange: (Boolean) -> Unit,
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
    policyWhatIfFlow: StateFlow<Map<ZonePolicy, Int>>,
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
    val autoDismissAllClear = state.autoDismissAllClear
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
    val nightOfficialRedEnabled = state.nightOfficialRedEnabled
    val nightOfficialYellowEnabled = state.nightOfficialYellowEnabled
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
    val deathAnimationStyle = state.deathAnimationStyle
    val flybyAnimationEnabled = state.flybyAnimationEnabled
    val followBullet = state.followBullet
    val highQualityExplosions = state.highQualityExplosions
    val neutralizedTallyEnabled = state.neutralizedTallyEnabled
    val neutralizedTallyAllUkraine = state.neutralizedTallyAllUkraine
    val alarmEpisodeTallyEnabled = state.alarmEpisodeTallyEnabled
    val threatIconZoom = state.threatIconZoom
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
    val relatedChips = remember(searchNormalized, searchDb) {
        if (!searching) emptyList()
        else searchDb.related
            .filter { (words, _) -> matchesSearch(searchWords, words) }
            .flatMap { it.chips }
            .distinctBy { it.labelUa }
            .take(8)
    }
    val allSearchKeywords = remember(searchDb) {
        searchDb.sectionDirect.values.flatten() + searchDb.related.flatMap { it.words }
    }
    val didYouMeanChips = remember(searchNormalized, searchDb) {
        if (searching && searchWords.size == 1 && matchedSections.isEmpty() &&
            relatedChips.isEmpty()
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
    val noSearchResults = searching && matchedSections.isEmpty() &&
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
    // One-time explainer dismissal: the dialog covers the page, so on close the eye is lost.
    // Return to the section the user was toggling and give that row a subtle border pulse so they
    // re-anchor where they were.
    var flashId by remember { mutableStateOf<String?>(null) }
    val sectionOfExplainer: (String) -> SettingsSection = { id -> when (id) {
        "followMe" -> SettingsSection.LOCATION
        "nightMode" -> SettingsSection.NIGHT
        "officialAlerts", "sirenOverride" -> SettingsSection.ALERTS
        "threatToggles" -> SettingsSection.THREATS
        "cardSize" -> SettingsSection.SYSTEM
        else -> SettingsSection.ALERTS
    } }
    val flashBlock: (String) -> Unit = { id ->
        flashId = id
        scope.launch {
            delay(900)
            flashId = null
        }
    }
    val dismissExplainer: () -> Unit = {
        val exp = activeExplainer
        if (exp != null) {
            onExplainerChange(null)
            onRouteChange(SettingsRoute.Detail(sectionOfExplainer(exp.id)))
            flashBlock(exp.id)
        }
        pendingCardSize?.let { onThreatCardSizeChange(it); pendingCardSize = null }
    }

    // Drilling in from a search result also pulses the block the query named: the user asked for
    // one setting, and landing on a page of twenty rows with nothing highlighted is how they end
    // up concluding the setting does not exist.
    val openSection: (SettingsSection) -> Unit = { sec ->
        // Shelters have no detail page: the whole shelter surface (directory, kids toggle, map
        // button switch) lives on the shelter screen, so a search hit must land there too.
        if (sec == SettingsSection.SHELTERS) {
            onOpenShelterList()
        } else {
            onRouteChange(SettingsRoute.Detail(sec))
            if (searching) {
                anchorFor(searchWords)?.takeIf { it.section == sec }?.let { flashBlock(it.id) }
            }
        }
    }

    // The disclaimer card keeps its own remember logic; the section routes are hoisted to MainScreen.
    var disclaimerExpanded by remember { mutableStateOf(disclaimerReadCount < 3 || !disclaimerCollapsed) }
    LaunchedEffect(Unit) {
        if (disclaimerReadCount < 3) onDisclaimerShown()
    }
    val onDisclaimerClick: () -> Unit = {
        disclaimerExpanded = !disclaimerExpanded
        onDisclaimerCollapse(!disclaimerExpanded)
    }

    // Section labels + icons drive both the root list and the detail page's topBar.
    val sectionTitle: (SettingsSection) -> String = { sec ->
        when (sec) {
            SettingsSection.LOCATION -> s.locationSectionTitle
            SettingsSection.ALERTS -> s.alertsLabel
            SettingsSection.NIGHT -> s.nightModeLabel
            SettingsSection.SHELTERS -> s.shelterSectionTitle
            SettingsSection.THREATS -> s.threatsLabel
            SettingsSection.SYSTEM -> s.systemSectionTitle
            SettingsSection.FLOURISH -> s.moraleSectionTitle
            SettingsSection.MORE -> s.moreSectionTitle
            SettingsSection.RELIABILITY -> s.reliabilitySectionTitle
        }
    }
    // The same live summary that used to sit under each collapsed card header now lives on its
    // detail page, so the state is still visible without expanding anything.
    val sectionSummary: (SettingsSection) -> String = { sec ->
        when (sec) {
            SettingsSection.LOCATION -> s.locationSubtitle(followMe, pinnedCity?.name(lang), periodicGps)
            SettingsSection.ALERTS -> s.alertsSubtitle(
                officialAlertsEnabled = officialAlertsEnabled,
                officialRedAlerts = officialRedAlertsEnabled,
                officialYellowAlerts = officialYellowAlertsEnabled,
                cityScope = officialAlertCityScope,
                alwaysSound = sirenOverride
            )
            SettingsSection.NIGHT -> s.nightSubtitle(
                nightEnabled,
                nightStartMin,
                nightEndMin,
                nightZoneSirenOverride || nightOfficialSirenOverride,
                nightUseCustomZones,
                state.nightSleepActive
            )
            SettingsSection.SHELTERS -> s.sheltersSubtitle(sheltersEnabled)
            SettingsSection.THREATS -> s.threatsSubtitle(hiddenTypes.size, silencedTypes.size)
            SettingsSection.SYSTEM -> s.systemSubtitle(threatCardSize, iconSet)
            SettingsSection.FLOURISH -> s.moraleSubtitle(deathAnimationEnabled, neutralizedTallyEnabled)
            SettingsSection.MORE -> s.moreSubtitleLabel
            SettingsSection.RELIABILITY -> s.reliabilitySubtitle(
                alwaysSound = sirenOverride,
                criticalOffline = criticalOfflineOverride,
                bootRestart = bootRestartEnabled,
                batteryExempt = batteryOptimized
            )
        }
    }
    val sectionIcon: @Composable (SettingsSection) -> Painter = { sec ->
        when (sec) {
            SettingsSection.LOCATION -> rememberVectorPainter(Icons.Default.LocationOn)
            SettingsSection.ALERTS -> rememberVectorPainter(Icons.Default.Notifications)
            SettingsSection.NIGHT -> painterResource(R.drawable.ic_moon)
            SettingsSection.SHELTERS -> shelterPainter()
            SettingsSection.THREATS -> rememberVectorPainter(Icons.Default.Warning)
            SettingsSection.SYSTEM -> painterResource(R.drawable.ic_language)
            SettingsSection.FLOURISH -> painterResource(R.drawable.ic_morale)
            SettingsSection.MORE -> rememberVectorPainter(Icons.Default.MoreHoriz)
            SettingsSection.RELIABILITY -> rememberVectorPainter(Icons.Filled.Security)
        }
    }
    val visibleSections = remember(searchNormalized, searchDb) {
        if (searching) rootSectionOrder.filter { it in matchedSections }
        else rootSectionOrder
    }

    Scaffold(
        topBar = {
            when (route) {
                SettingsRoute.Root -> TopAppBar(
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
                is SettingsRoute.Detail -> TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { onRouteChange(SettingsRoute.Root) },
                            interactionSource = rememberHapticInteractionSource()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.backButton)
                        }
                    },
                    title = { Text(sectionTitle(route.section), style = MaterialTheme.typography.titleMedium) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedVisibility(
                visible = route == SettingsRoute.Root &&
                    (relatedChips.isNotEmpty() || didYouMeanChips.isNotEmpty()),
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
            if (route == SettingsRoute.Root) {
            LazyColumn(
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

            items(
                items = visibleSections,
                key = { it.ordinal },
                contentType = { "section" }
            ) { sec ->
                // The two master switches that used to hide inside a page header live on the root
                // list instead: a section whose whole body is gated on its master is a dead end if
                // the switch is only reachable by drilling in and finding an empty card.
                when (sec) {
                    SettingsSection.NIGHT -> SettingsRootRow(
                        icon = sectionIcon(sec),
                        title = sectionTitle(sec),
                        subtitle = sectionSummary(sec),
                        onClick = { openSection(sec) },
                        // Tinted like its own detail page, so the purple section is legible as one
                        // thing from the list rather than appearing out of nowhere on drill-in.
                        cardColor = NightSectionBg,
                        cardBorder = NightSectionBorder,
                        trailing = {
                            Switch(
                                checked = nightEnabled,
                                onCheckedChange = { v ->
                                    showExplainer("nightMode"); onNightEnabledChange(v)
                                },
                                interactionSource = rememberHapticInteractionSource()
                            )
                        }
                    )
                    SettingsSection.FLOURISH -> SettingsRootRow(
                        icon = sectionIcon(sec),
                        title = sectionTitle(sec),
                        subtitle = sectionSummary(sec),
                        onClick = { openSection(sec) },
                        // The whole body is master-gated, so an off master means the row is
                        // currently inert — but the switch stays lit and the page stays openable.
                        iconTint = Color.Unspecified,
                        dimmed = !moraleMasterEnabled,
                        trailing = {
                            Switch(
                                checked = moraleMasterEnabled,
                                onCheckedChange = onMoraleMasterChange,
                                interactionSource = rememberHapticInteractionSource()
                            )
                        }
                    )
                    SettingsSection.SHELTERS -> SettingsRootRow(
                        icon = sectionIcon(sec),
                        title = sectionTitle(sec),
                        subtitle = sectionSummary(sec),
                        // The row IS the entry point: it opens the shelter screen instead of a
                        // settings page of its own. The switch stays here so the map button can be
                        // re-enabled from the list even when it is hidden on the map.
                        onClick = onOpenShelterList,
                        trailing = {
                            Switch(
                                checked = sheltersEnabled,
                                onCheckedChange = onSheltersEnabledChange,
                                interactionSource = rememberHapticInteractionSource()
                            )
                        }
                    )
                    else -> SettingsRootRow(
                        icon = sectionIcon(sec),
                        title = sectionTitle(sec),
                        subtitle = sectionSummary(sec),
                        onClick = { openSection(sec) }
                    )
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
            }
            } else {
            val section = (route as SettingsRoute.Detail).section
            Box(modifier = Modifier.weight(1f).padding(16.dp)) {
            when (section) {
                SettingsSection.RELIABILITY -> SectionDetailBody(stateSummary = sectionSummary(section)) {
                    AlwaysSoundRow(
                        s = s,
                        checked = sirenOverride,
                        flash = flashId == "sirenOverride",
                        onShowExplainer = { showExplainer("sirenOverride") },
                        onChange = onSirenOverrideChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CriticalOfflineRows(
                        s = s,
                        override = criticalOfflineOverride,
                        bypassSilent = criticalOfflineBypassSilent,
                        onOverride = onCriticalOfflineOverrideChange,
                        onBypass = onCriticalOfflineBypassSilentChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BootRestartRow(
                        s = s,
                        checked = bootRestartEnabled,
                        onChange = onBootRestartChange,
                        onRequestDisable = { showBootRestartOffConfirm = true }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BatteryExemptionBlock(
                        s = s,
                        granted = batteryOptimized,
                        oemInfo = batteryOemInfo,
                        flash = flashId == "battery",
                        onAllow = { BatteryOptimization.requestExemption(appContext) }
                    )
                }
                // Unreachable by construction (`openSection` redirects shelters to the shelter
                // screen); a restored legacy route must bounce there too rather than render an
                // empty settings page.
                SettingsSection.SHELTERS -> LaunchedEffect(Unit) { onOpenShelterList() }
            SettingsSection.LOCATION -> SectionDetailBody(stateSummary = sectionSummary(section)) {
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
            SettingsSection.ALERTS -> SectionDetailBody(stateSummary = sectionSummary(section)) {
                    if (notificationsDisabled) {
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
                                    onClick = { AlertNotificationManager.openNotificationSettings(appContext) },
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
                    AlwaysSoundRow(
                        s = s,
                        checked = sirenOverride,
                        flash = flashId == "sirenOverride",
                        onShowExplainer = { showExplainer("sirenOverride") },
                        onChange = onSirenOverrideChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.notifyPolicyEnabledTitle,
                        description = s.notifyPolicyEnabledDesc,
                        checked = notifyPolicyEnabled,
                        onCheckedChange = onNotifyPolicyEnabledChange,
                        icon = painterResource(R.drawable.ic_notifications),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBadge = s.quietBadgeMain,
                        iconBadgeSuperscriptSuffix = s.quietBadgeSuper
                    )
                    // Indented under its master toggle so the eye reads "limit repeats" as the parent and the
// policy picker as the thing it controls, not as a sibling card.
                    AnimatedVisibility(visible = notifyPolicyEnabled) {
                        Column(modifier = Modifier.padding(start = 24.dp, end = 12.dp)) {
                        NotifyPolicyRow(
                            title = s.notifyPolicyTitle,
                            description = s.notifyPolicyDesc,
                            selected = zonePolicy,
                            whatIf = policyWhatIfFlow.value,
                            onChange = onZonePolicyChange,
                            s = s,
                            detail = {
                                AnimatedVisibility(visible = zonePolicy == ZonePolicy.DIGEST) {
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
                        )
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
                        title = s.autoDismissAllClearTitle,
                        description = s.autoDismissAllClearDesc,
                        checked = autoDismissAllClear,
                        onCheckedChange = onAutoDismissAllClearChange,
                        icon = painterResource(R.drawable.ic_notifications_off),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CriticalOfflineRows(
                        s = s,
                        override = criticalOfflineOverride,
                        bypassSilent = criticalOfflineBypassSilent,
                        onOverride = onCriticalOfflineOverrideChange,
                        onBypass = onCriticalOfflineBypassSilentChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BootRestartRow(
                        s = s,
                        checked = bootRestartEnabled,
                        onChange = onBootRestartChange,
                        onRequestDisable = { showBootRestartOffConfirm = true }
                    )
                    }
            // The master switch now lives on the root row, so the page body carries no copy of it —
// an off master shows the explanatory line instead of an empty tinted card.
            SettingsSection.NIGHT -> SectionDetailBody(
                stateSummary = sectionSummary(section),
                cardColor = NightSectionBg,
                cardBorder = NightSectionBorder
            ) {
                    if (!nightEnabled && !state.nightSleepActive) {
                        Text(
                            s.nightModeDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    NightModeCard(
                        lang = lang,
                        enabled = nightEnabled,
                        sleepActive = state.nightSleepActive,
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
                        nightOfficialRedEnabled = nightOfficialRedEnabled,
                        nightOfficialYellowEnabled = nightOfficialYellowEnabled,
                        onNightOfficialAlertCityScopeChange = onNightOfficialAlertCityScopeChange,
                        onNightOfficialRedChange = onNightOfficialRedChange,
                        onNightOfficialYellowChange = onNightOfficialYellowChange,
                        onSleepToggle = onNightSleepToggle
                    )
            }
            SettingsSection.THREATS -> SectionDetailBody(stateSummary = sectionSummary(section)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).explainerFlash(flashId == "iconSet")) {
                        Text(s.iconSetTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        IconSetSelector(lang = lang, selected = iconSet, onChange = onIconSetChange)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).explainerFlash(flashId == "deathAnimStyle")) {
                        Text(s.deathAnimStyleTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        // IntrinsicSize.Min + fillMaxHeight keeps the three cards the same height even when one caption
                        // wraps to two lines; without it each chip sizes to its own text.
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DeathAnimationStyle.values().forEach { style ->
                                SelectorChip(
                                    label = when (style) {
                                        DeathAnimationStyle.SIMPLE -> s.deathAnimStyleSimpleLabel
                                        DeathAnimationStyle.CLASSIC -> s.deathAnimStyleClassicLabel
                                        DeathAnimationStyle.ADVANCED -> s.deathAnimStyleAdvancedLabel
                                    },
                                    caption = when (style) {
                                        DeathAnimationStyle.SIMPLE -> s.deathAnimStyleSimpleNote
                                        DeathAnimationStyle.CLASSIC -> s.deathAnimStyleClassicNote
                                        DeathAnimationStyle.ADVANCED -> s.deathAnimStyleAdvancedNote
                                    },
                                    // Ranks how heavy each style is on the phone: particles, shards,
                                    // smoke — so the choice is legible before you try it.
                                    intensity = when (style) {
                                        DeathAnimationStyle.SIMPLE -> 1
                                        DeathAnimationStyle.CLASSIC -> 2
                                        DeathAnimationStyle.ADVANCED -> 3
                                    },
                                    selected = deathAnimationStyle == style,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onClick = { onDeathAnimationStyleChange(style) }
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
                    // No group headers or bulk toggles: each card carries its own Fast/Slow pill and
                    // its own Map/Alerts toggles, so the headers only repeated what the rows say.
                    // The full-width rule between the groups keeps the fast/slow split readable.
                    fastAndSlowGroups(lang, typeCatalog).forEachIndexed { index, (_, _, types) ->
                        if (index > 0) {
                            Spacer(Modifier.height(22.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(14.dp))
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            types.forEach { type ->
                                ThreatTypeRow(
                                    type = type,
                                    lang = lang,
                                    iconSet = iconSet,
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
            SettingsSection.SYSTEM -> SectionDetailBody(stateSummary = sectionSummary(section)) {
                    // Language first — it relabels every other page, so it reads as a page header.
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            .explainerFlash(flashId == "language")
                    ) {
                        LanguageSelector(selected = lang, onChange = onLanguageChange)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        flash = flashId == "mapScale"
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
                        onSmallChange = onShowSmallCitiesChange,
                        flash = flashId == "cityLabels"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertRegionModeRow(
                        title = s.fillAlertRegionsTitle,
                        description = s.fillAlertRegionsDesc,
                        selected = alertRegionMode,
                        cityLabelsLabel = s.alertRegionModeCityLabelsChip,
                        fillLabel = s.alertRegionModeFillChip,
                        borderLabel = s.alertRegionModeBorderChip,
                        onModeChange = onAlertRegionModeChange,
                        flash = flashId == "regionFill"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(
                        title = s.showBordersTitle,
                        description = s.showBordersDesc,
                        flash = flashId == "borders",
                        checked = showBorders,
                        onCheckedChange = onShowBordersChange,
                        icon = rememberVectorPainter(Icons.Outlined.CropFree),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AlertToggleRow(
                            title = s.showRegionBordersTitle,
                            description = s.showRegionBordersDesc,
                            checked = showRegionBorders,
                            onCheckedChange = onShowRegionBordersChange,
                            icon = rememberVectorPainter(Icons.Outlined.CropFree),
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Haptic press feedback
                    AlertToggleRow(
                        title = s.hapticsTitle,
                        description = s.hapticsDesc,
                        checked = hapticsEnabled,
                        flash = flashId == "haptics",
                        onCheckedChange = onHapticsEnabledChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Show threat IDs on map
                    AlertToggleRow(
                        title = s.showThreatIdsOnMapTitle,
                        description = s.showThreatIdsOnMapDesc,
                        checked = showThreatIdsOnMap,
                        flash = flashId == "threatIds",
                        onCheckedChange = onShowThreatIdsOnMapChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).explainerFlash(flashId == "overlap")) {
                        Text(s.overlapModeTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.overlapModeDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OverlapModeChip(OverlapMode.DEFAULT, s.overlapDefaultLabel, overlapMode, Modifier.weight(1f)) { onOverlapModeChange(OverlapMode.DEFAULT) }
                            OverlapModeChip(OverlapMode.COUNT, s.overlapCountLabel, overlapMode, Modifier.weight(1f)) { onOverlapModeChange(OverlapMode.COUNT) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertToggleRow(title = s.threatIconZoomTitle, description = s.threatIconZoomDesc, checked = threatIconZoom, onCheckedChange = onThreatIconZoomChange, icon = rememberVectorPainter(Icons.Default.ZoomIn), iconTint = MaterialTheme.colorScheme.onSurfaceVariant, flash = flashId == "iconZoom")
            }
            // Master switch lives on the root row; with it off the page says so instead of showing
            // nothing but the voice-pack note.
            SettingsSection.FLOURISH -> SectionDetailBody(stateSummary = sectionSummary(section)) {
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
            SettingsSection.MORE -> SectionDetailBody(stateSummary = sectionSummary(section)) {
                BatteryExemptionBlock(
                    s = s,
                    granted = batteryOptimized,
                    oemInfo = batteryOemInfo,
                    flash = flashId == "battery",
                    onAllow = { BatteryOptimization.requestExemption(appContext) }
                )
                // The actions read as one list, not five competing buttons: same row shape as every
                // other settings row, each with a line saying what it actually does. The duplicate
                // Refresh icon that made "replay" and "reset tips" indistinguishable is gone.
                SettingsActionRow(
                    icon = rememberVectorPainter(Icons.Filled.Refresh),
                    title = s.relaunchSetupTitle,
                    subtitle = s.moreRelaunchDesc,
                    onClick = onRelaunchSetup,
                    flash = flashId == "relaunch"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = painterResource(R.drawable.ic_check),
                    title = s.resetTipsTitle,
                    subtitle = s.moreResetTipsDesc,
                    onClick = onResetTips
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = painterResource(R.drawable.ic_map_ua),
                    title = s.guideSettingsButton,
                    subtitle = s.moreGuideDesc,
                    onClick = onOpenGuide,
                    flash = flashId == "guide"
                )
                if (BuildConfig.SELF_UPDATE) {
                    val updateState by updateFlow.collectAsState()
                    val latestVersion by latestVersionFlow.collectAsState()
                    val isChecking = updateState is UpdateState.Checking
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsActionRow(
                        icon = if (latestVersion != null) painterResource(R.drawable.ic_download)
                        else rememberVectorPainter(Icons.Filled.Refresh),
                        title = if (latestVersion != null) "${s.updateAvailableButton} · v$latestVersion"
                        else s.updateButton,
                        subtitle = s.moreUpdateDesc,
                        onClick = onCheckUpdate,
                        enabled = !isChecking,
                        trailingProgress = isChecking,
                        flash = flashId == "update"
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = rememberVectorPainter(Icons.Filled.Close),
                    title = s.exitButton,
                    subtitle = s.moreExitDesc,
                    onClick = onExit,
                    iconTint = MaterialTheme.colorScheme.error,
                    titleTint = MaterialTheme.colorScheme.error
                )
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.privacyPolicy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.hapticClickable {
                            uriHandler.openUri("https://odesaplay.com.ua/other_apps/oko/privacy.html")
                        }
                    )
                }
            }
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

/** The shelter (bulb) glyph, drawn inline: it has no drawable of its own. */
@Composable
private fun shelterPainter(): Painter = remember {
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
}

/** "Always sound" — shared by the Alerts page and the Reliability page, so the same row has one
 *  definition rather than two that can drift. */
@Composable
private fun AlwaysSoundRow(
    s: Strings.StringSet,
    checked: Boolean,
    flash: Boolean,
    onShowExplainer: () -> Unit,
    onChange: (Boolean) -> Unit
) {
    AlertToggleRow(
        title = s.sirenOverrideTitle,
        description = s.sirenOverrideDesc,
        checked = checked,
        onCheckedChange = { v -> onShowExplainer(); onChange(v) },
        icon = painterResource(R.drawable.ic_volume_up),
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        flash = flash
    )
}

/** The critical-offline master and its silent-mode bypass, shared by Alerts and Reliability. */
@Composable
private fun CriticalOfflineRows(
    s: Strings.StringSet,
    override: Boolean,
    bypassSilent: Boolean,
    onOverride: (Boolean) -> Unit,
    onBypass: (Boolean) -> Unit
) {
    AlertToggleRow(
        title = s.offlineCriticalOverrideTitle,
        description = s.offlineCriticalOverrideDesc,
        checked = override,
        onCheckedChange = onOverride,
        icon = painterResource(R.drawable.ic_wifi_off),
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    AnimatedVisibility(visible = override) {
        Column(modifier = Modifier.padding(start = 40.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AlertToggleRow(
                title = s.offlineCriticalBypassSilentTitle,
                description = s.offlineCriticalBypassSilentDesc,
                checked = bypassSilent,
                onCheckedChange = onBypass,
                icon = painterResource(R.drawable.ic_volume_up),
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Restart-after-reboot, shared by Alerts and Reliability; turning it off asks for confirmation. */
@Composable
private fun BootRestartRow(
    s: Strings.StringSet,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onRequestDisable: () -> Unit
) {
    AlertToggleRow(
        title = s.bootRestartTitle,
        description = s.bootRestartDesc,
        checked = checked,
        onCheckedChange = { v -> if (!v) onRequestDisable() else onChange(true) },
        emoji = "🛡️"
    )
}

/** The battery-exemption row. Not a toggle but a live OS check with a fix action, shared by the
 *  More page and the Reliability page. */
@Composable
private fun BatteryExemptionBlock(
    s: Strings.StringSet,
    granted: Boolean,
    oemInfo: OemInfo,
    flash: Boolean,
    onAllow: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp).explainerFlash(flash)) {
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(s.batteryGranted, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(s.batteryBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                if (oemInfo.isAggressive) s.batteryOemTitle else s.batteryTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (oemInfo.isAggressive) s.batteryOemBody else s.batteryBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAllow,
                interactionSource = rememberHapticInteractionSource(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(s.batteryAllowButton, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
