# Changelog

## [Unreleased]

- Internal: migrated all UI/service/widget consumers onto the engine's `NormalizedThreat`; deleted the `Threat` display DTO and `Compat.kt` aliases / Внутрішнє: міграцію всіх UI/service/widget споживачів на `NormalizedThreat` рушія; видалено DTO `Threat` та аліаси `Compat.kt`
- Service: fix startup crash by aligning startForeground foregroundServiceType with manifest declaration / Сервіс: виправлено збій запуску узгодженням foregroundServiceType у startForeground з оголошенням у маніфесті
- Connection: remove legacy REST polling fallback completely; WebSocket channel is now the sole data source / З'єднання: повне видалення застарілого опитування REST; потік WebSocket тепер є єдиним джерелом даних
- Alerts: fix official-alert focus change — switching focus between alerting oblasts now properly drops the previous region and announces the new region / Сповіщення: виправлено зміну фокусу офіційних тривог — перемикання між областями з тривогою тепер скидає попередню область та оголошує нову
- Connection: track threat data freshness independently from socket liveness — suppress stale zone alerts if threat frames stop arriving / З'єднання: відстеження свіжості даних загроз окремо від стану сокету — приглушення застарілих зонних тривог, якщо кадри загроз перестають надходити
- Location: validate location freshness against timeout before evaluating focus and zones / Локація: перевірка свіжості геопозиції за тайм-аутом перед оцінкою фокусу та зон
- Settings: show warning banner when system notifications are disabled / Налаштування: показ попередження, коли сповіщення вимкнено в системі

- Map: fix threats not moving/facing wrong direction — marker update loop now reads fresh threat data on each tick / Мапа: виправлено загрози, що не рухаються/дивляться в хибному напрямку — цикл оновлення маркерів тепер зчитує свіжі дані загроз кожен тик
- Map: fix city labels not updating when language is changed / Мапа: виправлено міські мітки, що не оновлюються при зміні мови
- Map: fix pinned city icon overlapping city name text — pin moved above the label / Мапа: виправлено іконку закріпленого міста, що перекриває текст назви — шпильку переміщено вище мітки
- Map: fix double-painting of threats — stale state in marker loop was causing duplicate renders / Мапа: виправлено подвійне малювання загроз — застарілий стан в циклі маркерів спричиняв дублювання
- Shoot-down: zoom out to level 11 during strike for better projectile visibility / Збиття: віддалення до рівня 11 під час удару для кращої видимості снаряду
- Shoot-down: show hidden city labels during death animation for geographic context / Збиття: показ прихованих міських міток під час анімації знищення для географічного контексту
- Shoot-down: restore original zoom level after returning home from strike / Збиття: відновлення початкового рівня масштабу після повернення додому з удару

- Parsing: clamp future NEPTUN timestamps (updatedAt, confirmedAt, trail) to now instead of rejecting — prevents threats with clock-skewed timestamps from becoming immortal on the map / Парсинг: обмеження майбутніх часових міток NEPTUN (updatedAt, confirmedAt, trail) до поточного часу замість відхилення — запобігає вічній загрозі на мапі через годинникова похибка

- Map: areaOnly threats (oblast-level, no precise point) now render on the map with an amber dot instead of being hidden; card shows amber "Area-level" badge / Мапа: загрози areaOnly (на рівні області, без точної точки) тепер відображаються на мапі з бурим замість приховування; картка показує бейдж "Лише область"
- Map: footer strip tap now opens the threat popup card (was pan-only) / Мапа: дотик на панелі загроз тепер відкриває картку загрози (було лише переміщення)
- Prediction: stale ghost cap reduced from 30min to 15min / Прогноз: ліміт привидів зменшено з 30хв до 15хв
- Service: renamed CENTRE_ALERT_GRACE_MS to ALL_CLEAR_GRACE_MS for clarity / Сервіс: перейменовано CENTRE_ALERT_GRACE_MS на ALL_CLEAR_GRACE_MS
- Logs: SDK change entries now show "View manifest →" link to inspect what changed on Neptun's side / Логи: запис зміни SDK тепер показує посилання "Переглянути маніфест →"
- API: unknown threat types logged with toast + system entry; unknown types detected on every REST/WS frame / API: невідомі типи загроз логуються з тостом + записом системи; невідомі типи виявляються в кожному кадрі REST/WS
- API: manifest check runs on service start (not just daily 16:20); SDK changes sent to developer Telegram bot / API: перевірка маніфесту при запуску сервісу (не лише щодня о 16:20); зміни SDK надсилаються в Telegram-бота

