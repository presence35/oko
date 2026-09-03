# Behaviors — Threat Evaluation Engine

Source of truth for engine contract. Read before any engine work. If you change a
behavior here, update the implementation in the same change.

## Inputs

| Input | Type | Description |
|---|---|---|
| Threat stream | `List<NormalizedThreat>` | Source-agnostic threat objects (see Threat Model) |
| Official alerts | `List<OblastAlert>` | Regional alert feed |
| Focus state | `LatLng`, `FocusCity?`, `hasGps: Boolean` | Where to center evaluation |
| Zone params | `ZoneParams` (day/night variants) | User thresholds + armed bells |
| Type gates | `hiddenTypes: Set<String>`, `silencedTypes: Set<String>` | Per-source filtering |
| Night config | `NightConfig` | Window + overrides |
| Language | `AppLanguage` | UA/EN for reason text |
| `now` | `Long` | Explicit timestamp (deterministic evaluation, testable) |

## Outputs

| Output | Type | Description |
|---|---|---|
| Zone groups | `threatsInner`, `threatsOuter`, `activeZone`, `zoneThreats` | Tier classification |
| Map threats | `List<NormalizedThreat>` with predicted coords | Display-ready (ghosts excluded) |
| Red cities | `Set<String>` | Cities under official alert |
| Official alert | `focusOblastAlertActive: Boolean`, `officialReason: String?`, `reasonThreatId: String?` | Siren state + attribution |
| Threat level | `Double` (0–10) | Aggregate gauge |
| Proximity | `ThreatProximity?` (per selected threat) | Distance, ETA, speed source |

## Threat Model

Source-agnostic. Plugins map raw data to this model. Engine never touches source-specific
formats.

```kotlin
data class NormalizedThreat(
    val id: String,
    val type: String,              // open string, not enum
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val bearingDeg: Double?,       // authoritative velocity bearing
    val speedKmh: Double?,         // server-reported speed
    val status: String,            // "active" | "stale" | "resolved"
    val advisory: Boolean,         // informational only, never alerts
    val areaOnly: Boolean,         // no real point, lat/lon is centroid
    val confirmations: Int,        // source count
    val reliability: String,       // "high" | "medium" | "low" | "unknown"
    val count: Int,                // group size (0 = unspecified)
    val positionQuality: String?,  // "confirmed" | "approx"
    val uncertaintyKm: Double?,
    val confirmedAtMillis: Long?,  // dead-reckon anchor
    val updatedAtMillis: Long?,    // last server update
    val trail: List<TrailPoint>,
    val region: String?,
    val district: String?,
    val locality: String?,
    val explanationShort: String?,
    val title: String,
    val sourceMeta: Map<String, Any> = emptyMap()  // opaque to engine
)

data class TrailPoint(val lat: Double, val lon: Double, val tMillis: Long?)
```

`flying` is derived: `bearingDeg != null && confirmedAtMillis != null && status == "active"`.

## Type Properties (Plugin-Provided)

Plugins define per-type behavior via a properties bag. Engine uses these; it never
references type names directly.

```kotlin
data class ThreatProps(
    val isFast: Boolean,           // tier by ETA (true) vs distance (false)
    val reachKm: Double,           // max engagement range
    val staleAfterMs: Long,        // when to dim
    val ghostCapMs: Long,          // when to remove entirely
    val nominalSpeedMps: Double?,  // fallback speed (null = no dead-reckon without real velocity)
    val horizonSec: Double,        // dead-reckon time cap
    val maxGhostMeters: Double,    // dead-reckon distance cap
)
```

### Engine Defaults (forward-compatible)

Used when a plugin doesn't specify properties for a type. Sensible for unknown future
threat types.

```kotlin
val DEFAULT_THREAT_PROPS = ThreatProps(
    isFast = false,
    reachKm = 1500.0,
    staleAfterMs = 300_000L,       // 5 min
    ghostCapMs = 900_000L,         // 15 min
    nominalSpeedMps = null,         // no dead-reckon without real velocity
    horizonSec = 300.0,
    maxGhostMeters = 18_000.0,
)
```

### NEPTUN Plugin Overrides (for reference)

