package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.distanceFlat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Immutable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/** Zoom-dependent label prominence: oblast seats early, big non-seat cities mid-zoom,
 *  everything else only up close. */
enum class CityTier { MAJOR, MEDIUM, MINOR }

/** A place name drawn on the map. Tier controls when the label appears ([CityLabelOverlay]). */
@Immutable
data class City(
    val nameUa: String,
    val lat: Double,
    val lon: Double,
    val tier: CityTier = CityTier.MINOR,
    val pop: Int = 0,
    val nameEn: String = Transliteration.transliterate(nameUa)
) {
    /** Attribution/banner/pin-picker eligibility — always MAJOR-only (see [Cities.nearestCity]). */
    val major: Boolean get() = tier == CityTier.MAJOR

    fun name(lang: AppLanguage): String = if (lang == AppLanguage.UA) nameUa else nameEn
}

/**
 * Curated city list (no hoods/villages) so users get a sense of distance/scale while zooming.
 * Cities are grouped by oblast; each region declares its alert-match stem once. Names are drawn
 * in the current UI language, replacing the (Cyrillic, non-English) labels baked into the
 * basemap tiles. EN names derive from the app's own КМУ №55 transliteration. Minor cities are
 * map-context only — attribution/banner always resolve to majors.
 */
object Cities {

    /** One oblast region: [stem] is matched against the alert oblast name; [cities] its places. */
    private data class Region(val stem: String, val cities: List<City>)

