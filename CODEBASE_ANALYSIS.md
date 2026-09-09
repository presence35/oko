# Oko — Codebase Static Analysis Report

**Project**: Ukraine Drones (Oko) — live air-threat map Android app
**Version**: 0.5.78 (versionCode 392)
**Date**: 2026-09-09
**Method**: Static source analysis (no build/test execution)

---

## Summary

Oko is a well-architected, mature Android app with a clean engine/service/UI separation, strong adherence to the "single evaluation logic" mirror rule, and a source-agnostic threat engine. The codebase demonstrates sophisticated concurrency patterns (coroutines/StateFlows, monotonic clock isolation, generation-based WebSocket lifecycle) and a thoughtful plugin architecture. However, there are notable concerns around the `community` package being undocumented, `ZonePrefs` god-object tendencies, and several edge-case risks in the official-alert region-latch implementation.

**Health Score: 7.5/10**

---

## 1. Architecture & Modularity

### Strengths
- **Clean separation**: Engine (`engine/`), connection (`connection/`), plugins (`plugins/`), UI (`ui/`), domain (`domain/`), service (`service/`), flourish (`flourish/`), data (`data/`), widget (`widget/`) — properly sub-packaged.
- **Mirror rule works**: `MainViewModel` and `AlertService` both construct `ThreatEngine(registry.typeCatalog.value)` and call `engine.evaluate(...)` — no duplicated zone/tier/prediction logic. Verified in `MainViewModel.kt:256` and `AlertService.kt`.
- **Plugin SPI is clean**: `ThreatSource` interface with `PluginRegistry` as health authority; `AppPluginHolder` as singleton orchestrator.

### Concerns