```kotlin
val NEPTUN_TYPES = mapOf(
    "shahed"       to ThreatProps(isFast = false, reachKm = 1000.0, staleAfterMs = 300_000, ghostCapMs = 900_000, nominalSpeedMps = 50.0, horizonSec = 300.0, maxGhostMeters = 18_000.0),
    "fpv"          to ThreatProps(isFast = false, reachKm = 40.0,   staleAfterMs = 300_000, ghostCapMs = 900_000, nominalSpeedMps = 33.33, horizonSec = 300.0, maxGhostMeters = 18_000.0),
    "cruise"       to ThreatProps(isFast = true,  reachKm = 1500.0, staleAfterMs = 180_000, ghostCapMs = 900_000, nominalSpeedMps = 236.11, horizonSec = 180.0, maxGhostMeters = 30_000.0),
    "ballistic"    to ThreatProps(isFast = true,  reachKm = 1500.0, staleAfterMs = 90_000,  ghostCapMs = 900_000, nominalSpeedMps = 916.67, horizonSec = 90.0, maxGhostMeters = 20_000.0),
    "kab"          to ThreatProps(isFast = true,  reachKm = 70.0,   staleAfterMs = 180_000, ghostCapMs = 900_000, nominalSpeedMps = 250.0, horizonSec = 180.0, maxGhostMeters = 10_000.0),
    "aviation"     to ThreatProps(isFast = true,  reachKm = 9999.0, staleAfterMs = 240_000, ghostCapMs = 7_200_000, nominalSpeedMps = 250.0, horizonSec = 240.0, maxGhostMeters = 24_000.0),
    "recon"        to ThreatProps(isFast = false, reachKm = 50.0,   staleAfterMs = 300_000, ghostCapMs = 900_000, nominalSpeedMps = 22.22, horizonSec = 300.0, maxGhostMeters = 12_000.0),
    "unknown"      to ThreatProps(isFast = false, reachKm = 1500.0, staleAfterMs = 300_000, ghostCapMs = 900_000, nominalSpeedMps = null, horizonSec = 240.0, maxGhostMeters = 10_000.0),
)
```

## Behaviors (Pure Functions)

All functions take explicit inputs. No hidden state. No side effects. Deterministic
given the same inputs.

### `evaluate(...)` — Main Evaluation Pass

```
Input: threats, focus, params, hiddenTypes, silencedTypes, nightActive, nightParams, nightArmed, now
Output: EvaluationResult

For each threat:
  1. Skip resolved or ghost (isGhost check)
  2. Skip hidden types (not on map at all)
  3. Record fix in speed cache (if not stale)
  4. Compute predicted position (if flying)
  5. Add to mapThreats (all visible threats)
  6. Skip stale, advisory, areaOnly, silenced types, no focus → no zone evaluation
  7. Compute distance (Haversine)
  8. For fast types: use predicted position for distance
     For slow types: use raw fix for distance
  9. Compute speed (server > measured > nominal from ThreatProps)
  10. Call zoneTier()
  11. If tiered: compute score, add to zoneThreatsMap, categorize inner/outer
  12. Return EvaluationResult
```

### `zoneTier(props, distKm, speedKmh, params)` — Zone Classification

```
Input: ThreatProps, distance, speed, zone params
Output: ThreatZone? (INNER | OUTER | null)

Rules:
  1. distKm > props.reachKm → null (out of range)
  2. props.isFast && AVIATION special → always INNER (within reach)
  3. props.isFast → tier by ETA (etaMinutes → fastRedMin/fastYellowMin)
  4. !props.isFast → tier by distance (slowRedKm/slowYellowKm)
```

### `predictPosition(threat, speedMps, now)` — Dead-Reckoning

```
Input: NormalizedThreat, speed, timestamp
Output: LatLng? (null when not flying or no heading)

Only for flying threats (bearingDeg + confirmedAtMillis + active).
Advances from confirmedAtMillis along motionHeading() at speed.
Capped by ThreatProps.horizonSec and ThreatProps.maxGhostMeters.
```

### `motionHeading(threat)` — Unified Heading

```
Priority chain:
  1. bearingDeg (server's authoritative velocity bearing)
  2. heading (reported heading)
  3. measuredHeading (from speed cache fix track)

Used for BOTH dead-reckoning AND icon facing. They must always agree.
```

### `distanceHaversine(lat1, lon1, lat2, lon2)` — Accurate Distance

```
Haversine formula. Replaces equirectangular approximation.
Used for all distance calculations in the engine.
```

### `etaMinutes(distKm, speedKmh)` — Time to Focus

```
Return: distKm / speedKmh * 60.0
Null when speedKmh is null or <= 0.
```

### `scoreThreat(threat, distKm, eta, zoneParams, now)` — Per-Threat Score (0–10)

```
Multiplicative combination:
  BASE_SEVERITY[type] × distanceFactor × reliabilityFactor × confirmFactor
  × countFactor × qualityFactor × staleFactor × etaFactor

distanceFactor: 1.0 in red, 0.65 in yellow, 0.0 beyond
reliabilityFactor: HIGH=1.0, MEDIUM=0.8, UNKNOWN=0.7, LOW=0.5
confirmFactor: 1.0 + 0.15 × min(confirmations-1, 6)
countFactor: 1.0 + 0.1 × min(count-1, 8)
qualityFactor: positionQuality + uncertaintyKm
staleFactor: decays 1.0→0.4 as fix ages
etaFactor: 1.0 for <1min, down to 0.7 for >15min
```

