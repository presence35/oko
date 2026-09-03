package ua.ukrainedrones

import ua.ukrainedrones.engine.SpeedSource
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.ZoneParams
import ua.ukrainedrones.engine.typicalSpeedKmh
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription as semanticsContentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val UkraineBlue = Color(0xFF005BBB)

/** Night mode's boxed section inside the Alerts card: a darker purple tint + border. */
internal val NightSectionBg = Color(0xFF1A1130)
internal val NightSectionBorder = Color(0xFF44357A)

/** Collapse state of the Settings sections, hoisted to MainScreen. Reset to all-collapsed on
 *  every Settings open (see `openSettings`), so the user always lands on a clean list. */
data class SettingsCollapseState(
    val location: Boolean = false,
    val nightMode: Boolean = false,
    val alerts: Boolean = false,
    val flourish: Boolean = false,
    val shelters: Boolean = false,
    val threats: Boolean = false,
    val system: Boolean = false
) {
    companion object {
        val Saver = Saver<SettingsCollapseState, BooleanArray>(
            save = { it.let { s -> BooleanArray(7).apply {
                this[0] = s.location; this[1] = s.nightMode; this[2] = s.alerts
                this[3] = s.flourish; this[4] = s.shelters; this[5] = s.threats; this[6] = s.system
            } } },
            restore = { b -> SettingsCollapseState(
                location = b.getOrElse(0) { false },
                nightMode = b.getOrElse(1) { false },
                alerts = b.getOrElse(2) { false },
                flourish = b.getOrElse(3) { false },
                shelters = b.getOrElse(4) { false },
                threats = b.getOrElse(5) { false },
                system = b.getOrElse(6) { false }
            ) }
        )
    }
}

/** The collapsible section cards, in LazyColumn order (item 0 is the disclaimer card).
 *  `index` is the section's LazyColumn position with the full list shown. */
private enum class SettingsSection(val index: Int) {
    LOCATION(1), ALERTS(2), NIGHT(3), SHELTERS(4), THREATS(5), SYSTEM(6), FLOURISH(7)
}

/** Standalone action buttons below the section cards, also matched by the search box. */
private enum class StandaloneSetting { RELAUNCH, GUIDE, UPDATE, EXIT }

/** A suggestion chip shown by the search box: a tappable hint that fills the query with a
 *  keyword that resolves to its setting. */
private data class SearchChip(
    val labelUa: String,
    val labelEn: String,
    val queryUa: String,
    val queryEn: String
) {
    fun label(lang: AppLanguage): String = if (lang == AppLanguage.UA) labelUa else labelEn
    fun query(lang: AppLanguage): String = if (lang == AppLanguage.UA) queryUa else queryEn
}

/** A related concept: alternative words a user might type (synonyms, intent words, other
 *  languages) mapped to the suggestion chips that point at what they probably want. */
private data class RelatedConcept(
    val words: List<String>,
    val chips: List<SearchChip>
)

/** Normalizes text for search matching: lowercase, drop apostrophes/quotes, dashes → spaces. */
private fun String.searchNorm(): String = lowercase()
    .replace(Regex("[''´`]"), "")
    .replace(Regex("[-–—]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** True when every query word is a substring of at least one keyword. */
private fun matchesSearch(queryWords: List<String>, keywords: List<String>): Boolean =
    queryWords.all { qw -> keywords.any { qw in it } }

/** Classic Levenshtein edit distance, for the "did you mean" suggestions. */
private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1) { j -> if (j == 0) i else 0 }
        for (j in 1..b.length) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        prev = cur
    }
    return prev[b.length]
}

private fun searchChip(labelUa: String, labelEn: String, queryUa: String, queryEn: String) =
    SearchChip(labelUa, labelEn, queryUa.searchNorm(), queryEn.searchNorm())

private fun chipList(vararg c: SearchChip) = c.toList()

/** The search database: curated direct keywords per target + pooled related concepts. */
private data class SettingsSearchDb(
    val sectionDirect: Map<SettingsSection, List<String>>,
    val standaloneDirect: Map<StandaloneSetting, List<String>>,
    val related: List<RelatedConcept>
)

/** Builds the settings search database. Direct keywords are curated (both languages), threat-type
 *  terms are auto-derived from the catalog, and related concepts map user intents to chips.
 *  Future languages extend the keyword/concept lists without changing the matching logic. */