**Undocumented `community` package** (`D:\Desktop\oko\app\src\main\java\ua\ukrainedrones\community\`):
- Contains `CommunityAlertGate.kt`, `CommunityAlertModels.kt`, `CommunityAlertEnricher.kt`, `CompactPolygon.kt`, `CompactOblastBoundaries.kt`, `CompactRaionBoundaries.kt`, `FrontlineCommunityCatalog.kt`.
- This is **entirely absent from ARCHITECTURE.md**. The package appears to implement community/hromada-level air raid alerts with geographic proximity gating (`CommunityAlertGate.evaluate()`).
- `CommunityAlertGate.evaluate()` calls `FrontlineCommunityCatalog.findRaionForAlert()` — this is new alert-source infrastructure that should be documented in the module map.

**`ThreatEngine` takes `typeCatalog` as constructor param** (`ThreatEngine.kt:38`), which is correct (source-agnostic), but:
- The engine creates `SpeedCache` internally (`ThreatEngine.kt:40`) — each engine instance has its own, which is correct per the contract.
- However, `AppPluginHolder` is a singleton and `AppPluginHolder.registry` is accessed by both `MainViewModel` and `AlertService`, meaning both create separate `ThreatEngine` instances with the same `typeCatalog`. This is fine (intentional isolation per consumer).

**TypeBridge hardcodes NEPTUN type names** (`TypeBridge.kt:13`):
```kotlin
fun isFastType(type: ThreatType): Boolean =
    NEPTUN_TYPES[type.apiKey]?.isFast ?: DEFAULT_THREAT_PROPS.isFast
```
This reads from `NEPTUN_TYPES` (defined in `ThreatProps.kt`). The `ThreatType` enum maps to engine strings via `toEngineString()` in `TypeMapping.kt`. While the engine itself doesn't hardcode type names, the bridge layer does reference `NEPTUN_TYPES` — a mild leak of NEPTUN-specific knowledge into the engine-adjacent layer.

---

## 2. Engine Correctness

### `evaluate()` — Verified against BEHAVIORS.md (`ThreatEngine.kt:45-144`)

**Correct**:
- Skips resolved/ghost threats (line 67)
- Skips hidden types (line 68)
- Records speed cache fixes (line 72)
- Computes predicted position for fast threats (line 76)
- Adds all visible threats to `mapThreats` (line 79)
- Skips stale/focus-less/advisory/areaOnly/silenced (lines 81-82)
- Distance uses Haversine (`distanceHaversine`) for slow, predicted for fast (lines 84-86)
- `zoneTier()` follows fast-ETA vs slow-distance rule (line 254)
- `scoreThreat()` uses multiplicative formula with all named factors (line 359)
- `aggregateScores()` uses top-3 diminishing returns [1.0, 0.5, 0.25] (line 386)
- `officialAlertActiveFor()` called with scope parameter (line 116)
- `computeRedCities()` is region-precise (line 117)
- `computeFillKeys()` derived directly from alerts (lines 118-119)

**Deviations from BEHAVIORS.md**:
1. **`computeFillKeys` only processes non-yellow alerts** (line 118): `alerts.filter { it.level != "yellow" }`. BEHAVIORS.md doesn't mention yellow alerts at all — this appears to be an extension (yellow = tactical/artillery). The `computeRedCities` function also only processes red alerts. This is consistent with the "yellow" level addition (level "yellow" alerts) but not explicitly covered in the behavioral contract.
2. **`deriveOfficialAlertReason` uses `distanceFlat` not `distanceHaversine`** (`ThreatEngine.kt:237`): The BEHAVIORS.md contract says "Haversine for distance." However, this is in the reason-derivation path, not the core tiering/prediction path. Minor inconsistency.
3. **`scoreThreat` `qualityFactor`** (lines 422-435): The spec says `qualityFactor: positionQuality + uncertaintyKm` — this seems like shorthand for a combined score. The implementation is more nuanced (base by positionQuality × uncertainty multiplier). The intent matches but the formula differs from the simplified spec notation.

### `zoneTier` — Verified (`ThreatEngine.kt:254-275`)
**Correct**: Returns null when `distKm > props.reachKm`, handles `alwaysInnerWithinReach`, fast=ETA, slow=distance. Exact match.

### `predictPosition` — Verified (`ThreatEngine.kt:284-311`)
**Correct**: Gates on `canDrift`, uses server course only (`bearingDeg ?: heading`), anchors to latest fix (`updatedAtMillis ?: confirmedAtMillis`), caps by `horizonSec` AND `maxGhostMeters` AND `DRIFT_MAX_METERS` (5000m). This is more restrictive than the spec mentions (spec doesn't mention the hard `DRIFT_MAX_METERS` cap, but it's a sensible safety addition).

### `canDrift` — Verified (`ThreatEngine.kt:281`)
**Correct**: `!isStale(t, props, now) && t.flying` — matches spec exactly.

### `SpeedCache` — Verified (`SpeedCache.kt:1-73`)
**Correct**: `@Synchronized` on all public methods, per-threat ID queue (max 4 fixes), thread-safe.
**Issue**: `SpeedCache` is a class, not an object — each `ThreatEngine` instance gets its own. This is correct per the spec ("engine-internal, not a global singleton"), but means speed history doesn't transfer between UI and service engine instances. The spec notes "speed fallback is deterministic (server → trail → nominal), so consumers can't disagree near a zone boundary" — confirmed: all three paths (server speed, measured, trail, nominal) are deterministic.

### Non-negotiable constraint check

| Constraint | Status | Evidence |
|---|---|---|
| Single evaluation logic | ✅ | Both MainViewModel and AlertService use `engine.evaluate()` |
| Pure & deterministic | ✅ | All engine functions are pure with explicit inputs |
| Source-agnostic | ✅ | Engine works with `NormalizedThreat`/`ThreatProps`; NEPTUN JSON parsed in `data/Threat.kt` |
| Plugin-provided type properties | ✅ | `propsFor(type)` reads from `typeCatalog`; `DEFAULT_THREAT_PROPS` for unknown |
| Haversine for distance | ✅ | `distanceHaversine()` in core path; `distanceFlat` used only in `deriveOfficialAlertReason` |
| Motion heading shared, server course drives drift | ✅ | `motionHeading()` uses `bearingDeg ?: heading ?: measured`; `predictPosition` uses only server course |
| Slow tier uses raw fix | ✅ | `tierLat = if (props.isFast) predicted.lat else t.lat` (line 84) |
| Fast tier uses predicted position | ✅ | Same logic, inverted |
| `alwaysInnerWithinReach` from props | ✅ | `props.alwaysInnerWithinReach` checked in `zoneTier` |
| Advisory/areaOnly never tier | ✅ | Lines 82: `if (t.advisory || t.areaOnly ...) continue` |
| Explicit `now` parameter | ✅ | All time functions take `now: Long` |
| Official-alert evaluation engine-owned | ✅ | `officialAlertActiveFor`, `computeRedCities`, `deriveOfficialAlertReason` all in `ThreatEngine`/`OblastAlert` |
| Thread-safe speed cache | ✅ | `@Synchronized` on all public methods |

---

## 3. Data Flow & State Management

### NEPTUN WS + REST Merge (`NeptunConnectionClient.kt:58-609`)

**Architecture**: Generation-based lifecycle with `AtomicInteger` (`connectionGeneration`), sequential frame processing via `Channel<String>(UNLIMITED)` (`frameChannel`), separate `StateFlow`s for `connectionState`, `threats`, `alerts`, `removedThreats`.

**Correct**:
- Strict generation-based lifecycle prevents stale socket callbacks (lines 97, 148)
- Watchdog pings every 20s, 45s stale detection (lines 88, 66)
- Reconnect backoff 1-3s → capped 15s (`calculateBackoffMs`, line 78)
- Monotonic clock for in-process ephemeral deltas (`lastSocketFrameMono`, `lastValidThreatUpdateMono`)
- Public StateFlows stay wall-clock for Sources tab rendering
- Alert clear debounce: `ALERT_CLEAR_CONFIRM_MS = 30_000L` (line 73)
- `markUserShot` / `wasUserShotRecently` grace tracking via `ConcurrentHashMap` (lines 151-152)

**Concern**: The `frameChannel` has `capacity = Channel.UNLIMITED`. If frame processing falls behind (e.g., during a massive bombardment wave), this could buffer unbounded frames in memory. A bounded channel with drop policy might be safer.

### PluginRegistry Takeover Semantics (`PluginRegistry.kt:1-267`)

**Correct**:
- Takeover merge: authoritative source's snapshots are sole truth; stale holders fill only when nothing is authoritative (lines 167-200)
- `wsHealthy` = an enabled WS source is `CONNECTED` (line 236)
- `degraded` = `!wsHealthy` with enabled sources (line 238)
- `isOffline(now)` = degraded past `OFFLINE_EPISODE_MS` (5 min) with no fallback (lines 255-260)
- `lastThreatUpdateAt` stamped on monotonic clock for source-agnostic staleness (lines 151, 265-266)
- **Every merge/health derivation reads `enabledPlugins`** — so a disabled source can't feed, own, or degrade anything (line 148)

**Concern**: `remergeThreats()` updates `_lastThreatUpdateAt` with `Monotonic.now()` even when `enabledPlugins` is empty (line 151). This means after disabling all sources, `lastThreatUpdateAt` keeps ticking forward, potentially preventing `isThreatDataStale` from ever triggering. The staleness check should probably use `Monotonic.now() - _lastThreatUpdateAt.value` which would still work, but the timestamp keeps updating even when no data is flowing — it just won't go stale since the "now" keeps advancing. Actually, this is correct behavior — `isThreatDataStale` checks if `now - lastThreatUpdateAt >= THREAT_DATA_STALE_MS`, and since `now` is monotonic and keeps advancing, eventually it will go stale. But the semantics are slightly confusing: the "last threat update" keeps being stamped even when no threats are being updated.

**Concern**: `recheckConnection()` (line 229) reads `_plugins.value` directly — this is a `MutableStateFlow`, so `.value` is safe. But `enabledPlugins` filters by `it.enabled.value` which is a separate `StateFlow` per plugin. There's no synchronization between reading `_plugins` and reading each plugin's `enabled.value` — theoretically a plugin could be toggled between these reads. In practice this is a minor race (the worst case is a brief inconsistent view), but worth noting.

---

## 4. Concurrency & Threading

### Coroutines/Flows

**MainViewModel** (`MainViewModel.kt:200+`):
- `neptunForUi = combine(...).sample(120)` — correct 120ms sampling for UI (line 264-266)
- `threatsFlow` uses `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())` — correct lifecycle-aware sharing (line 259)
- `zonesFlow` combines prefs correctly (line 288)
- `buildUiState` ends in `.flowOn(Dispatchers.Default)` before `stateIn` — correct for off-main-thread computation

**AlertService** (`AlertService.kt:1`):
- `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — correct for background service
- `monitoringJob` manages the main monitoring loop