    private val REGIONS: List<Region> = listOf(
        Region("Київськ", listOf(
            City("Київ", 50.4501, 30.5234, CityTier.MAJOR),
            City("Біла Церква", 49.7954, 30.1167, CityTier.MEDIUM),
            City("Бровари", 50.5184, 30.7908),
            City("Бориспіль", 50.3506, 30.9553),
            City("Ірпінь", 50.5217, 30.2506),
            City("Фастів", 50.0767, 29.9184),
            City("Васильків", 50.1836, 30.3213),
            City("Обухів", 50.1300, 30.6180),
            City("Вишгород", 50.5826, 30.4883),
            City("Буча", 50.5434, 30.2207),
            City("Переяслав", 50.0650, 31.4500),
            City("Яготин", 50.2550, 31.7820),
            City("Миронівка", 49.6600, 30.9840),
            City("Сквира", 49.7300, 29.6700),
            City("Тетіїв", 49.3730, 29.6940),
            City("Баришівка", 50.3593, 31.32, pop = 10294), // pop ~10294
            City("Березань", 50.3107, 31.4677, pop = 16202), // pop ~16202
            City("Біличі", 50.4654, 30.3465, pop = 40200), // pop ~40200
            City("Білогородка", 50.3898, 30.2273, pop = 21369), // pop ~21369
            City("Богуслав", 49.5464, 30.8741, pop = 15789), // pop ~15789
            City("Бородянка", 50.6431, 29.9233, pop = 13832), // pop ~13832
            City("Бортничі", 50.3849, 30.6954, pop = 15500), // pop ~15500
            City("Боярка", 50.3191, 30.2973, pop = 34394), // pop ~34394
            City("Вишневе", 50.3883, 30.3681, pop = 42983), // pop ~42983
            City("Гатне", 50.3544, 30.375, pop = 12500), // pop ~12500
            City("Гостомель", 50.5684, 30.2651, pop = 18466), // pop ~18466
            City("Кагарлик", 49.859, 30.8233, pop = 13133), // pop ~13133
            City("Коцюбинське", 50.4884, 30.3296, pop = 17623), // pop ~17623
            City("Петрівське", 50.3821, 30.3228, pop = 16200), // pop ~16200
            City("Саперна Слобідка", 50.4008, 30.5349, pop = 20200), // pop ~20200
            City("Славутич", 51.5225, 30.7181, pop = 24464), // pop ~24464
            City("Софіївська Борщагівка", 50.4093, 30.3591, pop = 25882), // pop ~25882
            City("Тараща", 49.5572, 30.4962, pop = 12952), // pop ~12952
            City("Узин", 49.8248, 30.4249, pop = 11921), // pop ~11921
            City("Українка", 50.1432, 30.7461, pop = 13636), // pop ~13636
            City("Чайки", 50.4303, 30.2838, pop = 12000), // pop ~12000
        )),
        Region("Одеськ", listOf(
            City("Одеса", 46.4825, 30.7233, CityTier.MAJOR),
            City("Чорноморськ", 46.3036, 30.6566),
            City("Південне", 46.6226, 31.1014),
            City("Білгород-Дністровський", 46.1947, 30.3484),
            City("Ізмаїл", 45.3503, 28.8348),
            City("Рені", 45.4571, 28.2867),
            City("Кілія", 45.4552, 29.2637),
            City("Овідіополь", 46.2450, 30.4413),
            City("Біляївка", 46.4829, 30.1985),
            City("Березівка", 47.2044, 30.9093),
            City("Роздільна", 46.8493, 30.0833),
            City("Арциз", 45.9919, 29.4183),
            City("Татарбунари", 45.8527, 29.6144),
            City("Подільськ", 47.7433, 29.5350),
            City("Балта", 47.9364, 29.6226),
            City("Болград", 45.6810, 28.6130),
            City("Ананьїв", 47.7220, 29.9580),
            City("Кодима", 48.1020, 29.1240),
            City("Саврань", 48.1320, 30.0820),
            City("Любашівка", 47.8370, 30.2630),
            City("Великодолинське", 46.3464, 30.579, pop = 13856), // pop ~13856
            City("Лиманка", 46.3856, 30.6774, pop = 13085), // pop ~13085
            City("Черемушки", 46.4325, 30.7115),
        )),
        Region("Львівськ", listOf(
            City("Львів", 49.8397, 24.0297, CityTier.MAJOR),
            City("Дрогобич", 49.3500, 23.5050),
            City("Стрий", 49.2620, 23.8500),
            City("Самбір", 49.5180, 23.1970),
            City("Шептицький", 50.3850, 24.2270),
            City("Борислав", 49.2860, 23.4250),
            City("Трускавець", 49.2760, 23.5060),
            City("Броди", 50.0790, 25.1530),
            City("Золочів", 49.8060, 24.9000),
            City("Сокаль", 50.4860, 24.2790),
            City("Яворів", 49.9380, 23.3840),
            City("Мостиська", 49.7940, 23.1500),
            City("Жовква", 50.0580, 23.9700),
            City("Пустомити", 49.7130, 23.9050),
            City("Вінники", 49.8167, 24.1442, pop = 19037), // pop ~19037
            City("Городок", 49.7848, 23.6481, pop = 15993), // pop ~15993
            City("Жидачів", 49.3832, 24.1392, pop = 10353), // pop ~10353
            City("Зимна Вода", 49.8262, 23.8822, pop = 11365), // pop ~11365
            City("Кам’янка-Бузька", 50.1066, 24.3445, pop = 10397), // pop ~10397
            City("Миколаїв", 49.5255, 23.9779, pop = 14498), // pop ~14498
            City("Новий Розділ", 49.4714, 24.1335, pop = 28304), // pop ~28304
            City("Новояворівськ", 49.9302, 23.5736, pop = 31366), // pop ~31366
            City("Соснівка", 50.2897, 24.2514, pop = 10838), // pop ~10838
            City("Стебник", 49.301, 23.552, pop = 20200), // pop ~20200
        )),
        Region("Дніпропетровськ", listOf(
            City("Дніпро", 48.4647, 35.0462, CityTier.MAJOR),
            City("Кривий Ріг", 47.9105, 33.3918, CityTier.MAJOR),
            City("Кам'янське", 48.5147, 34.6102, CityTier.MEDIUM),
            City("Нікополь", 47.5720, 34.3580, CityTier.MEDIUM),
            City("Павлоград", 48.5170, 35.8730),
            City("Новомосковськ", 48.6330, 35.2200),
            City("Марганець", 47.6430, 34.6150),
            City("Жовті Води", 48.3480, 33.5000),
            City("Покров", 47.6670, 34.0910),
            City("П'ятихатки", 48.4130, 33.7080),
            City("Апостолове", 47.6600, 33.7160),
            City("Верхньодніпровськ", 48.6550, 34.3350),
            City("Синельникове", 48.3180, 35.5190),
            City("Першотравенськ", 48.3460, 36.3990),
            City("Тернівка", 48.5230, 36.0820),
            City("Верхівцеве", 48.4813, 34.24, pop = 10262), // pop ~10262
            City("Вільногірськ", 48.4842, 34.0171, pop = 22079), // pop ~22079
            City("Зеленодольськ", 47.555, 33.659, pop = 12692), // pop ~12692
            City("Інгулець", 47.7301, 33.2519),
            City("Нові Кодаки", 48.4867, 34.9564, pop = 15000), // pop ~15000
            City("Підгороднє", 48.5742, 35.097, pop = 19138), // pop ~19138
            City("Самар", 48.6289, 35.2589, pop = 70550), // pop ~70550
            City("Слобожанське", 48.532, 35.0715, pop = 13556), // pop ~13556
            City("Таромське", 48.4428, 34.7885, pop = 13289), // pop ~13289
        )),
        Region("Харківськ", listOf(
            City("Харків", 49.9935, 36.2304, CityTier.MAJOR),
            City("Чугуїв", 49.8370, 36.9390),
            City("Лозова", 48.8890, 36.3900),
            City("Ізюм", 49.2090, 37.2520),
            City("Куп'янськ", 49.7100, 37.6210),
            City("Златопіль", 49.3860, 36.2140),
            City("Балаклія", 49.4620, 36.8590),
            City("Богодухів", 50.1640, 35.5270),
            City("Вовчанськ", 50.2900, 36.9400),
            City("Зміїв", 49.6950, 36.3610),
            City("Дергачі", 50.1150, 36.1180),
            City("Мерефа", 49.8180, 36.0500),
            City("Берестин", 49.3810, 35.4420),
            City("Люботин", 49.9470, 35.9290),
            City("Валки", 49.837, 35.6139, pop = 10381), // pop ~10381
            City("Високий", 49.8942, 36.1238, pop = 10988), // pop ~10988
            City("Ківшарівка", 49.6303, 37.6775, pop = 19738), // pop ~19738
            City("Нова Водолага", 49.7188, 35.8601, pop = 10455), // pop ~10455
            City("Пісочин", 49.9516, 36.1026, pop = 23509), // pop ~23509
            City("Слобожанське", 49.5905, 36.5217, pop = 15825), // pop ~15825
            City("Солоницівка", 49.9968, 36.0346, pop = 12378), // pop ~12378
        )),
        Region("Запорізьк", listOf(
            City("Запоріжжя", 47.8388, 35.1396, CityTier.MAJOR),
            City("Мелітополь", 46.8380, 35.3600, CityTier.MEDIUM),
            City("Бердянськ", 46.7540, 36.7890),
            City("Енергодар", 47.4980, 34.6560),
            City("Токмак", 47.2550, 35.7130),
            City("Пологи", 47.4820, 36.2540),
            City("Василівка", 47.4420, 35.2750),
            City("Приморськ", 46.7340, 36.3550),
            City("Оріхів", 47.5670, 35.7840),
            City("Гуляйполе", 47.6640, 36.2580),
            City("Вільнянськ", 47.9440, 35.4350),
            City("Кам'янка-Дніпровська", 47.4850, 34.4140),
            City("Михайлівка", 47.2700, 35.2210),
            City("Розівка", 47.3780, 37.0670),
            City("Дніпрорудне", 47.3899, 35.0006, pop = 17736), // pop ~17736
            City("Костянтинівка", 46.8178, 35.4242, pop = 11540), // pop ~11540
            City("Якимівка", 46.7011, 35.1633, pop = 11069), // pop ~11069
        )),
        Region("Вінницьк", listOf(
            City("Вінниця", 49.2331, 28.4682, CityTier.MAJOR),
            City("Жмеринка", 49.0370, 28.1130),
            City("Могилів-Подільський", 48.4470, 27.7980),
            City("Хмільник", 49.5540, 27.9580),
            City("Козятин", 49.7180, 28.8400),
            City("Гайсин", 48.8120, 29.3930),
            City("Бар", 49.0780, 27.6820),
            City("Шаргород", 48.7540, 28.0780),
            City("Липовець", 49.2170, 29.0560),
            City("Іллінці", 49.1030, 29.2080),
            City("Немирів", 48.9680, 28.8440),
            City("Калинівка", 49.4470, 28.5260),
            City("Тульчин", 48.6740, 28.8500),
            City("Ладижин", 48.6850, 29.2400),
            City("Бершадь", 48.3635, 29.5146, pop = 12205), // pop ~12205
            City("Гнівань", 49.0939, 28.3378, pop = 12191), // pop ~12191
            City("Ямпіль", 48.2406, 28.2814, pop = 10957), // pop ~10957
        )),
        Region("Миколаївськ", listOf(
            City("Миколаїв", 46.9750, 31.9946, CityTier.MAJOR),
            City("Вознесенськ", 47.5653, 31.3311),
            City("Первомайськ", 48.0446, 30.8506),
            City("Южноукраїнськ", 47.8150, 31.1780),
            City("Очаків", 46.6160, 31.5400),
            City("Новий Буг", 47.6940, 32.5160),
            City("Баштанка", 47.4040, 32.4400),
            City("Снігурівка", 47.0710, 32.7960),
            City("Ольшанське", 47.1990, 31.7970),
            City("Веселинове", 47.3570, 31.2350),
            City("Казанка", 47.8360, 32.8230),
            City("Нова Одеса", 47.3127, 31.7697, pop = 13547), // pop ~13547
        )),
        Region("Херсонськ", listOf(
            City("Херсон", 46.6354, 32.6169, CityTier.MAJOR),
            City("Нова Каховка", 46.7560, 33.3850),
            City("Каховка", 46.7980, 33.4760),
            City("Генічеськ", 46.1750, 34.8000),
            City("Скадовськ", 46.1180, 32.9110),
            City("Олешки", 46.6290, 32.7230),
            City("Гола Пристань", 46.5250, 32.5260),
            City("Берислав", 46.8400, 33.4270),
            City("Таврійськ", 46.7500, 33.4380),
            City("Чаплинка", 46.3450, 33.5320),
            City("Велика Лепетиха", 47.1740, 33.9370),
            City("Нижні Сірогози", 47.0250, 34.3790),
            City("Антонівка", 46.6767, 32.7306, pop = 12777), // pop ~12777
            City("Новоолексіївка", 46.23, 34.6458, pop = 10154), // pop ~10154
            City("Новотроїцьке", 46.3509, 34.3324, pop = 10647), // pop ~10647
        )),
        Region("Кіровоградськ", listOf(
            City("Кропивницький", 48.5079, 32.2603, CityTier.MAJOR),
            City("Олександрія", 48.6690, 33.1150),
            City("Світловодськ", 49.0490, 33.2510),
            City("Знам'янка", 48.7140, 32.6740),
            City("Долинська", 48.1130, 32.7500),
            City("Новоукраїнка", 48.3150, 31.5250),
            City("Бобринець", 48.0590, 32.1580),
            City("Гайворон", 48.3390, 29.8480),
            City("Новомиргород", 48.7810, 31.6430),
            City("Помічна", 48.2410, 31.4070),
            City("Мала Виска", 48.6460, 31.6340),
            City("Устинівка", 48.1540, 32.5350)
        )),
        Region("Полтавськ", listOf(
            City("Полтава", 49.5883, 34.5514, CityTier.MAJOR),
            City("Кременчук", 49.0680, 33.4230, CityTier.MEDIUM),
            City("Горішні Плавні", 49.0110, 33.6500),
            City("Лубни", 50.0190, 32.9970),
            City("Миргород", 49.9650, 33.6110),
            City("Гадяч", 50.3650, 33.9910),
            City("Пирятин", 50.2420, 32.5110),
            City("Карлівка", 49.4570, 35.1300),
            City("Хорол", 49.7830, 33.2780),
            City("Зіньків", 50.2090, 34.3590),
            City("Лохвиця", 50.3590, 33.2650),
            City("Решетилівка", 49.5590, 34.0740),
            City("Кобеляки", 49.1410, 34.2050),
            City("Чутове", 49.7170, 35.1610),
            City("Гребінка", 50.1202, 32.4297, pop = 10541), // pop ~10541
            City("Котельва", 50.0685, 34.747, pop = 12122), // pop ~12122
        )),
        Region("Черкаськ", listOf(
            City("Черкаси", 49.4444, 32.0598, CityTier.MAJOR),
            City("Умань", 48.7484, 30.2211, CityTier.MAJOR),
            City("Сміла", 49.2170, 31.8710),
            City("Золотоноша", 49.6680, 32.0400),
            City("Канів", 49.7510, 31.4700),
            City("Корсунь-Шевченківський", 49.4170, 31.2600),
            City("Звенигородка", 49.0780, 30.9690),
            City("Шпола", 49.0060, 31.3910),
            City("Ватутіне", 49.0090, 31.0720),
            City("Тальне", 48.8860, 30.6930),
            City("Христинівка", 48.8200, 29.9670),
            City("Монастирище", 48.9940, 29.7950),
            City("Чигирин", 49.0780, 32.6610),
            City("Жашків", 49.2470, 30.1110),
            City("Городище", 49.2880, 31.4510),
            City("Кам’янка", 49.0396, 32.1017, pop = 11501), // pop ~11501
        )),
        Region("Хмельницьк", listOf(
            City("Хмельницький", 49.4220, 26.9871, CityTier.MAJOR),
            City("Кам'янець-Подільський", 48.6840, 26.5910),
            City("Шепетівка", 50.1850, 27.0640),
            City("Нетішин", 50.3400, 26.6430),
            City("Славута", 50.3020, 26.8650),
            City("Старокостянтинів", 49.7570, 27.2210),
            City("Городок", 49.1640, 26.5730),
            City("Волочиськ", 49.5400, 26.1860),
            City("Деражня", 49.2680, 27.4330),
            City("Дунаївці", 48.8880, 26.8550),
            City("Полонне", 50.1200, 27.5070),
            City("Красилів", 49.6520, 26.9720),
            City("Ізяслав", 50.1180, 26.8200),
            City("Чемерівці", 49.0030, 26.3500),
            City("Летичів", 49.3801, 27.6189, pop = 10335), // pop ~10335
        )),
        Region("Житомирськ", listOf(
            City("Житомир", 50.2546, 28.6587, CityTier.MAJOR),
            City("Бердичів", 49.8930, 28.6020),
            City("Коростень", 50.9520, 28.6370),
            City("Звягель", 50.5950, 27.6170),
            City("Малин", 50.7720, 29.2700),
            City("Овруч", 51.3260, 28.8100),
            City("Коростишів", 50.3180, 29.0590),
            City("Радомишль", 50.4970, 29.2220),
            City("Брусилів", 50.2840, 29.5260),
            City("Чуднів", 50.0540, 28.1130),
            City("Андрушівка", 50.0180, 29.0190),
            City("Попільня", 50.0000, 29.4600),
            City("Олевськ", 51.2270, 27.6480),
            City("Хорошів", 50.5970, 28.4450),
            City("Баранівка", 50.2969, 27.6622, pop = 11161), // pop ~11161
        )),
        Region("Рівненськ", listOf(
            City("Рівне", 50.6199, 26.2516, CityTier.MAJOR),
            City("Вараш", 51.3400, 25.8500),
            City("Дубно", 50.4170, 25.7500),
            City("Острог", 50.3300, 26.5150),
            City("Сарни", 51.3390, 26.6000),
            City("Костопіль", 50.8790, 26.4420),
            City("Здолбунів", 50.5230, 26.2430),
            City("Березне", 50.9990, 26.7440),
            City("Радивилів", 50.1320, 25.2560),
            City("Володимирець", 51.4200, 26.1450),
            City("Корець", 50.6150, 27.1610),
            City("Дубровиця", 51.5720, 26.5630),
            City("Млинів", 50.5170, 25.6080),
            City("Рокитне", 51.2780, 27.2200)
        )),
        Region("Чернівецьк", listOf(
            City("Чернівці", 48.2917, 25.9352, CityTier.MAJOR),
            City("Сторожинець", 48.1590, 25.7150),
            City("Кіцмань", 48.4400, 25.7610),
            City("Вижниця", 48.2490, 25.1910),
            City("Новоселиця", 48.2240, 26.2710),
            City("Хотин", 48.5080, 26.4900),
            City("Сокиряни", 48.4480, 27.4170),
            City("Герца", 48.1490, 26.2610),
            City("Заставна", 48.5270, 25.8470),
            City("Глибока", 48.0870, 25.9330),
            City("Путила", 47.9860, 25.0930),
            City("Красноїльськ", 48.0186, 25.56, pop = 10428), // pop ~10428
            City("Новодністровськ", 48.5832, 27.4366, pop = 10590), // pop ~10590
        )),
        Region("Івано-Франківськ", listOf(
            City("Івано-Франківськ", 48.9226, 24.7111, CityTier.MAJOR),
            City("Калуш", 49.0430, 24.3670),
            City("Коломия", 48.5290, 25.0360),
            City("Надвірна", 48.6340, 24.5700),
            City("Долина", 48.9740, 24.0020),
            City("Бурштин", 49.2570, 24.6350),
            City("Косів", 48.3150, 25.0950),
            City("Снятин", 48.4470, 25.5700),
            City("Городенка", 48.6700, 25.4990),
            City("Рогатин", 49.4110, 24.6080),
            City("Тисмениця", 48.9030, 24.8490),
            City("Болехів", 49.0670, 23.8640),
            City("Галич", 49.1190, 24.7260),
            City("Тлумач", 48.8640, 25.0010),
            City("Перегінське", 48.8112, 24.192, pop = 12681), // pop ~12681
        )),
        Region("Тернопільськ", listOf(
            City("Тернопіль", 49.5535, 25.5948, CityTier.MAJOR),
            City("Чортків", 49.0170, 25.7950),
            City("Кременець", 50.1070, 25.7240),
            City("Бережани", 49.4460, 24.9350),
            City("Збараж", 49.6660, 25.7690),
            City("Зборів", 49.6600, 25.1500),
            City("Теребовля", 49.3040, 25.7020),
            City("Бучач", 49.0640, 25.3840),
            City("Гусятин", 49.0710, 26.2050),
            City("Підволочиськ", 49.5310, 26.1370),
            City("Козова", 49.5150, 25.1590),
            City("Шумськ", 50.1160, 26.1110),
            City("Ланівці", 49.8650, 26.0800),
            City("Монастириська", 49.0890, 25.1680),
            City("Борщів", 48.8032, 26.0317, pop = 10632), // pop ~10632
        )),
        Region("Сумськ", listOf(
            City("Суми", 50.9077, 34.7981, CityTier.MAJOR),
            City("Шостка", 51.8630, 33.4700),
            City("Конотоп", 51.2390, 33.2030),
            City("Охтирка", 50.3100, 34.8970),
            City("Ромни", 50.7500, 33.4870),
            City("Глухів", 51.6750, 33.9080),
            City("Лебедин", 50.5830, 34.4750),
            City("Кролевець", 51.5540, 33.3840),
            City("Путивль", 51.3310, 33.8700),
            City("Тростянець", 50.4810, 34.9640),
            City("Білопілля", 51.1470, 34.3070),
            City("Буринь", 51.1940, 33.8210),
            City("Середина-Буда", 52.1900, 34.0260)
        )),
        Region("Чернігівськ", listOf(
            City("Чернігів", 51.4982, 31.2893, CityTier.MAJOR),
            City("Ніжин", 51.0470, 31.8780),
            City("Прилуки", 50.5950, 32.3880),
            City("Бахмач", 51.1790, 32.8260),
            City("Новгород-Сіверський", 52.0040, 33.2620),
            City("Корюківка", 51.7780, 32.2640),
            City("Мена", 51.5240, 32.2150),
            City("Сновськ", 51.8200, 31.9550),
            City("Борзна", 51.2510, 32.4280),
            City("Ічня", 50.8510, 32.3960),
            City("Городня", 51.8920, 31.5960),
            City("Семенівка", 52.1770, 32.5800),
            City("Козелець", 50.9160, 31.1130),
            City("Бобровиця", 50.7456, 31.382, pop = 10541), // pop ~10541
            City("Масани", 51.5332, 31.2313, pop = 30000), // pop ~30000
            City("Носівка", 50.938, 31.5803, pop = 12908), // pop ~12908
        )),
        Region("Донецьк", listOf(
            City("Донецьк", 48.0159, 37.8029, CityTier.MAJOR),
            City("Маріуполь", 47.0971, 37.5434, CityTier.MEDIUM),
            City("Горлівка", 48.3380, 38.0860, CityTier.MEDIUM),
            City("Краматорськ", 48.7310, 37.5560, CityTier.MEDIUM),
            City("Слов'янськ", 48.8540, 37.6060, CityTier.MEDIUM),
            City("Бахмут", 48.5950, 38.0000),
            City("Покровськ", 48.2790, 37.1820),
            City("Костянтинівка", 48.5310, 37.7170),
            City("Дружківка", 48.6100, 37.5520),
            City("Харцизьк", 48.0430, 38.1450),
            City("Торецьк", 48.3900, 37.8470),
            City("Авдіївка", 48.1400, 37.7450),
            City("Волноваха", 47.5980, 37.4990),
            City("Єнакієве", 48.2310, 38.2030),
            City("Амвросіївка", 47.7906, 38.4785, pop = 17998), // pop ~17998
            City("Білозерське", 48.5337, 37.0627, pop = 14634), // pop ~14634
            City("Вугледар", 47.7797, 37.25, pop = 14144), // pop ~14144
            City("Гірник", 48.0573, 37.3716, pop = 10357), // pop ~10357
            City("Дебальцеве", 48.3359, 38.4026, pop = 24209), // pop ~24209
            City("Добропілля", 48.4667, 37.0857, pop = 28170), // pop ~28170
            City("Докучаєвськ", 47.7522, 37.6776, pop = 22835), // pop ~22835
            City("Жданівка", 48.1532, 38.2569, pop = 11867), // pop ~11867
            City("Зугрес", 48.0143, 38.266, pop = 17871), // pop ~17871
            City("Іловайськ", 47.925, 38.2024, pop = 15447), // pop ~15447
            City("Кіровське", 48.1497, 38.3594, pop = 27370), // pop ~27370
            City("Комсомольське", 47.6639, 38.0775, pop = 11422), // pop ~11422
            City("Красногорівка", 48.0046, 37.5069, pop = 14666), // pop ~14666
            City("Курахове", 47.9847, 37.2807, pop = 18220), // pop ~18220
            City("Лиман", 48.9901, 37.8084, pop = 20066), // pop ~20066
            City("Макіївка", 48.0478, 37.9258, pop = 338968), // pop ~338968
            City("Миколаїв", 48.8619, 37.7684, pop = 14210), // pop ~14210
            City("Мирноград", 48.3099, 37.2651, pop = 46098), // pop ~46098
            City("Моспине", 47.8901, 38.0638, pop = 10471), // pop ~10471
            City("Нижня Кринка", 48.1138, 38.1596, pop = 14253), // pop ~14253
            City("Новоазовськ", 47.1139, 38.086, pop = 11051), // pop ~11051
            City("Новогродівка", 48.2005, 37.3388, pop = 14037), // pop ~14037
            City("Пелагіївка", 48.1075, 38.6134, pop = 17877), // pop ~17877
            City("Північне", 48.3978, 37.9094, pop = 11747), // pop ~11747
            City("Сартана", 47.1752, 37.6918, pop = 10070), // pop ~10070
            City("Світлодарськ", 48.4337, 38.2233, pop = 11127), // pop ~11127
            City("Селидове", 48.1465, 37.3022, pop = 21521), // pop ~21521
            City("Сіверськ", 48.8648, 38.0968, pop = 10875), // pop ~10875
            City("Сніжне", 48.0233, 38.762, pop = 55587), // pop ~55587
            City("Соледар", 48.6921, 38.071, pop = 10490), // pop ~10490
            City("Софіївка", 48.2638, 38.1593, pop = 11458), // pop ~11458
            City("Українськ", 48.0978, 37.3653, pop = 10655), // pop ~10655
            City("Ханжонківський район", 48.0951, 38.042),
            City("Часів Яр", 48.5877, 37.8341, pop = 12250), // pop ~12250
            City("Часткове", 48.0388, 38.5969),
            City("Шахтарськ", 48.0566, 38.4383, pop = 71700), // pop ~71700
            City("Юнокомунарівськ", 48.2214, 38.2836, pop = 13495), // pop ~13495
            City("Ясинувата", 48.1268, 37.8592, pop = 37600), // pop ~37600
        )),
        Region("Луганськ", listOf(
            City("Луганськ", 48.5740, 39.3078, CityTier.MAJOR),
            City("Алчевськ", 48.4690, 38.8000, CityTier.MEDIUM),
            City("Сєвєродонецьк", 48.9480, 38.4870, CityTier.MEDIUM),
            City("Лисичанськ", 48.9040, 38.4320, CityTier.MEDIUM),
            City("Кадіївка", 48.5690, 38.6530),
            City("Рубіжне", 49.0160, 38.3790),
            City("Сорокине", 48.2950, 39.7500),
            City("Довжанськ", 48.0890, 39.6520),
            City("Попасна", 48.6350, 38.3730),
            City("Брянка", 48.5130, 38.6530),
            City("Ровеньки", 48.0720, 39.3470),
            City("Кремінна", 49.0490, 38.2180),
            City("Сватове", 49.4100, 38.1590),
            City("Старобільськ", 49.2730, 38.9140),
            City("Sokolohirs'k", 48.6279, 38.5239, pop = 36091), // pop ~36091
            City("Антрацит", 48.1164, 39.0886, pop = 52150), // pop ~52150
            City("Боково-Хрустальне", 48.1512, 38.7804, pop = 11421), // pop ~11421
            City("Вознесенівка", 48.078, 39.787, pop = 15218), // pop ~15218
            City("Золоте", 48.6952, 38.5151, pop = 13007), // pop ~13007
            City("Кіровськ", 48.6375, 38.6428, pop = 26654), // pop ~26654
            City("Красний Луч", 48.1424, 38.9237, pop = 79533), // pop ~79533
            City("Лутугине", 48.4043, 39.213, pop = 17061), // pop ~17061
            City("Отаманівка", 48.3459, 39.6523, pop = 22449), // pop ~22449
            City("Перевальськ", 48.4392, 38.8284, pop = 24817), // pop ~24817
            City("Петрово-Красносілля", 48.2966, 38.8789, pop = 12642), // pop ~12642
            City("Станично-Луганське", 48.6625, 39.4836, pop = 12258), // pop ~12258
            City("Суходільськ", 48.3508, 39.7257, pop = 20390), // pop ~20390
            City("Щастя", 48.7378, 39.2305, pop = 11411), // pop ~11411
            City("Ювілейне", 48.5559, 39.1828, pop = 16948), // pop ~16948
        )),
        Region("Закарпатськ", listOf(
            City("Ужгород", 48.6208, 22.2879, CityTier.MAJOR),
            City("Мукачево", 48.4410, 22.7130),
            City("Хуст", 48.1800, 23.2930),
            City("Берегове", 48.2050, 22.6400),
            City("Виноградів", 48.1410, 23.0330),
            City("Свалява", 48.5470, 22.9920),
            City("Рахів", 48.0500, 24.2000),
            City("Тячів", 48.0110, 23.5710),
            City("Іршава", 48.3160, 23.0370),
            City("Міжгір'я", 48.5280, 23.5070),
            City("Воловець", 48.7100, 23.1810),
            City("Великий Березний", 48.8940, 22.4600),
            City("Чоп", 48.4320, 22.2060),
            City("Перечин", 48.7340, 22.4740),
            City("Королево", 48.1573, 23.1377, pop = 10385), // pop ~10385
        )),
        Region("Волинськ", listOf(
            City("Луцьк", 50.7472, 25.3254, CityTier.MAJOR),
            City("Ковель", 51.2150, 24.7080),
            City("Володимир", 50.8480, 24.3230),
            City("Нововолинськ", 50.7330, 24.1670),
            City("Рожище", 50.9140, 25.2700),
            City("Ківерці", 50.8330, 25.4590),
            City("Любомль", 51.2240, 24.0360),
            City("Камінь-Каширський", 51.6210, 24.9580),
            City("Горохів", 50.4980, 24.7630),
            City("Маневичі", 51.2940, 25.5330),
            City("Любешів", 51.6220, 25.4970),
            City("Шацьк", 51.5010, 23.9290),
            City("Ратне", 51.6610, 24.5290),
            City("Стара Вижівка", 51.4380, 24.4280)
        )),
        Region("Крим", listOf(
            City("Сімферополь", 44.9521, 34.1024, CityTier.MEDIUM),
            City("Керч", 45.3530, 36.4740, CityTier.MEDIUM),
            City("Євпаторія", 45.1930, 33.3660),
            City("Ялта", 44.5020, 34.1660),
            City("Феодосія", 45.0310, 35.3830),
            City("Джанкой", 45.7110, 34.3880),
            City("Алушта", 44.6760, 34.4100),
            City("Бахчисарай", 44.7520, 33.8600),
            City("Саки", 45.1340, 33.6000),
            City("Красноперекопськ", 45.9610, 33.7940),
            City("Армянськ", 46.1070, 33.6920),
            City("Щолкіне", 45.4260, 35.8250),
            City("Білогірськ", 45.0568, 34.6039, pop = 16428), // pop ~16428
            City("Буюк-Онлар", 45.2887, 34.1352, pop = 10244), // pop ~10244
            City("Гаспра", 44.4363, 34.1125, pop = 10310), // pop ~10310
            City("Гвардійське", 45.1169, 34.0219, pop = 12589), // pop ~12589
            City("Ічкі", 45.3427, 34.9246, pop = 10410), // pop ~10410
            City("Курман", 45.5027, 34.3013, pop = 11134), // pop ~11134
            City("Приморський", 45.112, 35.4786, pop = 12560), // pop ~12560
            City("Судак", 44.8492, 34.9747, pop = 16597), // pop ~16597
            City("Чорноморське", 45.5066, 32.6978, pop = 11039), // pop ~11039
        )),
        Region("Севастополь", listOf(
            City("Севастополь", 44.6166, 33.5254),
            City("Балаклава", 44.5112, 33.5994, pop = 18649), // pop ~18649
            City("Інкерман", 44.6139, 33.6098, pop = 10204), // pop ~10204
        ))
    )