private fun buildSearchDb(pinnedCity: City?): SettingsSearchDb {
    fun kw(vararg words: String): List<String> =
        words.map { it.searchNorm() }.filter { it.isNotBlank() }.distinct()
    val threatTerms = ThreatTypeCatalog.INFO.values.flatMap { info ->
        val ua = info.labelUa.searchNorm()
        val en = info.labelEn.searchNorm()
        listOf(ua, en) + ua.split(" ") + en.split(" ")
    }.filter { it.length >= 2 }
    val cityTerms = pinnedCity?.let { listOf(it.nameUa.searchNorm(), it.nameEn.searchNorm()) }
        ?: emptyList()
    val sectionDirect = mapOf(
        SettingsSection.LOCATION to kw(
            "follow", "follow me", "location", "focus", "pin", "city", "gps", "calibrate",
            "periodic", "network", "fix", "refresh", "position",
            "локація", "фокус", "місто", "прив'язка", "пін", "слідувати", "за мною",
            "калібрування", "періодичн", "мереж", "фікс", "позиція"
        ) + cityTerms,
        SettingsSection.NIGHT to kw(
            "night", "night mode", "zone", "zones", "vibration", "vibrate",
            "ніч", "нічний", "вночі", "зона", "зони", "вібрація"
        ),
        SettingsSection.ALERTS to kw(
            "alert", "alerts", "siren", "sirens", "sound", "official", "notification",
            "vibration", "vibrate", "volume", "chime",
            "оповіщення", "сповіщення", "сирена", "звук", "офіційн", "офіційна",
            "офіційні", "вібрація", "вібро", "гучність"
        ),
        SettingsSection.FLOURISH to kw(
            "fun", "animation", "bullet", "death", "flourish", "shoot", "tally", "neutralized",
            "calm", "icon", "icons", "icon set",
            "розваг", "анімація", "куля", "збиття", "загибель", "лічильник", "знешкоджен", "загроза",
            "заспокійлив", "іконка", "іконки", "набір іконок"
        ),
        SettingsSection.SHELTERS to kw(
            "shelter", "shelters", "directory", "укриття", "сховище", "бомбосховище", "каталог"
        ),
        SettingsSection.THREATS to (kw(
            "threat", "threats", "map", "icon", "icons", "fast", "slow", "type", "types", "group",
            "загроз", "загроза", "загрози", "мапа", "іконка", "іконки", "швидкі", "повільні",
            "швидк", "повільн", "тип", "типи", "група",
            "shahed", "шахед", "moped", "мопед", "drone", "дрон", "безпілотник", "бпла", "uav",
            "fpv", "фпв", "missile", "ракета", "cruise", "крилата", "ballistic", "балістика",
            "балістична", "kab", "каб", "bomb", "бомба", "aviation", "авіація", "mig", "міг",
            "літак", "recon", "reconnaissance", "розвідка", "розвідувальний", "unknown", "невідомий"
        ) + threatTerms),
        SettingsSection.SYSTEM to kw(
            "system", "display", "interface", "language", "ukrainian", "english", "icon", "icons",
            "card", "cards", "size", "scale", "battery", "exempt",
            "система", "інтерфейс", "дисплей", "мова", "українськ", "англійськ", "іконка", "іконки",
            "картка", "картки", "розмір", "масштаб", "батарея",
            "енерг", "звільнення"
        )
    )
    val standaloneDirect = mapOf(
        StandaloneSetting.RELAUNCH to kw(
            "relaunch", "replay", "wizard", "setup", "перезапуск", "повторити", "початкове"
        ),
        StandaloneSetting.GUIDE to kw(
            "guide", "help", "features", "путівник", "допомога", "функції"
        ),
        StandaloneSetting.UPDATE to kw(
            "update", "check", "download", "version", "new", "оновлення", "перевір", "завантаж", "версія"
        ),
        StandaloneSetting.EXIT to kw(
            "exit", "stop", "monitoring", "quit", "вийти", "зупинити", "моніторинг", "вихід"
        )
    )
    val related = listOf(
        RelatedConcept(kw("quiet", "тихо", "mute", "беззвучний", "тиша", "silent"), chipList(
            searchChip("Сирена завжди", "Sirens always sound", "сирена", "sirens"),
            searchChip("Вібрація", "Vibration", "вібрація", "vibration")
        )),
        RelatedConcept(kw("ring", "дзвонити", "дзвінок", "alarm", "будильник", "гудок"), chipList(
            searchChip("Сирена завжди", "Sirens always sound", "сирена", "sirens")
        )),
        RelatedConcept(kw("air raid", "тривога", "сигнал", "emergency", "екстрений"), chipList(
            searchChip("Офіційні сповіщення", "Official alerts", "офіційні", "official")
        )),
        RelatedConcept(kw("bomb shelter", "бомбосховище", "сховище"), chipList(
            searchChip("Укриття", "Shelters", "укриття", "shelter")
        )),
        RelatedConcept(kw("dark", "темний", "темрява", "сон", "sleep", "тихіший", "вечір", "evening"), chipList(
            searchChip("Нічний режим", "Night mode", "ніч", "night")
        )),
        RelatedConcept(kw("danger", "небезпека", "небезпечний"), chipList(
            searchChip("Типи загроз", "Threat types", "загрози", "threats")
        )),
        RelatedConcept(kw("plane", "літак", "вертоліт", "helicopter", "aircraft", "гелікоптер"), chipList(
            searchChip("Авіація", "Aviation", "авіація", "aviation")
        )),
        RelatedConcept(kw("theme", "тема", "темна", "оформлення", "вигляд", "appearance"), chipList(
            searchChip("Стиль іконок", "Icon style", "іконки", "icon"),
            searchChip("Розмір картки", "Card size", "розмір", "size")
        )),
        RelatedConcept(kw("переклад", "translation", "translate", "мови"), chipList(
            searchChip("Мова", "Language", "мова", "language")
        )),
        RelatedConcept(kw("вибух", "explosion", "ефект", "effect"), chipList(
            searchChip("Анімація знищення", "Death animation", "анімація", "death animation")
        )),
        RelatedConcept(kw("заряд", "автономність", "charge", "power", "енергозбереження", "економія"), chipList(
            searchChip("Батарея", "Battery exemption", "батарея", "battery")
        )),
        RelatedConcept(kw("геолокація", "місцезнаходження", "трекінг", "tracking", "координати", "coordinates", "де я", "звідки"), chipList(
            searchChip("Слідувати за мною", "Follow me", "слідувати", "follow me"),
            searchChip("Прив'язати місто", "Pin city", "місто", "pin city")
        )),
        RelatedConcept(kw("інструкція", "як працює", "how to", "tutorial", "що нового"), chipList(
            searchChip("Путівник", "Feature guide", "путівник", "guide")
        )),
        RelatedConcept(kw("вимкнути", "disable", "turn off", "вимкнення"), chipList(
            searchChip("Зупинити й вийти", "Stop & exit", "вийти", "exit")
        )),
        RelatedConcept(kw("час", "time", "розклад", "schedule"), chipList(
            searchChip("Нічний режим", "Night mode", "ніч", "night")
        ))
    )
    return SettingsSearchDb(sectionDirect, standaloneDirect, related)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    lang: AppLanguage,
    listState: LazyListState,
    onThreatsScrollHandled: () -> Unit,
    scrollToThreatsTick: Int,
    scrollToNightMode: Boolean,
    collapse: SettingsCollapseState,
    onCollapseChange: (SettingsCollapseState) -> Unit,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    officialAlertsEnabled: Boolean,
    officialAlertCityScope: Boolean,
    sirenOverride: Boolean,
    criticalOfflineOverride: Boolean,
    criticalOfflineBypassSilent: Boolean,
    nightEnabled: Boolean,
    nightStartMin: Int,
    nightEndMin: Int,
    nightUseCustomZones: Boolean,
    slowRedKm: Int,
    slowYellowKm: Int,
    fastRedMin: Int,
    fastYellowMin: Int,
    nightSlowRedKm: Int,
    nightSlowYellowKm: Int,
    nightFastRedMin: Int,
    nightFastYellowMin: Int,
    nightSlowRedArmed: Boolean,
    nightSlowYellowArmed: Boolean,
    nightFastRedArmed: Boolean,
    nightFastYellowArmed: Boolean,
    nightZoneSirenOverride: Boolean,
    nightOfficialSirenOverride: Boolean,
    disclaimerCollapsed: Boolean,
    disclaimerReadCount: Int,
    followMe: Boolean,
    pinnedCity: City?,
    threatCardSize: ThreatCardSize,
    iconSet: ThreatIconSet,
    showMapScale: Boolean,
    showMediumCities: Boolean,
    showSmallCities: Boolean,
    sheltersEnabled: Boolean,
    periodicGps: Boolean,
    calmMessagesEnabled: Boolean,
    hapticsEnabled: Boolean,
    deathAnimationEnabled: Boolean,
    flybyAnimationEnabled: Boolean,
    onFlybyAnimationChange: (Boolean) -> Unit,
    followBullet: Boolean,
    neutralizedTallyEnabled: Boolean,
    neutralizedTallyAllUkraine: Boolean,
    fastGroupCollapsed: Boolean,
    slowGroupCollapsed: Boolean,
    versionName: String,
    isChecking: Boolean,
    latestVersion: String?,
    onBack: () -> Unit,
    activeExplainer: Explainer?,
    onExplainerChange: (Explainer?) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    onThreatMapToggleAll: (Set<ThreatType>, Boolean) -> Unit,
    onThreatAlertToggleAll: (Set<ThreatType>, Boolean) -> Unit,
    onOfficialAlertsChange: (Boolean) -> Unit,
    onOfficialAlertCityScopeChange: (Boolean) -> Unit,
    onSirenOverrideChange: (Boolean) -> Unit,
    onCriticalOfflineOverrideChange: (Boolean) -> Unit,
    onCriticalOfflineBypassSilentChange: (Boolean) -> Unit,
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
    onFollowMeChange: (Boolean) -> Unit,
    onPinnedCityChange: (City?) -> Unit,
    onPeriodicGpsChange: (Boolean) -> Unit,
    onCalmMessagesChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onDisclaimerCollapse: (Boolean) -> Unit,
    onDisclaimerShown: () -> Unit,
    onThreatCardSizeChange: (ThreatCardSize) -> Unit,
    onIconSetChange: (ThreatIconSet) -> Unit,
    onShowMapScaleChange: (Boolean) -> Unit,
    onShowMediumCitiesChange: (Boolean) -> Unit,
    onShowSmallCitiesChange: (Boolean) -> Unit,
    onSheltersEnabledChange: (Boolean) -> Unit,
    onOpenShelterList: () -> Unit = {},
    onJustFunMasterChange: (Boolean) -> Unit,
    justFunMasterEnabled: Boolean,
    onDeathAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
    onNeutralizedTallyChange: (Boolean) -> Unit,
    onNeutralizedTallyAllUkraineChange: (Boolean) -> Unit,
    onFastGroupCollapse: (Boolean) -> Unit,
    onSlowGroupCollapse: (Boolean) -> Unit,
    onExit: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenGuide: () -> Unit,
    onRelaunchSetup: () -> Unit,
    onResetTips: () -> Unit = {}
) {
    val s = Strings.get(lang)
    // Search box: filters sections + standalone actions by curated keywords, surfaces suggestion
    // chips for related concepts and "did you mean" for typos. Query is plain remember so it
    // clears itself every time the screen is reopened.
    var searchQuery by remember { mutableStateOf("") }
    val searchNormalized = searchQuery.searchNorm()
    val searchWords = searchNormalized.split(" ").filter { it.isNotBlank() }
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
                                IconButton(onClick = { searchQuery = "" }) {
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
                    IconButton(onClick = onBack) {
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

            item {
                // "Official signals come first" — first, default expanded, needs two taps to collapse.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val disclaimerInteraction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressTick(disclaimerInteraction)
                                .clickable(
                                    interactionSource = disclaimerInteraction,
                                    indication = ripple(bounded = true),
                                    onClick = onDisclaimerClick
                                )
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
            item {
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
            item {
                CollapsibleSectionCard(
                    title = s.alertsLabel,
                    icon = rememberVectorPainter(Icons.Default.Notifications),
                    expanded = collapse.alerts,
                    subtitle = s.alertsSubtitle(officialAlertsEnabled, sirenOverride),
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
                                        if (lang == AppLanguage.UA) "Сповіщення вимкнено" else "Notifications disabled",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (lang == AppLanguage.UA)
                                        "Додаток не зможе показувати тривоги та сирени. Увімкніть сповіщення в налаштуваннях системи."
                                    else
                                        "The app cannot deliver sirens or alert notifications. Enable notifications in system settings.",
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
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (lang == AppLanguage.UA) "Увімкнути сповіщення" else "Enable notifications",
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
                        note = s.officialAlertsRedTridentNote,
                        noteIcon = painterResource(R.drawable.ic_trident),
                        noteIconTint = Color(0xFFD32F2F),
                        flash = flashId == "officialAlerts"
                    )
                    AnimatedVisibility(visible = officialAlertsEnabled) {
                        Column(modifier = Modifier.padding(start = 40.dp)) {
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
                        title = s.offlineCriticalOverrideTitle,
                        description = s.offlineCriticalOverrideDesc,
                        checked = criticalOfflineOverride,
                        onCheckedChange = onCriticalOfflineOverrideChange,
                        icon = painterResource(R.drawable.ic_notifications_off),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
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
            item {
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
                    cardBorder = NightSectionBorder
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
                        daySlowRedKm = slowRedKm,
                        daySlowYellowKm = slowYellowKm,
                        dayFastRedMin = fastRedMin,
                        dayFastYellowMin = fastYellowMin,
                        onEnabledChange = { v -> showExplainer("nightMode"); onNightEnabledChange(v) },
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
                        flash = flashId == "nightMode"
                    )
                }
            }

            }
            if (searching.not() || SettingsSection.SHELTERS in matchedSections) {
            item {
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
                            .clickable { onOpenShelterList() }
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
            item {
                CollapsibleSectionCard(
                    title = s.threatsLabel,
                    icon = rememberVectorPainter(Icons.Default.Warning),
                    expanded = collapse.threats,
                    subtitle = s.threatsSubtitle(hiddenTypes.size, silencedTypes.size),
                    onToggle = { onCollapseChange(collapse.copy(threats = !collapse.threats)) }
                ) {
                    fastAndSlowGroups(lang).forEachIndexed { index, (groupIcon, groupTitle, types) ->
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
                                .clickable {
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
                }
            }

            }
            if (searching.not() || SettingsSection.SYSTEM in matchedSections) {
            item {
                CollapsibleSectionCard(
                    title = s.systemSectionTitle,
                    icon = painterResource(id = R.drawable.ic_language),
                    expanded = collapse.system,
                    subtitle = s.systemSubtitle(lang, threatCardSize, iconSet),
                    onToggle = { onCollapseChange(collapse.copy(system = !collapse.system)) }
                ) {
                    // Language Switcher
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        LanguageFlag(
                            emoji = "\uD83C\uDDFA\uD83C\uDDE6",
                            active = lang == AppLanguage.UA,
                            onClick = { onLanguageChange(AppLanguage.UA) },
                            modifier = Modifier.weight(1f)
                        )
                        LanguageFlag(
                            emoji = "\uD83C\uDDE8\uD83C\uDDE6",
                            active = lang == AppLanguage.EN,
                            onClick = { onLanguageChange(AppLanguage.EN) },
                            modifier = Modifier.weight(1f)
                        )
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
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CityLabelTogglesRow(
                        title = s.cityLabelsTitle,
                        description = s.cityLabelsDesc,
                        mediumChecked = showMediumCities,
                        smallChecked = showSmallCities,
                        mediumLabel = s.mediumCitiesChip,
                        smallLabel = s.smallCitiesChip,
                        onMediumChange = onShowMediumCitiesChange,
                        onSmallChange = onShowSmallCitiesChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Haptic press feedback
                    AlertToggleRow(
                        title = s.hapticsTitle,
                        description = s.hapticsDesc,
                        checked = hapticsEnabled,
                        onCheckedChange = onHapticsEnabledChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Reset tip counters
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        OutlinedButton(
                            onClick = onResetTips,
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
            item {
                CollapsibleSectionCard(
                    title = s.justFunSectionTitle,
                    icon = painterResource(R.drawable.ic_explosion),
                    emoji = "🥳",
                    expanded = collapse.flourish,
                    subtitle = s.justFunSubtitle(deathAnimationEnabled, neutralizedTallyEnabled),
                    onToggle = { onCollapseChange(collapse.copy(flourish = !collapse.flourish)) },
                    trailing = {
                        Switch(
                            checked = justFunMasterEnabled,
                            onCheckedChange = onJustFunMasterChange
                        )
                    }
                ) {
                    AnimatedVisibility(visible = justFunMasterEnabled) {
                        Column {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    s.iconSetTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(10.dp))
                                IconSetSelector(
                                    lang = lang,
                                    selected = iconSet,
                                    onChange = onIconSetChange
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AlertToggleRow(
                                title = s.calmMessagesTitle,
                                description = s.calmMessagesDesc,
                                checked = calmMessagesEnabled,
                                onCheckedChange = onCalmMessagesChange,
                                icon = painterResource(R.drawable.ic_peace),
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AlertToggleRow(
                                title = s.flybyAnimationLabel,
                                description = s.flybyAnimationDesc,
                                checked = flybyAnimationEnabled,
                                onCheckedChange = onFlybyAnimationChange,
                                icon = painterResource(R.drawable.ic_airplay),
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AlertToggleRow(
                                title = s.deathAnimationTitle,
                                description = s.deathAnimationDesc,
                                checked = deathAnimationEnabled,
                                onCheckedChange = onDeathAnimationChange,
                                icon = painterResource(R.drawable.ic_explosion),
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            AnimatedVisibility(visible = deathAnimationEnabled) {
                                Column(modifier = Modifier.padding(start = 40.dp)) {
                                    AlertToggleRow(
                                        title = s.followBulletTitle,
                                        description = s.followBulletDesc,
                                        checked = followBullet,
                                        onCheckedChange = onFollowBulletChange,
                                        icon = painterResource(R.drawable.bullet),
                                        iconTint = null
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AlertToggleRow(
                                title = s.neutralizedTallyTitle,
                                description = s.neutralizedTallyDesc,
                                checked = neutralizedTallyEnabled,
                                onCheckedChange = onNeutralizedTallyChange,
                                icon = rememberVectorPainter(Icons.Default.Notifications),
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                iconBadge = "21"
                            )
                            if (neutralizedTallyEnabled) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(modifier = Modifier.padding(start = 40.dp)) {
                                    AlertToggleRow(
                                        title = s.neutralizedTallyAllUkraineTitle,
                                        description = s.neutralizedTallyAllUkraineDesc,
                                        checked = neutralizedTallyAllUkraine,
                                        onCheckedChange = onNeutralizedTallyAllUkraineChange,
                                        emoji = "🇺🇦"
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        s.justFunNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            }
            if (noSearchResults) {
                item {
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
            item {
                OutlinedButton(
                    onClick = onRelaunchSetup,
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
            item {
                OutlinedButton(
                    onClick = onOpenGuide,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.guideSettingsButton, fontWeight = FontWeight.SemiBold)
                }
            }

            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (searching.not() || StandaloneSetting.UPDATE in matchedStandalone) {
            item {
                if (isChecking) {
                    Button(
                        onClick = onCheckUpdate,
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
            item {
                Button(
                    onClick = onExit,
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
            item {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val telegramUrl = "https://t.me/odesaplay_bot"
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.clickable { uriHandler.openUri(telegramUrl) },
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
        FeatureExplainerDialog(explainer = exp, s = s, onDismiss = dismissExplainer)
    }

    }

private fun timeText(min: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightModeCard(
    lang: AppLanguage,
    enabled: Boolean,
    startMin: Int,
    endMin: Int,
    useCustomZones: Boolean,
    slowRedKm: Int,
    slowYellowKm: Int,
    fastRedMin: Int,
    fastYellowMin: Int,
    slowRedArmed: Boolean,
    slowYellowArmed: Boolean,
    fastRedArmed: Boolean,
    fastYellowArmed: Boolean,
    zoneSirenOverride: Boolean,
    officialSirenOverride: Boolean,
    daySlowRedKm: Int,
    daySlowYellowKm: Int,
    dayFastRedMin: Int,
    dayFastYellowMin: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
    onUseCustomZonesChange: (Boolean) -> Unit,
    onSlowRedChange: (Int) -> Unit,
    onSlowYellowChange: (Int) -> Unit,
    onFastRedChange: (Int) -> Unit,
    onFastYellowChange: (Int) -> Unit,
    onSlowRedArmedChange: (Boolean) -> Unit,
    onSlowYellowArmedChange: (Boolean) -> Unit,
    onFastRedArmedChange: (Boolean) -> Unit,
    onFastYellowArmedChange: (Boolean) -> Unit,
    onZoneSirenOverrideChange: (Boolean) -> Unit,
    onOfficialSirenOverrideChange: (Boolean) -> Unit,
    flash: Boolean = false
) {
    val s = Strings.get(lang)
    var editing by remember { mutableStateOf<String?>(null) }  // "start" | "end" | null

    Column {
        AlertToggleRow(
            title = s.nightModeLabel,
            description = s.nightModeDesc,
            checked = enabled,
            onCheckedChange = onEnabledChange,
            flash = flash
        )
        if (enabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NightTimeField(
                    label = s.nightStartTimeLabel,
                    minute = startMin,
                    onClick = { editing = "start" },
                    modifier = Modifier.weight(1f)
                )
                NightTimeField(
                    label = s.nightEndTimeLabel,
                    minute = endMin,
                    onClick = { editing = "end" },
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.padding(horizontal = 14.dp)) {
                SectionCaption(s.nightSoundLabel)
            }
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                AlertToggleRow(
                    title = s.nightOfficialSirenOverrideTitle,
                    description = s.nightOfficialSirenOverrideDesc,
                    checked = officialSirenOverride,
                    onCheckedChange = onOfficialSirenOverrideChange,
                    icon = painterResource(R.drawable.ic_trident)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.nightZoneSirenOverrideTitle,
                    description = s.nightZoneSirenOverrideDesc,
                    checked = zoneSirenOverride,
                    onCheckedChange = onZoneSirenOverrideChange,
                    icon = painterResource(R.drawable.ic_volume_up),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AlertToggleRow(
                title = s.nightCustomZonesTitle,
                description = s.nightCustomZonesDesc,
                checked = useCustomZones,
                onCheckedChange = onUseCustomZonesChange
            )
            if (useCustomZones) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    GroupedZoneSection {
                        SectionCaption(s.slowSectionLabel, leadingIcon = R.drawable.ic_turtle, leadingDesc = s.slowGroupIconDesc, leadingTint = TurtleGreen)
                        ZoneRow(
                            value = slowRedKm,
                            range = 1f..20f,
                            unit = s.kmUnit,
                            accent = ZoneRedColor,
                            armed = slowRedArmed,
                            bellDesc = s.alertsBellToggle,
                            reference = daySlowRedKm,
                            dayLabel = s.dayShortLabel,
                            onArmedChange = onSlowRedArmedChange,
                            onCommit = onSlowRedChange
                        )
                        Spacer(Modifier.height(10.dp))
                        ZoneRow(
                            value = slowYellowKm,
                            range = (slowRedKm + 2).toFloat()..50f,
                            unit = s.kmUnit,
                            accent = ZoneYellowColor,
                            armed = slowYellowArmed,
                            bellDesc = s.alertsBellToggle,
                            reference = daySlowYellowKm,
                            dayLabel = s.dayShortLabel,
                            onArmedChange = onSlowYellowArmedChange,
                            onCommit = onSlowYellowChange
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    GroupedZoneSection {
                        SectionCaption(s.fastSectionLabel, leadingIcon = R.drawable.ic_lightning, leadingDesc = s.fastGroupIconDesc)
                        ZoneRow(
                            value = fastRedMin,
                            range = 1f..5f,
                            unit = s.minUnit,
                            accent = ZoneRedColor,
                            armed = fastRedArmed,
                            bellDesc = s.alertsBellToggle,
                            reference = dayFastRedMin,
                            dayLabel = s.dayShortLabel,
                            onArmedChange = onFastRedArmedChange,
                            onCommit = onFastRedChange
                        )
                        Spacer(Modifier.height(10.dp))
                        ZoneRow(
                            value = fastYellowMin,
                            range = (fastRedMin + 2).toFloat()..20f,
                            unit = s.minUnit,
                            accent = ZoneYellowColor,
                            armed = fastYellowArmed,
                            bellDesc = s.alertsBellToggle,
                            reference = dayFastYellowMin,
                            dayLabel = s.dayShortLabel,
                            onArmedChange = onFastYellowArmedChange,
                            onCommit = onFastYellowChange
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!slowRedArmed || !slowYellowArmed || !fastRedArmed || !fastYellowArmed) {
                        Text(
                            s.nightMuteExitNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }

    if (editing != null) {
        val initial = if (editing == "start") startMin else endMin
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(if (editing == "start") s.nightStartTimeLabel else s.nightEndTimeLabel)
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = timeState.hour * 60 + timeState.minute
                    if (editing == "start") onStartChange(minute) else onEndChange(minute)
                    editing = null
                }) { Text(s.okButton) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(s.backButton) }
            }
        )
    }
}

@Composable
private fun NightTimeField(
    label: String,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                timeText(minute),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun AlertToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: Painter? = null,
    iconTint: Color? = null,
    iconBadge: String? = null,
    emoji: String? = null,
    note: String? = null,
    noteIcon: Painter? = null,
    noteIconTint: Color? = null,
    flash: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
            .explainerFlash(flash)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPressed) 0.06f else 0f))
            .pressTick(interactionSource)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            )
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (emoji != null) {
            Text(
                text = emoji,
                fontSize = 22.sp,
                modifier = Modifier.size(28.dp)
            )
        } else {
            icon?.let {
                Box(modifier = Modifier.size(28.dp)) {
                    Image(
                        painter = it,
                        contentDescription = null,
                        colorFilter = iconTint?.let { c -> ColorFilter.tint(c) },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (iconBadge != null) {
                        Text(
                            text = iconBadge,
                            color = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            note?.let {
                Spacer(Modifier.height(6.dp))
                if (noteIcon != null) {
                    val iconId = "noteIcon"
                    Text(
                        buildAnnotatedString {
                            appendInlineContent(iconId, "[icon]")
                            append(" $it")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        inlineContent = mapOf(
                            iconId to InlineTextContent(
                                Placeholder(
                                    14.sp,
                                    14.sp,
                                    PlaceholderVerticalAlign.TextCenter
                                )
                            ) {
                                Image(
                                    painter = noteIcon,
                                    contentDescription = null,
                                    colorFilter = noteIconTint?.let { ColorFilter.tint(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        )
                    )
                } else {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.scale(if (isPressed) 0.92f else 1f)
        )
    }
}

/** City-labels row: title/description with two independent FilterChips (medium / small towns),
 *  each acting as its own on/off toggle. Both default on. */
@Composable
private fun CityLabelTogglesRow(
    title: String,
    description: String,
    mediumChecked: Boolean,
    smallChecked: Boolean,
    mediumLabel: String,
    smallLabel: String,
    onMediumChange: (Boolean) -> Unit,
    onSmallChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilterChip(
            selected = mediumChecked,
            onClick = { onMediumChange(!mediumChecked) },
            label = { Text(mediumLabel, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_city),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            colors = CityChipColors(selected = mediumChecked)
        )
        FilterChip(
            selected = smallChecked,
            onClick = { onSmallChange(!smallChecked) },
            label = { Text(smallLabel, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_house),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            colors = CityChipColors(selected = smallChecked)
        )
    }
}

/** ON = vivid primary pill with dark content; OFF = muted grey pill — the two states
 *  can't be confused in the dark theme. */
@Composable
private fun CityChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = Color(0xFF1E1E1E),
    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    iconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = Color(0xFF0D1117),
    selectedLeadingIconColor = Color(0xFF0D1117)
)

/** Bordered, rounded box that visually groups a set of zone slider rows (night custom zones). */
@Composable
private fun GroupedZoneSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content
    )
}

/** A single threat's settings card: icon + name/desc, compact Map/Alerts switches on the right. */
@Composable
private fun ThreatSettingsCard(
    type: ThreatType,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    expanded: Boolean,
    onExpandChange: () -> Unit,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    flash: Boolean = false
) {
    val s = Strings.get(lang)
    val info = ThreatTypeCatalog.INFO.getValue(type)
    val label = if (lang == AppLanguage.UA) info.labelUa else info.labelEn
    val description = if (lang == AppLanguage.UA) info.descriptionUa else info.descriptionEn
    val details = if (lang == AppLanguage.UA) info.detailsUa else info.detailsEn
    val joke = if (lang == AppLanguage.UA) info.jokeUa else info.jokeEn
    val onMap = type !in hiddenTypes
    val onAlerts = type !in silencedTypes
    val typicalSpeed = typicalSpeedKmh(type)?.roundToInt()

    Card(modifier = Modifier.fillMaxWidth().explainerFlash(flash)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThreatIcon(
                    type = type,
                    set = iconSet,
                    size = 36.dp,
                    contentDescription = label
                )
                Spacer(Modifier.width(12.dp))
                val expandInteraction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pressTick(expandInteraction)
                        .clickable(
                            interactionSource = expandInteraction,
                            indication = ripple(bounded = true),
                            onClick = onExpandChange
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = s.moreInfoLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconToggle(
                        icon = Icons.Filled.Place,
                        contentDescription = s.threatMapLabel,
                        on = onMap,
                        enabled = true,
                        onClick = { onThreatMapToggle(type, !onMap) }
                    )
                    IconToggle(
                        icon = Icons.Filled.Notifications,
                        contentDescription = s.threatAlertLabel,
                        on = onAlerts,
                        enabled = true,
                        onClick = { onThreatAlertToggle(type, !onAlerts) }
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    typicalSpeed?.let {
                        Surface(
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                "~$it ${s.speedUnit}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (type == ThreatType.UNKNOWN) 220.dp else 160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(
                                if (type == ThreatType.UNKNOWN) R.drawable.ic_unknown_cat
                                else IconCatalog.res(type, iconSet)
                            ),
                            contentDescription = label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (type == ThreatType.UNKNOWN) Modifier.scale(1.15f) else Modifier
                                )
                        )
                    }
                    joke.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinCityRow(
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
private fun GpsCalibrationRow(
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
            showToast(context, s.calibratingGps, cardVisible = false)
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
            showToast(context, s.calibratingGps, cardVisible = false)
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

/** Mock threat + proximity driving the live card-size previews. */
private val PreviewThreat = NormalizedThreat(
    id = "preview",
    type = "shahed",
    title = "БпЛА",
    region = "Одеська область",
    district = null,
    locality = "Одеса",
    lat = 46.4825,
    lon = 30.7233,
    heading = null,
    bearingDeg = 210.0,
    status = "active",
    advisory = false,
    areaOnly = false,
    confirmations = 3,
    reliability = "MEDIUM",
    count = 2,
    explanationShort = "БпЛА курсом на Чорноморськ",
    speedKmh = 180.0,
    uncertaintyKm = 1.5,
    positionQuality = "approx",
    confirmedAtMillis = null,
    updatedAtMillis = null,
    trail = emptyList()
)

private val PreviewProximity = ThreatProximity(
    predicted = LatLng(46.48, 30.72),
    distToUserKm = 6.0,
    etaToUserMin = 4.5,
    params = ZoneParams(slowRedKm = 20, slowYellowKm = 50, fastRedMin = 5, fastYellowMin = 20),
    speedSource = SpeedSource.RECORDED,
    speedKmh = 180.0
)

/** Two selectable tiles, each a live scaled preview of that card size. */
@Composable
private fun ThreatCardSizeSelector(
    lang: AppLanguage,
    selected: ThreatCardSize,
    onChange: (ThreatCardSize) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThreatCardSize.values().forEach { size ->
            CardSizeTile(
                size = size,
                lang = lang,
                selected = size == selected,
                onClick = { onChange(size) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CardSizeTile(
    size: ThreatCardSize,
    lang: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Draw the real card at a fixed nominal width, then scale it down to the tile.
            // The height follows the scaled card exactly, so there's no dead space around it.
            // The small card is a compact top-left chip on the map, so its preview hugs the
            // tile's top-left corner at ~75% of the tile width instead of filling it.
            val density = LocalDensity.current
            val previewNominal = if (size == ThreatCardSize.SMALL) 300.dp else 340.dp
            // The preview card is static sample data, so its height at the fixed nominal width
            // is deterministic; cache the scaled height (non-state, keyed by size+lang) so
            // transient re-measure passes can't make the tile jump.
            val cachedHeight = remember(size, lang) { intArrayOf(0) }
            SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
                val nominalW = with(density) { previewNominal.toPx() }
                val nominalWpx = with(density) { previewNominal.roundToPx() }
                val scale = if (size == ThreatCardSize.SMALL) {
                    constraints.maxWidth * 0.75f / nominalW
                } else {
                    constraints.maxWidth.toFloat() / nominalW
                }
                val cardPlaceable = subcompose("preview-card") {
                    Box(
                        modifier = Modifier
                            .width(previewNominal)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                    ) {
                        ThreatPopupCard(
                            threat = PreviewThreat,
                            lang = lang,
                            proximity = PreviewProximity,
                            pinnedCity = null,
                            threatLevel = 7.0,
                            onDismiss = {},
                            cardSize = size,
                            interactive = false
                        )
                    }
                }[0].measure(
                    constraints.copy(
                        minWidth = nominalWpx,
                        maxWidth = nominalWpx,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity
                    )
                )
                val measuredHeight = (cardPlaceable.height * scale).roundToInt()
                if (constraints.maxWidth != Constraints.Infinity && constraints.maxWidth > 0) {
                    cachedHeight[0] = measuredHeight
                }
                val height = if (cachedHeight[0] > 0) cachedHeight[0] else measuredHeight
                layout(constraints.maxWidth, height) {
                    cardPlaceable.place(0, 0)
                }
            }
        }
    }
}

/** Icon-slot size inside an icon-set tile. */
private val IconTileSlot = 36.dp

/** Icon-style picker: four stacked full-width rows (one per real set — Photos,
 *  Army, Comic, Russian), each showing all seven icons side by side. */
@Composable
internal fun IconSetSelector(
    lang: AppLanguage,
    selected: ThreatIconSet,
    onChange: (ThreatIconSet) -> Unit,
    slot: Dp = IconTileSlot
) {
    val s = Strings.get(lang)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconSetTile(
            set = ThreatIconSet.PHOTO,
            label = s.iconSetPhotoLabel,
            selected = selected == ThreatIconSet.PHOTO,
            onClick = { onChange(ThreatIconSet.PHOTO) },
            slot = slot
        )
        IconSetTile(
            set = ThreatIconSet.ARMY,
            label = s.iconSetArmyLabel,
            selected = selected == ThreatIconSet.ARMY,
            onClick = { onChange(ThreatIconSet.ARMY) },
            slot = slot
        )
        IconSetTile(
            set = ThreatIconSet.COMIC,
            label = s.iconSetComicLabel,
            selected = selected == ThreatIconSet.COMIC,
            onClick = { onChange(ThreatIconSet.COMIC) },
            slot = slot
        )
        IconSetTile(
            set = ThreatIconSet.RUSSIAN,
            label = s.iconSetRussianLabel,
            selected = selected == ThreatIconSet.RUSSIAN,
            onClick = { onChange(ThreatIconSet.RUSSIAN) },
            slot = slot
        )
    }
}

@Composable
internal fun IconSetTile(
    set: ThreatIconSet,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slot: Dp = IconTileSlot
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "iconSetScale"
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconCatalog.photoTypes().forEach { type ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ThreatIcon(
                        type = type,
                        set = set,
                        size = slot,
                        contentDescription = label
                    )
                }
            }
        }
    }
}

/** Subtle one-shot blue border pulse around the row whose one-time explainer just closed. */
@Composable
private fun Modifier.explainerFlash(active: Boolean): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            alpha.snapTo(0f)
            alpha.animateTo(0.45f, tween(180))
            alpha.animateTo(0f, tween(520))
        }
    }
    return if (active) then(
        Modifier.drawWithContent {
            drawContent()
            val sw = 2.dp.toPx()
            drawRoundRect(
                color = UkraineBlue.copy(alpha = alpha.value),
                topLeft = Offset(sw / 2, sw / 2),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = sw)
            )
        }
    ) else this
}

/** A horizontal row of tappable search suggestion chips under the search box. */
@Composable
private fun SearchChipsRow(
    label: String,
    chips: List<SearchChip>,
    lang: AppLanguage,
    onChip: (SearchChip) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { chip ->
                FilterChip(
                    selected = false,
                    onClick = { onChip(chip) },
                    label = { Text(chip.label(lang)) }
                )
            }
        }
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    icon: Painter,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    emoji: String? = null,
    cardColor: Color? = null,
    cardBorder: Color? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val chevronAngle = animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "chevronAngle"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (cardColor != null) CardDefaults.cardColors(containerColor = cardColor) else CardDefaults.cardColors(),
        border = if (cardBorder != null) BorderStroke(1.dp, cardBorder) else null
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressTick(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true),
                        onClick = onToggle
                    )
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isPressed) 0.06f else 0f
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (emoji != null) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!expanded && !subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                trailing?.invoke()
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronAngle.value }
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}

@Composable
private fun WarningTriangle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2f, 0f)
            lineTo(w, h * 0.95f)
            lineTo(0f, h * 0.95f)
            close()
        }
        drawPath(path, color = Color(0xFFF9A825))
        drawLine(
            color = Color(0xFF3A2B00),
            start = Offset(w / 2f, h * 0.38f),
            end = Offset(w / 2f, h * 0.62f),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFF3A2B00),
            radius = 1.4f,
            center = Offset(w / 2f, h * 0.8f)
        )
    }
}

@Composable
internal fun LanguageFlag(
    emoji: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "langFlagScale"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .then(
                if (active) Modifier.background(UkraineBlue.copy(alpha = 0.25f)) else Modifier
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        ) {
            Text(
                emoji,
                fontSize = 32.sp,
                // Inverted: the flag of the language you'd switch to is the colored one.
                modifier = Modifier.alpha(if (active) 0.3f else 1f)
            )
            if (label != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