**SpeedCache** (`SpeedCache.kt`):
- `@Synchronized` on `record`, `estimateWithSource`, `measuredHeading` — thread-safe

**Potential Issues**:
1. **MainViewModel property initializers run before `init{}`** (line 256): `engine = ThreatEngine(registry.typeCatalog.value)` — but `registry` is `AppPluginHolder.registry`, which requires `init { AppPluginHolder.init(...) }` in the `init` block (line 252). Property initializers run before `init` blocks, but `AppPluginHolder.registry` throws `IllegalStateException` if not initialized. However, `AppPluginHolder.init` is called at the start of the `init` block, so `registry` getter is safe by the time `engine` is initialized. Wait — actually, `engine = ThreatEngine(registry.typeCatalog.value)` is a property initializer, and property initializers run in declaration order. Since `registry` is declared before `engine`, and `init` blocks run after all property initializers... this could be a problem. Let me re-check: `registry` is a property with a getter (`AppPluginHolder.registry`), not a backing field, so it's evaluated lazily when `engine` references it. By that point, `init { AppPluginHolder.init(...) }` has already run. Actually no — property initializers in Kotlin run in order, and `init` blocks run after all property initializers. So `engine = ThreatEngine(registry.typeCatalog.value)` would try to access `AppPluginHolder.registry` before `AppPluginHolder.init(getApplication())` runs. But `registry` is a computed property (`get() = _registry ?: throw`), so it would throw. However, the comment on line 248-250 explicitly says: "The plugin registry must exist before any property below reads it — property initializers run before the init{} block, so init it here (idempotent; the later init block also calls it after AlertService may have started)." And `AppPluginHolder.init` is called at the top of the `init` block — but `init` blocks run after property initializers, not before. This is a **real bug** — unless Kotlin's property initialization order works differently than I think. Actually, re-reading: in Kotlin, `companion object` properties and regular class properties initialize in order, but `init` blocks also run as part of initialization. The order is: (1) property initializers in declaration order, (2) `init` blocks in order. So yes, `engine` would try to access `AppPluginHolder.registry` before `AppPluginHolder.init()` runs. But `AppPluginHolder.init` is idempotent and `AppPluginHolder.registry` is a computed property... the `init` block on line 252 calls `AppPluginHolder.init(getApplication())`, but by the time `engine` property initializer runs, `AppPluginHolder.init` hasn't been called yet. This would throw `IllegalStateException`. Unless `AppPluginHolder._registry` is somehow pre-initialized... but looking at `AppPluginHolder.kt`, `_registry` starts as `null`. This appears to be a genuine bug. The comment acknowledges the ordering concern but says "init it here" — meaning the `init` block — which is too late for property initializers.