    /** Population from which a non-seat place is labeled from mid-zoom ([CityTier.MEDIUM]). */
    private const val MEDIUM_POP = 50_000

    /** All cities, flat, in the order defined above; big non-seats auto-promoted to MEDIUM. */
    val ALL: List<City> = REGIONS.flatMap { it.cities }
        .map { c ->
            if (c.tier == CityTier.MINOR && c.pop >= MEDIUM_POP) c.copy(tier = CityTier.MEDIUM) else c
        }

    /** Ukrainian name → representative city (highest-population holder of that name —
     *  Ukraine has a few same-named towns in different oblasts). Used when translating
     *  NEPTUN's course text and resolving a course-message place to coordinates. */
    private val representativeByName: Map<String, City> =
        ALL.groupBy { it.nameUa }.mapValues { (_, v) -> v.maxByOrNull { it.pop }!! }

    /** Ukrainian → English place-name lookup, used when translating NEPTUN's course text. */
    val uaToEn: Map<String, String> = representativeByName.mapValues { it.value.nameEn }

    /** Ukrainian name → city lookup, used to resolve a course-message place to its coordinates. */
    val byUa: Map<String, City> = representativeByName

    /** City (by Ukrainian name) → its oblast name stem, used to highlight a city label in red
     *  while an official air-raid alert is active for that oblast. Matched via `contains`
     *  against the alert's oblast/name (e.g. stem "Харківськ" hits "Харківська область").
     *  Same-named towns resolve to the oblast of the largest one. */
    val cityOblast: Map<String, String> =
        REGIONS.flatMap { r -> r.cities.map { Triple(it.nameUa, r.stem, it.pop) } }
            .groupBy({ it.first }, { it })
            .mapValues { (_, v) -> v.maxByOrNull { it.third }!!.second }