### `aggregateScores(scores)` — Overall Threat Level (0–10)

```
Diminishing returns: top 3 scores × weights [1.0, 0.5, 0.25]
Clamped to 0–10.
```

### `officialAlertActive(alerts, token, city, scope)` — Siren Gate

```
Returns true when any alert covers the focus point.
scope=false → oblast-wide matching
scope=true  → oblast + city name matching (coversCity)
Falls back to oblast-wide when city name is unknown.
```

### `deriveReason(threats, alert, focus, lang)` — Human-Readable Reason

```
Finds the highest-scoring active threat in the alert's oblast.
Returns formatted reason string + threat ID.
```

### Staleness Lifecycle

```
isStale(threat, now):
  status == "stale" OR (type != AVIATION && updatedAtMillis > staleAfterMs)

isGhost(threat, now, props):
  AVIATION: updatedAtMillis > props.ghostCapMs (default 2h)
  Others: updatedAtMillis > props.staleAfterMs + props.ghostCapMs (default 5min + 15min)

Stale threats: shown dimmed on map, excluded from zones/alerts/scores.
Ghost threats: removed from map entirely.
```

### Speed Cache (Internal to Engine)

```
Per threat ID, keep last 4 GPS fixes.
estimateSpeed(id, threat):
  1. Server speed (threat.speedKmh) if >= 5.0 km/h → RECORDED
  2. Measured from consecutive fixes (2+ fixes, 2–600s span) → RECORDED
  3. Trail-based (from threat.trail) → RECORDED
  4. Nominal from ThreatProps.nominalSpeedMps → TYPICAL
  5. null (no dead-reckon possible)

Thread-safe. Owned by engine. Not a global singleton.
```

## Non-Negotiable Constraints

1. **Single evaluation logic.** UI and service consume identical outputs from one engine.
   No duplicated zone/tier/prediction logic.
2. **Pure & deterministic.** Explicit inputs, no hidden state, no async delays in core
   functions. Given the same inputs, always produces the same outputs.
3. **Source-agnostic.** Engine works with `NormalizedThreat` and `ThreatProps`. Never
   touches source-specific formats (NEPTUN JSON, etc.).
4. **Plugin-provided type properties.** Engine defaults exist but plugins override.
   New threat types work without engine changes.
5. **Haversine for distance.** All distance calculations use Haversine, not
   equirectangular.
6. **Motion heading is shared.** Dead-reckoning and icon facing use the same heading
   resolution. They must never disagree.
7. **Slow tier uses raw fix.** Distance for slow threats is from the confirmed raw fix,
   not the predicted position.
8. **Fast tier uses predicted position.** Distance for fast threats is from the
   dead-reckoned position.
9. **AVIATION always INNER within reach.** MiG-31K takeoff = country-wide warning.
   Only opt-out is the type's bell toggle.
10. **Advisory/areaOnly never tier.** These are informational only.
11. **Dark-only theme.** No light theme. Theme is a plugin interface; only dark ships.
12. **Zero UI regressions.** Existing Compose UI, map markers, cards, settings must
    receive data in their expected format without breaking changes.
13. **Thread-safe speed cache.** Handles concurrent access from Main and IO.
14. **Explicit `now` parameter.** All time-dependent functions take an explicit timestamp.
    Enables deterministic testing.

## Consumer Behaviors (Reference)

These are NOT engine concerns but must be preserved in the consumer layer.

### Alert Service

| Behavior | Trigger | Notes |
|---|---|---|
| Zone siren | Threat enters armed zone tier | 20s grace, coalescing |
| Official siren | `officialAlertActiveFor()` true | Region-latched, persists across restart |
| All-clear | Official alert ends for focus region | Only fires for the region that was ringing |
| Offline critical | Connection lost >5 min | Forced notification regardless of settings |
| Offline bypass silent | Sub-toggle of offline critical | Plays sound in silent mode |
| Night siren overrides | Night window active | Separate zone + official override flags |
| Resolved tally | Threat removed from stream | Scoped to focus oblast or all-Ukraine |

### UI

| Behavior | Trigger | Notes |
|---|---|---|
| Threat card popup | Tap threat on map | Small chip / large card variants |
| Flourish ejection | Red alert or modal covers map | First 3 show hint toast |
| Settings gear hint | First 3 screen loads | Pulsing gear icon |
| Threat toggle hint | First 3 per-type toggles | Toast explaining map vs alert |
| Feature explainers | First toggle of 6 advanced settings | One-shot dialogs |
| Disclaimer auto-expand | Read count < 3 | Expands on each show |
| Follow bullet | Death animation sub-toggle | Bullet trail before explosion |
| Flyby animation | INNER-tier AVIATION | Random bearing, engine sound |
| Neutralized tally | Threat removed | Persistent notification, tap replays |
| Tally-tap replay | Tap tally notification | Groups threats spatially, replays deaths |