Wait, actually re-reading the MainViewModel more carefully: `private val registry = AppPluginHolder.registry` is a property initializer. This runs when `MainViewModel` is constructed. `init { AppPluginHolder.init(getApplication()) }` is an `init` block. In Kotlin, `init` blocks run AFTER all property initializers. So `AppPluginHolder.registry` would throw because `_registry` is null. **This is a real bug.** Unless the `init` block on line 252 is actually the FIRST thing that runs because `AppPluginHolder.init` is called inside it. But property initializers always precede `init` blocks in Kotlin.

Actually, I need to re-examine. Looking at lines 248-256:
```kotlin
// The plugin registry must exist before any property below reads it — property
// initializers run before the init{} block, so init it here (idempotent; the later
// init block also calls it after AlertService may have started).
init {
    AppPluginHolder.init(getApplication())
}
private val registry = AppPluginHolder.registry
```

Wait — the comment says "property initializers run before the init{} block, so init it here." So they KNOW this and put `AppPluginHolder.init()` BEFORE the property initializer. But the actual code shows `init` block first, then `registry` property. Let me re-read... Actually looking at lines 252-255:
```kotlin
init {
    AppPluginHolder.init(getApplication())
}
private val registry = AppPluginHolder.registry
```

Hmm, but in Kotlin, `init` blocks and property initializers are interleaved by declaration order. The `init` block is declared BEFORE `registry`, so it runs first. Wait no — Kotlin initializes class members in the order they appear textually: property initializers and `init` blocks are both executed in textual order, but all property initializers that come before an `init` block run before that `init` block, and `init` blocks run before property initializers that come after them. So: `init { AppPluginHolder.init() }` runs first (it's declared first), then `registry = AppPluginHolder.registry`. This is CORRECT. The comment is right and the code is right.

2. **`NeptunConnectionClient` scope uses `SupervisorJob() + Dispatchers.IO`** (line 92) — correct, failures in one coroutine don't cancel others.
3. **`frameChannel = Channel<String>(capacity = Channel.UNLIMITED)`** — unbounded buffer concern as noted above.

---

## 5. Testing Coverage

**21 test files** found in `app/src/test/java/ua/ukrainedrones/`.

| Test File | Coverage |
|---|---|
| `ThreatEngineTest.kt` | `evaluate` zoning, `zoneTier`, `predictPosition`, `motionHeading`, `isStale`/`isGhost`, scoring, AVIATION override, official-alert outputs |
| `OblastAlertScopeTest.kt` | `inOblast`/`isOblastWide`/`coversCity`/`officialAlertActiveFor` matching |
| `PluginRegistryTest.kt` | Plugin merging, `typeCatalog` |
| `ConnectionStateTest.kt` | Connection state machine |
| `NeptunClientTest.kt` | Reconnect backoff, `ConnectionState` degradation |
| `DebugLogTest.kt` | Serialize/parse, ring-buffer cap, `computeSweep` verdicts |
| `ConnectionLogTest.kt` | Episode-commit rules |
| `CitiesTest.kt` | City-list integrity, `resolveFocus` |
| `ThreatTest.kt` | JSON parsing, type mapping, course translation |
| `TransliterationTest.kt` | КМУ №55 romanization |
| `UpdateManagerTest.kt` | `versionNameGreater` |
| `NightModeTest.kt` | Night-window resolution |
| `VibrationTest.kt` | `vibrationPattern` levels |
| `StringsFormatTest.kt` | `formatDateTime` per-language |
| `WidgetSnapshotTest.kt` | `computeWidgetSnapshot` |
| `AviationFlybyTest.kt` | `AviationFlyby` policy/geometry |
| `IconGeometryTest.kt` | `computeIconGeometry` PCA |
| `ApiMonitorTest.kt` | `ApiMonitor` |
| `MarkerRotationTest.kt` | Marker rotation |
| `ThreatCardDedupeTest.kt` | Dedup logic |
| `ConnEventTest.kt` | Connection events |
| `ShelterTest.kt` | Shelter ranking |
| `TestThreats.kt` | Shared `threat(...)` builder helper |

**Gaps**:
- No dedicated test for `computeFillKeys` (new feature, region-precise fills)
- No test for `PluginRegistry.isOffline()` edge cases
- No test for `NeptunConnectionClient` generation-based lifecycle race conditions
- No integration test verifying the mirror rule (both consumers calling the same engine)
- No test for `CommunityAlertGate` (undocumented package)
- `ThreatEngineTest` exists but the test class name (`ThreatEvaluatorTest.kt`) suggests it might be an older artifact — there are two files (`ThreatEvaluatorTest.kt` and `ThreatEngineTest.kt`) which could cause confusion.

---

## 6. Performance

### Recomposition Strategy

**Well-implemented**:
- `sample(120)` on `neptunForUi` — bounds recomposition to ~8fps for NEPTUN data (line 264)
- `SelectionUi` is OUTSIDE `UiState` — tapping a threat only recomposes `ThreatCardHost` (line 172-178)
- No global wall-clock StateFlow — each consumer has its own scoped clocks
- `@Immutable` models: `UiState`, `SelectionUi`, `RevealRequest`, `ThreatProximity` (line 61, 172, 181, 191)
- `flowOn(Dispatchers.Default)` before `stateIn` — off-main-thread computation
- `mapVisibleFlow` and `shelterModeFlow` prevent animation waste when map isn't visible
- `ThreatElapsedText` has its own 1s clock — card body doesn't recompose per second
- MapView marker loop runs at 30fps with idle fallback to 1s
- Death animation cap `MAX_DEATHS = 14`, explosion glow is lazily pre-rendered bitmap
- `IconGeometryCache` decodes/caches per `ThreatIconSet`

**Concerns**:
1. **`UiState` has ~60+ fields** (`MainViewModel.kt:62-165`). This is a massive data class. Compose will recompose on any field change. While `@Immutable` helps with structural equality, the sheer number of fields means most state changes trigger recomposition of the entire screen. Consider splitting `UiState` into domain-specific states.
2. **`threatsFlow` uses `stateIn` with `WhileSubscribed(5000)`** — when no collector is active, the upstream stops, but when a collector resumes, it replays. This is correct but could cause a burst of recomposition on resume.
3. **`NeptunConnectionClient.frameChannel` is UNLIMITED** — memory concern under heavy load.

### Map Rendering
- `MapView` owns OSMdroid rendering; `resolveThreatPose` decides parked/orbit/drift
- One adaptive loop at 30fps while anything moves, idles at 1s
- Threat icons scale with zoom (1x→3x across ~3 zoom levels, 8dp-bucket quantization)
- `deOverlapThreats` with 4 modes (DEFAULT/GRID/SPREAD/COUNT)
- `buildUiState` must stay free of main-thread/Android-UI dependencies

---

## 7. Code Quality & Conventions

### Conventions Compliance

**EN-only strings during normal work**: Verified — `Strings.kt` uses `StringSet` with `AppLanguage.EN`/`AppLanguage.UA`. The CHANGELOG shows EN-first entries with `/` separator for UA.

**UA/EN text through `Strings`**: Verified — `Strings.get(lang).StringSet` pattern used throughout.

**No Android resource localization**: Verified — `R.string` references replaced by `Strings` system.

**Non-ASCII file editing**: AGENTS.md specifies .NET approach; codebase has Cyrillic in `Strings.kt`, `Cities.kt`, etc. The source files appear to use proper UTF-8.

### God Object Concern: `ZonePrefs`

`ZonePrefs.kt` is flagged in ARCHITECTURE.md as "god object mixing prefs with persisted state." Assessment:
- **Preferences**: `AppLanguage`, `ThreatCardSize`, `ThreatIconSet`, `OverlapMode`, night config, toggles/thresholds
- **Persisted state**: `ConnectionLog` (ring buffer), `offline-restore state`, `onboarding flags`, `widget_snapshot`, `last_notified_update_code`
- **Threat map flows**: `threatMapFlow`, `threatAlertFlow`
- **Settings**: `haptics_enabled` (tri-state), `followMe`, `pinnedCity`

This is a genuine concern. The `zone_preferences` DataStore file mixes configuration (user preferences that should be resettable) with application state (should survive app updates and be managed differently). The split suggested in ARCHITECTURE.md (`AppPrefs` / `ConnectionLogStore`) hasn't been implemented.

### Other Quality Issues

1. **`ThreatEvaluatorTest.kt` and `ThreatEngineTest.kt`** both exist — naming confusion. If `ThreatEvaluatorTest` is the old name, it should be cleaned up.
2. **`Compat.kt` deleted** (per ARCHITECTURE.md line 156) — engine types now imported directly. Good cleanup.
3. **`community` package lacks tests** — `CommunityAlertGate` has no test coverage despite being a new alert-source feature.

---

## 8. Security & Secrets

### Secrets Handling

- **`app/keystore.properties`** — git-ignored, signing credentials
- **`app/upload.properties`** — git-ignored, FTP credentials
- **`app/carto.properties`** — git-ignored, CARTO API key
- **`app/telegram.properties`** — git-ignored, Telegram bot token
- **`app/upload.properties`** read in `build.gradle.kts` for FTP upload (lines 196-204)

### Build Config Exposure

In `app/build.gradle.kts` (lines 44-46):
```kotlin
buildConfigField("String", "CARTO_API_KEY", "\"$cartoApiKey\"")
buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"$telegramBotToken\"")
buildConfigField("String", "TELEGRAM_CHAT_ID", "\"$telegramChatId\"")
```

**Issue**: `CARTO_API_KEY`, `TELEGRAM_BOT_TOKEN`, and `TELEGRAM_CHAT_ID` are exposed as `BuildConfig` fields. This means they're readable via `BuildConfig.CARTO_API_KEY` at runtime and appear in the APK. For a Telegram bot token this is particularly concerning — anyone who decompiles the APK can extract the token and abuse it.

The `CARTO_API_KEY` is for a map tile provider (CARTO) — this should ideally be restricted by domain/referer on the server side, but storing it in BuildConfig is risky.

The `TELEGRAM_BOT_TOKEN` exposure is the most serious security concern. It should be moved to a server-side proxy rather than embedded in the APK.

---

## 9. Potential Bugs & Risks

### Critical

1. **`MainViewModel` property initialization ordering**: As analyzed in Section 4, the `init { AppPluginHolder.init(...) }` block must run before `private val registry = AppPluginHolder.registry`. In Kotlin, `init` blocks and property initializers run in textual order. If `registry` property appears after the `init` block, it works. But if someone reorganizes the file and puts `registry` before the `init` block, it breaks. The comment on lines 248-250 documents this, but it's fragile. Consider using `lazy { }` or a proper `init`-only pattern.

2. **`TELEGRAM_BOT_TOKEN` in BuildConfig**: Exposed in APK (Section 8).

### High

3. **`NeptunConnectionClient.frameChannel` unbounded**: `Channel.UNLIMITED` could cause OOM under sustained high-message throughput (massive bombardment waves).

4. **`PluginRegistry.remergeThreats()` stamps `lastThreatUpdateAt` on every merge** including when `enabledPlugins` is empty: The timestamp advances even with no data. This means `isThreatDataStale` won't trigger until `Monotonic.now()` advances past the threshold, which it will, but the semantics are misleading.

5. **`ZonePrefs` god object**: Mixes preferences with persisted state. A crash during `ConnectionLog` persistence could corrupt preferences. The recommended split hasn't been implemented.

### Medium

6. **Official-alert region-latch edge case**: `AlertService` uses `officialRegionToken` to latch all-clears. But what happens if the focus switches between oblasts DURING an official alert? The service checks `state.focusToken == officialRegionToken` — if the focus token changes, the all-clear is suppressed. But `MainViewModel`'s `officialAlertActiveFor` uses the current focus token. This means the UI banner might show an alert while the service silences the siren (if the focus moved to a non-alerting region). This is by design (the service is region-latched, the UI shows current focus) but could confuse users.

7. **`computeRedCities` uses `Cities.ALL`** — this is a hardcoded list of ~483 places. If a new city is added to Ukraine but not in `Cities.ALL`, it won't appear in red labels. The system is correct for existing cities, but the maintenance burden is non-trivial.

8. **`TypeBridge.isFastType` and `typicalSpeedKmh` read `NEPTUN_TYPES` directly** — these are engine-adjacent functions that hardcode the NEPTUN type catalog. If a new plugin adds a type not in `NEPTUN_TYPES`, these functions return defaults. This is a mild concern but violates the "never hardcode type names" principle.

### Low

9. **`ThreatEvaluatorTest.kt` duplication**: Two test files with similar names suggest legacy from the pre-refactor project. Should be consolidated or removed.
10. **`ConnectionSupervisor` milestone tracking** is well-implemented but not tested separately (no `ConnectionSupervisorTest.kt`).
11. **`AlertWatchdog` (WorkManager 15-min periodic)** — no test for the scenario where WorkManager itself is killed by battery optimization.

---

## 10. Update & Release Pipeline

### Gradle Tasks (`app/build.gradle.kts:159-295`)

**`bumpVersion`**: Auto-bumps `versionCode` by 1 and `versionName` patch. Supports `-PnewVersion=X.Y.Z` override. Writes to `version.properties`. Correctly implemented.

**`release`**: Depends on `bumpVersion`, then delegates to `uploadRelease`. The release task is a `GradleBuild` type — it spawns a fresh Gradle process for `:app:uploadRelease`. This ensures the APK and `version.json` both carry the new version.

**`uploadRelease`**: Generates `version.json` from `version.properties` + changelog notes, uploads APK and `version.json` via FTP using `curl`. FTP credentials in `upload.properties`.

**Issues**:
1. **FTP upload uses `curl`** — depends on curl being installed on the build machine. No fallback. Also, FTP is plaintext (credentials sent in cleartext over the network). Should use `sftp` or FTPS.
2. **`buildNotesFromChangelog()`** reads `CHANGELOG.md` and extracts bullets under `## [Unreleased]`. Each line is split on ` / ` to produce EN/UA. If a changelog line doesn't contain ` / `, only EN is produced and UA is empty. This means the server `version.json` could have empty UA notes for any bullet that lacks the separator.
3. **No verification step** — after upload, the task just prints the URL. There's no HTTP check to verify `version.json` is accessible at `https://odesaplay.com.ua/other_apps/ukrainedrones/version.json`.
4. **`uploadRelease` depends on `assembleRelease`** which is declared via `dependsOn("assembleRelease")` — but `release` task calls `:app:uploadRelease` which is a separate task. The `release` task delegates to a fresh Gradle process with `tasks = listOf(":app:uploadRelease")`. This is correct.

---

## Findings by Dimension Summary

### Critical Issues (must-fix)
1. **`TELEGRAM_BOT_TOKEN` in BuildConfig** — security risk, should be server-side proxied
2. **`MainViewModel` init ordering fragility** — documented but vulnerable to file reorganization

### Suggestions (nice-to-have)
1. **Document `community` package** in `ARCHITECTURE.md` — it's a significant new feature (community hromada alerts)
2. **Bound `frameChannel` capacity** — replace `Channel.UNLIMITED` with a bounded channel + drop policy
3. **Split `ZonePrefs`** into `AppPrefs` + `ConnectionLogStore` per the documented plan
4. **Add `curl` → `sftp`/`FTPS`** for upload security
5. **Consolidate `ThreatEvaluatorTest.kt`** (legacy name) with `ThreatEngineTest.kt`
6. **Add tests for `computeFillKeys`** and `PluginRegistry.isOffline()` edge cases
7. **Add `version.json` HTTP verification** post-upload
8. **Move `CommunityAlertGate` tests** if the package is production code
9. **`TypeBridge.isFastType` should not reference `NEPTUN_TYPES`** — should use the type catalog passed through the plugin system
10. **Add a `ConnectionSupervisorTest`** — milestone tracking is untested

---

## Codebase Health Score: 7.5/10

**Rationale**:
- **+2.0**: Excellent architecture (engine/service/UI mirror rule, source-agnostic engine, plugin SPI)
- **+1.5**: Strong concurrency patterns (monotonic clock isolation, generation-based lifecycle, StateFlows)
- **+1.0**: Good test coverage (21 test files, comprehensive engine tests)
- **+0.5**: Clean UI state management (SelectionUi separation, @Immutable models, sample rates)
- **-0.5**: `ZonePrefs` god object (documented but not fixed)
- **-0.5**: `community` package undocumented
- **-0.5**: `TELEGRAM_BOT_TOKEN` in BuildConfig (security)
- **-0.5**: `frameChannel` unbounded (potential OOM)

---

## State Plumbing / Recomposition Contract (verified)

The performance optimization strategy is well-documented in `ARCHITECTURE.md` (lines 546-571) and verified in code:

- ✅ **UI samples the stream**: `MainViewModel` uses `combine(...).sample(120)` for UI path (line 264)
- ✅ **Clock is not state**: No global wall-clock StateFlow; `lastFrameAt` is collected by connection sheet only
- ✅ **Selection is not UiState**: `SelectionUi` is outside `UiState`; only `ThreatCardHost` collects it
- ✅ **Off-main state building**: `.flowOn(Dispatchers.Default)` before `stateIn`
- ✅ **Stability**: `@Immutable` annotations on model classes and `UiState`
- ✅ **Popup clock is a leaf**: `ThreatElapsedText` has its own 1s clock

---

## Deliberate Tradeoffs (verified)

### Mirrored UI and Alert Evaluation
Both `MainViewModel` and `AlertService` construct their own `ThreatEngine` instances. Each has its own `SpeedCache` (per-consumer, not shared). Speed fallback is deterministic (server → trail → nominal). The engine outputs (redCities, focusOblastAlertActive, reason) are identical for both consumers because they use the same `typeCatalog` and same inputs.

### Foreground Service Instead of Backend/Push
No intermediate server buffers anything. Alerts stop when monitoring stops. The app must be kept alive (battery-exemption flow). `AlertWatchdog` (WorkManager 15-min periodic) restarts the service if it died. `BootReceiver` restarts on reboot.

### Direct Third-Party API Dependency
NEPTUN is consumed directly. The REST/WS merge protects against CDN-cached staleness. The `community` package adds Ubilling REST as a potential fallback (gated by `CommunityAlertGate`).

---

## Failure Modes (verified)

| Failure | Behavior | Owner |
|---|---|---|
| NEPTUN offline | Offline pill + monitor notification with Retry; last-known alerts held; drop persisted across restarts | `ConnectionHolder`, `AlertService` |
| Stale threats | Dimmed on map (alpha 0.45); excluded from tiers/alerts/strip/gauge; ghosts removed after ~30 min | `engine/ThreatEngine.kt` + consumers |
| Service process interrupted | Recovery depends on Android FGS lifecycle; logs restored from DataStore on restart | `AlertService`, DataStore |
| Monitoring silently dead | `MonitoringStatus.running` false → `UiState.protectionState` OFFLINE → red "SERVICE OFFLINE" banner | `MonitoringStatus`, `AlertWatchdog`, `MainScreen` |
| Reboot | `BootReceiver` restarts service (unless `boot_restart_enabled` disabled); persistent "Monitoring paused" if not auto-restarted | `BootReceiver`, `AlertWatchdog` |
| Package replaced | `BootReceiver` restarts service on `MY_PACKAGE_REPLACED` | `BootReceiver` |
| Location unavailable | Follow-me uses last-known fix; no fix → country-wide focus + "No GPS fix" warning | `LocationTracker`, `Cities` |

---

*Report generated from static source analysis. No build/test execution performed. Findings are based on code inspection against documented invariants in `ARCHITECTURE.md`, `BEHAVIORS.md`, and `AGENTS.md`.*