    /** Display default when there is no GPS fix and no pinned city: the camera already
     *  opens on Odesa, so the header/attribution names it too instead of showing nothing. */
    val ODESA: City = ALL.first { it.nameUa == "Одеса" }

    /**
     * Nearest **major** listed city within [radiusKm] of a GPS position, used to attribute a
     * follow-me location to an oblast for official-alert matching without depending on a
     * geocoder. Minor cities are map-context only and never drive attribution/banner.
     */
    fun nearestCity(lat: Double, lon: Double, radiusKm: Double = 70.0): City? {
        var best: City? = null
        var bestM = radiusKm * 1000.0
        for (c in ALL) {
            if (!c.major) continue
            val d = distanceFlat(lat, lon, c.lat, c.lon)
            if (d < bestM) {
                bestM = d
                best = c
            }
        }
        return best
    }
}

/** Oblast attribution + banner city for the focus point: the pinned city, else nearest to GPS. */
data class FocusAttribution(
    val token: String?,
    val bannerCityUa: String,
    val bannerCityEn: String
)

fun focusAttribution(followMe: Boolean, userLocation: LatLng?, pinned: City?): FocusAttribution {
    if (!followMe && pinned != null) {
        return FocusAttribution(
            token = Cities.cityOblast[pinned.nameUa],
            bannerCityUa = pinned.nameUa,
            bannerCityEn = pinned.nameEn
        )
    }
    val gps = userLocation?.let { Cities.nearestCity(it.lat, it.lon) }
    return if (gps != null) {
        FocusAttribution(
            token = Cities.cityOblast[gps.nameUa],
            bannerCityUa = gps.nameUa,
            bannerCityEn = gps.nameEn
        )
    } else {
        // No usable fix and no pinned city: resolve to Odesa (the app's display default —
        // the camera already opens there), so header/banner/widget agree with the map.
        FocusAttribution(
            token = Cities.cityOblast[Cities.ODESA.nameUa],
            bannerCityUa = Cities.ODESA.nameUa,
            bannerCityEn = Cities.ODESA.nameEn
        )
    }
}