- Build: target SDK 35 (Android 15) for Play Store compliance; guard NeutralizedDismissReceiver against background start restriction / Збірка: target SDK 35 (Android 15) для відповідності Play Store; захист NeutralizedDismissReceiver від обмеження запуску з фону
- Connection: add 10s connectTimeout and 30s readTimeout to OkHttp client (was infinite) / З'єднання: додано connectTimeout 10с та readTimeout 30с для OkHttp (було без ліміту)
- Connection: close WebSocket on onFailure to prevent socket leak / З'єднання: закриття WebSocket при onFailure для запобігання витоку сокетів
- Connection: cap backoff grace reset — after 60s stable connection, reconnectAttempt floors at 2 (prevents rapid-fire reconnect hammering on flaky networks) / З'єднання: обмеження скидання — після 60с стабільного з'єднання reconnectAttempt не нижче 2 (запобігає швидкому перепідключенню на нестійких мережах)
- Connection: move activeWebSocket assignment into onOpen to eliminate window referencing unopened socket / З'єднання: перенесення activeWebSocket в onOpen для уникнення посилання на не відкритий сокет
- Connection: move JSON parsing outside threatsMutex lock in REST payload (reduces lock hold time) / З'єднання: парсинг JSON поза м'ютексом загроз (зменшує час блокування)
- Connection: per-entry exception handling in snapshot/upsert frames (one malformed threat no longer drops entire update) / З'єднання: обробка виключень для кожного запису в кадрах snapshot/upsert (один неправильний threat більше не скасовує все оновлення)
- Connection: log malformed WebSocket frames at WARN level (was silently swallowed) / З'єднання: логування неправильних кадрів WebSocket на рівні WARN (мовчазно поглиналося)
- Connection: force-reset restInFlight after 15s timeout in watchdog / З'єднання: примусовий скидання restInFlight через 15с в watchdog
- Connection: restrict testHarness to internal visibility / З'єднання: обмеження testHarness до internal видимості
- Service: move runBlocking off main thread with Dispatchers.IO in createChannels (prevents ANR) / Сервіс: перенесення runBlocking на Dispatchers.IO в createChannels (запобігання ANR)
- Service: @Volatile on wasConnected to prevent stale cross-thread reads / Сервіс: @Volatile для wasConnected для запобігання застарілим зчитуванням між потоками
- Location: AtomicBoolean guard in forceRefresh to prevent double onComplete callback / Локація: AtomicBoolean guard в forceRefresh для запобігання подвійного виклику onComplete
- Zones: explicit AVIATION reach cap at 9999km (was 1500km via else branch) / Зони: явний ліміт AVIATION 9999км (було 1500км через else)
- Prediction: clarify estimateWithSource speed extraction pattern / Прогноз: покращення читабельності estimateWithSource
- Tests: rewrite AlertServiceLogicTest to test actual pure domain functions (reachKm, zoneTier, etaMinutes, distanceMeters, backoff, isExpired, isStale) / Тести: переписано AlertServiceLogicTest для тестування чистих доменних функцій
- Tests: fix pre-existing compilation errors in ThreatEvaluatorTest (lng→lon, Double→Int), NeptunConnectionClientTest (nonexistent APIs), PredictionTest (missing param), ApiMonitorTest (type mismatch) / Тести: виправлено попередні помилки компіляції в тестах
- Tests: delete duplicate AlertServiceLogicTest and NeptunConnectionClientTest; fix 6 assertion bugs (isStale boundary, speedTracker clear fallback, measuredHeading bearing, zoneThreats prediction, computeProximity distance, icon exhaust coords) — 249/249 green / Тести: видалено дублікати AlertServiceLogicTest та NeptunConnectionClientTest; виправлено 6 помилкових assert-ів — 249/249 зелених