### Flourish

| Behavior | Trigger | Gate |
|---|---|---|
| Death animation (live) | Selected threat removed | `deathAnimationEnabled`, `FlourishPolicy` |
| Death animation (replay) | Tally notification tap | `deathAnimationEnabled`, map visible |
| Aviation flyby (auto) | INNER-tier AVIATION takeoff | `flybyAnimationEnabled`, map visible, app foreground |
| Aviation flyby (manual) | Notification tap on AVIATION | `flybyAnimationEnabled` |
| Strike camera follow | Each live strike | `followBullet`, replay not active |
| Strike haptics | Each bullet | Vibrator available |
| Tally notification | `removedThreats` in service | `neutralizedTallyEnabled`, oblast filter |

### Logging (Observational Only)

| System | Persisted | Retention | Purpose |
|---|---|---|---|
| DebugLog | Yes | 500 / 24h | Alert decision audit trail |
| ConnectionLog | Yes | 50 episodes | ONLINE/OFFLINE/DEGRADED episodes |
| ApiMonitor | Yes | 100 / 7d | SDK changes, malformed frames, unknown types |
| ConnEvent | No | Current episode | Offline milestones, retry scheduling |

No log feeds back into engine evaluation. All logging is write-only.

## Plugin Architecture

### ThreatSource Interface

```kotlin
interface ThreatSource {
    val name: String
    val typeCatalog: Map<String, ThreatProps>
    fun connect()
    fun disconnect()
    val threats: Flow<List<NormalizedThreat>>
    val alerts: Flow<List<OblastAlert>>
    val connectionState: Flow<ConnectionState>
    val supportsOfficialAlerts: Boolean get() = false
}
```

### ThemePlugin Interface

```kotlin
interface ThemePlugin {
    val name: String
    val isDark: Boolean
    val colors: ColorScheme
    val typography: Typography
}
```

Only `DarkThemePlugin` ships. Architecture supports adding light/other themes later.
Implemented in `theme/ThemePlugin.kt` + `theme/DarkThemePlugin.kt`; `MainActivity` uses
`DarkThemePlugin.colors`.

### Plugin Registry

```kotlin
object PluginRegistry {
    fun register(source: ThreatSource)
    fun get(name: String): ThreatSource?
    fun active(): ThreatSource?
}
```

## File Map (Current → New) — completed

| Current File | Responsibility | New Location (actual) |
|---|---|---|
| `Zones.kt` | `zoneTier`, `reachKm`, `etaMinutes`, `ZoneParams` | `engine/ThreatEngine.kt` |
| `Prediction.kt` | `predictPosition`, `motionHeading`, `distanceMeters`, `ThreatSpeedTracker` | `engine/ThreatEngine.kt` + `engine/SpeedCache.kt` |
| `ThreatLevel.kt` | `scoreOf`, `overall` | `engine/ThreatEngine.kt` |
| `ThreatEvaluator.kt` | `evaluate`, `zoneThreats`, `buildOfficialReason` | `engine/ThreatEngine.kt` + `engine/OblastUtils.kt` (geographic utils) |
| `Threat.kt` | `Threat` data class, JSON parsing | kept as the UI display type; bridged to `engine/NormalizedThreat.kt` via `engine/TypeMapping.kt` |
| `NeptunConnectionClient.kt` | WebSocket, REST merge | wrapped by `plugins/NeptunPlugin.kt` |
| `NightMode.kt` | Night window, effective params | kept in `domain/NightMode.kt` (app-layer, no engine equivalent) |
| `Cities.kt` | Focus attribution, city labels | kept in `domain/Cities.kt` (app-layer, no engine equivalent) |

## Session Status

- [x] Session 1: BEHAVIORS.md (this document)
- [x] Session 2: Engine kernel (engine/*, 44 tests passing)
- [x] Session 3: Plugin system (ThreatSource, NeptunPlugin, PluginRegistry, TypeMapping, 54 tests passing)
- [x] Session 4: UI refactor (AppPluginHolder, reverse mapper, MainViewModel + AlertService wired to registry, 54 tests passing)
- [x] Session 5: Cleanup (test harness / TEMP toggles removed; `ThemePlugin` + `DarkThemePlugin` implemented in `theme/`; EN-only strings convention)
- [x] Session 6: UI currency = `NormalizedThreat` (`Threat` display DTO + `toThreat()` reverse mapper deleted; `NeptunConnectionClient` emits `Map<String, NormalizedThreat>`; `Compat.kt` deleted; all consumers import `engine.LatLng/ThreatZone/ZoneParams` directly; `ThreatTypeCatalog.INFO[threat.type]` → `threatTypeInfoByString`; 197 tests passing)