/** Draws city names in the current language, sized to zoom level. MAJOR labels always show;
 *  MEDIUM/MINOR respect the Settings toggles ([showMediumCities] / [showSmallCities], both on
 *  by default). Cities in [redCityNames] (by Ukrainian name) are drawn red — the set already
 *  respects the official-alert scope (whole oblast by default, city-level when the City scope
 *  is on). */
class CityLabelOverlay(
    context: Context,
    private val lang: AppLanguage,
    private val redCityNames: Set<String> = emptySet(),
    private val showMediumCities: Boolean = true,
    private val showSmallCities: Boolean = true,
    private val forceShowAllProvider: () -> Boolean = { false }
) : Overlay() {

    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply {
        isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        textAlign = Paint.Align.CENTER
    }
    private val reuse = android.graphics.Point()

    private fun name(c: City) = if (lang == AppLanguage.UA) c.nameUa else c.nameEn

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val zoom = mapView.zoomLevelDouble.coerceAtLeast(0.0)
        val forceAll = forceShowAllProvider()
        for (c in Cities.ALL) {
            val minZoom = when (c.tier) {
                CityTier.MAJOR -> 4.0
                CityTier.MEDIUM -> if (forceAll || showMediumCities) 6.5 else Double.MAX_VALUE
                CityTier.MINOR -> if (forceAll || showSmallCities) 10.0 else Double.MAX_VALUE
            }
            if (zoom < minZoom) continue
            mapView.projection.toPixels(GeoPoint(c.lat, c.lon), reuse)
            if (reuse.x < -240 || reuse.x > canvas.width + 240 ||
                reuse.y < -60 || reuse.y > canvas.height + 60
            ) continue
            paint.textSize = (when (c.tier) {
                CityTier.MAJOR -> (10.5 + (zoom - 4.0) * 1.2).coerceIn(10.5, 17.0)
                CityTier.MEDIUM -> (9.5 + (zoom - 6.5) * 0.9).coerceIn(9.5, 15.0)
                CityTier.MINOR -> (8.5 + (zoom - 10.0) * 0.7).coerceIn(8.5, 13.0)
            }).toFloat() * density
            paint.color = if (c.nameUa in redCityNames) {
                Color.argb(255, 211, 47, 47)
            } else {
                Color.argb(230, 235, 235, 235)
            }
            canvas.drawText(name(c), reuse.x.toFloat(), reuse.y.toFloat() - 6f * density, paint)
        }
    }
}