- Background: add partial WakeLock to prevent CPU deep sleep from freezing WebSocket and milestone notifications when screen is off / Фон: додано частковий WakeLock для запобігання замерзанню WebSocket та сповіщень про віхи при вимкненому екрані
- Notifications: milestone channel (3m/6m/10m/20m) now audible with critical_offline.ogg / Сповіщення: канал віх тепер звуковий з critical_offline.ogg
- Notifications: fix CHANNEL_OFFLINE_CRITICAL missing from channel cleanup keep set / Сповіщення: виправлено відсутність CHANNEL_OFFLINE_CRITICAL у keep set
- Manifest: add WAKE_LOCK permission / Маніфест: додано дозвіл WAKE_LOCK
- Connection: fix retry showing "0s" by emitting Connecting state before delay; fix attempt resetting to 1 forever after stable session / З'єднання: виправлено "0s" при повторі — статус Connecting тепер до затримки; виправлено скидання спроби на 1 після стабільної сесії
- Connection: 20-min milestone now actually pauses reconnection for 30 min (was a no-op) / З'єднання: віха 20 хв тепер реально призупиняє повтор на 30 хв (було без дії)
- Connection: fix duplicate attempt number in monitor notification text / З'єднання: виправлено подвоєння номера спроби в тексті сповіщення
- Connection: connection log uses wifi-off icon instead of bell-slash / З'єднання: журнал з'єднання використовує іконку wifi-off замість bell-slash
- Settings: "Override silent mode" sub-setting for critical offline alert (default off, does not bypass DND) / Налаштування: підпункт «Обійти тишу» для критичного офлайн-сповіщення (за замовчуванням вимкнено, не обходить DND)
- UI: RetryLogCard is now scrollable with capped height / Інтерфейс: RetryLogCard тепер прокручується з обмеженою висотою
- UI: header trident enlarged from 32dp to 44dp / Інтерфейс: трIDENT у шапці збільшено з 32dp до 44dp
- ConnectionLog: first offline sighting now opens pending episode instead of being swallowed / ConnectionLog: перший офлайн тепер відкриває pending замість поглинання
- ConnectionLog: zero grace — every drop counts (was silently dropping <5s blips) / ConnectionLog: нульова мінімальна тривалість — кожен збій враховується (мало <5с падіння)

- Network: removed duplicate reconnect trigger from onAvailable, added generation guard for stale socket callbacks / Мережа: прибрано дубльований reconnect з onAvailable, додано generation guard для застарілих callback-ів
- Offline: persist reconnect start millis across process kills so 3/6/10/20-min milestones survive restarts / Офлайн: зберігається reconnect start millis між перезапусками для коректних 3/6/10/20-хвилинних віх
- UI: header pill now immediately reflects connection drops (mirror rule enforcement) / Інтерфейс: пілл одразу відображає втрату з'єднання (узгодження mirror rule)
- Widget: skip snapshot pipeline when no widgets are placed (battery optimization) / Віджет: пропуск pipeline коли віджети не встановлені (оптимізація батареї)
- Map: marker smoothing loop no longer freezes on overlay changes / Мапа: loop згладжування маркерів більше не замерзає при зміні overlay
- Test MIG: deterministic launch base per test fire (no per-frame random) / Тестовий MIG: визначена база запуску на кожен тест (без per-frame random)
- Logs: removed runBlocking from IO-thread persistence in ConnectionLog/DebugLog / Логи: прибрано runBlocking з IO-thread persistence
- Code: deleted unused alertEpoch, cached threat-toggle pref keys, O(1) city lookups / Код: видалено alertEpoch, кешовано threat-toggle ключі, O(1) пошук міст

- Strings: flyby animation description routed through Strings (was English-only) / Строки: опис анімації польоту через Strings (був англійською)
- Data: Neptun URLs centralized to single constants / Дані: URL Neptun зcentralізовано в одних константах
- Data: Ukraine bounding boxes & Odesa coords merged to shared GeoConstants / Дані: межі України та координати Одеси об'єднано в GeoConstants
- ConnectionStatus: neptun.in.ua label uses Strings.connNeptunLabel / Статус з'єднання: мітка neptun.in.ua через Strings
- Logs: DecisionCard redesigned — title shows "Entered zone" (accent color = zone), second row has type · distance + date right-aligned, day/night + ringer on third row with notified badge right-aligned (green ✓ Notified / amber suppressed); "left zone" entries now show threat type + city. / Логи: DecisionCard перероблено — заголовок «Entered zone» (колір = зона), другий рядок: тип · відстань + дата праворуч, день/ніч + сирена в третьому з міткою сповіщення праворуч (✓ Notified / пригнічено); «left zone» тепер показує тип загрози + місто
- Logs: header redesigned — Decisions/Connections are now Material 3 tabs; filter chips consolidated into single scrollable row with leading icons (🕐 Timeline / 📍 Proximity / 📋 Type / ↕ Sort / ✓ Notified / ⭐ Shoot-downs); test buttons moved below retry log card; attempt counter now shows 1-indexed. / Логи: заголовок перероблено — Decisions/Connections тепер вкладки Material 3; фільтри об'єднано в один рядок з іконками; кнопки тестів перенесено під картку retry; лічильник спроб тепер починається з 1
