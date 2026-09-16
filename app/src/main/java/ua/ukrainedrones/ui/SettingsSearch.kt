package ua.ukrainedrones

import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.City

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
        val Saver = androidx.compose.runtime.saveable.Saver<SettingsCollapseState, BooleanArray>(
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
internal enum class SettingsSection(val index: Int) {
    LOCATION(1), ALERTS(2), NIGHT(3), SHELTERS(4), THREATS(5), SYSTEM(6), FLOURISH(7)
}

/** Standalone action buttons below the section cards, also matched by the search box. */
internal enum class StandaloneSetting { RELAUNCH, GUIDE, UPDATE, EXIT }

/** A suggestion chip shown by the search box: a tappable hint that fills the query with a
 *  keyword that resolves to its setting. */
internal data class SearchChip(
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
internal data class RelatedConcept(
    val words: List<String>,
    val chips: List<SearchChip>
)

/** Normalizes text for search matching: lowercase, drop apostrophes/quotes, dashes → spaces. */
internal fun String.searchNorm(): String = lowercase()
    .replace(Regex("[''´`]"), "")
    .replace(Regex("[-–—]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** True when every query word is a substring of at least one keyword. */
internal fun matchesSearch(queryWords: List<String>, keywords: List<String>): Boolean =
    queryWords.all { qw -> keywords.any { qw in it } }

/** Classic Levenshtein edit distance, for the "did you mean" suggestions. */
internal fun levenshtein(a: String, b: String): Int {
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

internal fun searchChip(labelUa: String, labelEn: String, queryUa: String, queryEn: String) =
    SearchChip(labelUa, labelEn, queryUa.searchNorm(), queryEn.searchNorm())

internal fun chipList(vararg c: SearchChip) = c.toList()

/** The search database: curated direct keywords per target + pooled related concepts. */
internal data class SettingsSearchDb(
    val sectionDirect: Map<SettingsSection, List<String>>,
    val standaloneDirect: Map<StandaloneSetting, List<String>>,
    val related: List<RelatedConcept>
)

/** Builds the settings search database. Direct keywords are curated (both languages), threat-type
 *  terms are auto-derived from the catalog, and related concepts map user intents to chips.
 *  Future languages extend the keyword/concept lists without changing the matching logic. */
internal fun buildSearchDb(pinnedCity: City?): SettingsSearchDb {
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
            "vibration", "vibrate", "volume", "chime", "boot", "reboot", "restart", "monitoring",
            "оповіщення", "сповіщення", "сирена", "звук", "офіційн", "офіційна",
            "офіційні", "вібрація", "вібро", "гучність", "перезавантаження", "моніторинг"
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
            "fill", "region", "regions", "oblast", "oblasts", "alert fill", "region fill",
            "border", "borders", "outline", "boundaries",
            "region", "raion", "district", "large", "big", "city labels",
            "система", "інтерфейс", "дисплей", "мова", "українськ", "англійськ", "іконка", "іконки",
            "картка", "картки", "розмір", "масштаб", "батарея",
            "заливка", "область", "області", "заливка областей", "заливка регіонів",
            "кордон", "кордони", "межа", "межі", "контури",
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
