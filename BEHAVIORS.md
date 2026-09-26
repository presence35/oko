# Behaviors — Threat Evaluation Engine

Source of truth for engine contract. Read before any engine work. If you change a
behavior here, update the implementation in the same change.

## Inputs

| Input | Type | Description |
|---|---|---|
| Threat stream | `List<NormalizedThreat>` | Source-agnostic threat objects (see Threat Model) |
| Official alerts | `List<OblastAlert>` | Regional alert feed (source-agnostic alert currency, `engine/OblastAlert.kt`) |
| Alert focus | `focusToken: String?`, `focusCityUa: String?`, `cityScope: Boolean` | Which canonical oblast id / city the official-alert gate scopes to |
| Focus state | `LatLng`, `FocusCity?`, `hasGps: Boolean` | Where to center evaluation |
| Zone params | `ZoneParams` (day/night variants) | User thresholds + armed bells |
| Type gates | `hiddenTypes: Set<String>`, `silencedTypes: Set<String>` | Per-source filtering |
| Night config | `NightConfig` | Window + overrides |
| Language | `AppLanguage` | UA/EN/RU for reason text |
| `now` | `Long` | Explicit timestamp (deterministic evaluation, testable) |

## Outputs

| Output | Type | Description |
|---|---|---|
| Zone groups | `threatsInner`, `threatsOuter`, `activeZone`, `zoneThreats` | Tier classification |
| Map threats | `List<NormalizedThreat>` (raw fixes) | Display-ready, ghosts excluded. Prediction is applied downstream: consumers glide via `predictPosition` on their own tick (MapView's 1s marker loop, popup proximity) |
| Red cities | `Set<String>` | Cities under official alert (region-precise; scope-independent, labels light nationwide) |
| Official alert | `focusOblastAlertActive: Boolean`, `focusOblastYellowAlertActive: Boolean`, `officialReason: String?`, `reasonThreatId: String?` | Siren/latch state (red) + yellow flag + attribution, owned by the engine |
| Threat level | `Double` (0–10) | Aggregate gauge |
| Proximity | `ThreatProximity?` (per selected threat) | Distance, ETA, speed source |

The service's **raw** (scope=false) gate for the all-clear latch is the same engine function
`officialAlertActiveFor(alerts, token, null, scope = false)` — consumers never re-implement matching.

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
    val sourceMeta: Map<String, Any> = emptyMap(),  // opaque to engine
    val simulated: Boolean = false  // Test-source watermark; engine never branches on it
)

data class TrailPoint(val lat: Double, val lon: Double, val tMillis: Long?)
```

`flying` is derived: `(bearingDeg != null || heading != null) && (confirmedAtMillis != null || updatedAtMillis != null) && status == "active"`.
Movement additionally needs a resolvable course + speed; `ThreatEngine.canDrift(t, props, now) =
!isStale(...) && t.flying` is the single gate both the map glide and [predictPosition](#predictpositionthreat-speedmps-now--dead-reckoning)
use — dead-reckoning only ever moves a **fresh** track whose **source** reported a course
(never a client-measured one), so a plugin's own movement model is never overridden and a stale
or quiet track holds its last reported fix.

## Type Properties (Plugin-Provided)

Plugins define per-type behavior via a properties bag. Engine uses these; it never
references type names directly.

```kotlin
data class ThreatProps(
    val isFast: Boolean,           // tier by ETA (true) vs distance (false)
    val reachKm: Double,           // max engagement range
    val alwaysInnerWithinReach: Boolean, // inside reachKm always rings INNER (aviation's country-wide warning)
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
    alwaysInnerWithinReach = false,
    staleAfterMs = 300_000L,       // 5 min
    ghostCapMs = 900_000L,         // 15 min
    nominalSpeedMps = null,         // no dead-reckon without real velocity
    horizonSec = 300.0,
    maxGhostMeters = 18_000.0,
)
```

### Source-Owned Type Catalogs (NEPTUN values live in NeptunSource)

Per-type VALUES are owned by their source, never by the engine. The engine owns only the
`ThreatProps` struct (shape) plus `DEFAULT_THREAT_PROPS` (fallback for types no source
defines). NEPTUN's catalog lives in `source/NeptunSource.kt` (`NEPTUN_TYPES`, exposed as
`Source.typeCatalog`) with the values NEPTUN sends, and reaches every consumer merged via
`SourceRegistry.typeCatalog`:

```
Source.typeCatalog → SourceRegistry.typeCatalog → ThreatEngine(catalog) → propsFor(type)
```

RULES (violations broke the build's layering before — do not reintroduce):
- No file outside its owning `Source` implementation may import or name a concrete
  catalog (`NEPTUN_TYPES` or any successor). Only `NeptunSource` references `NEPTUN_TYPES`.
- `engine/`, UI, service, widget and domain code take props ONLY via an injected catalog
  (`ThreatEngine(catalog)`, `engine.propsFor(type)`, `registry.typeCatalog`, or an explicit
  `typeCatalog`/`catalog` parameter). Static per-type lookups are a violation.
- Pure helpers (`computeWidgetSnapshot`, `computeSweep`, `isFastType`, `typicalSpeedKmh`)
  take the catalog as an explicit parameter, defaulting to empty (which falls back to
  `DEFAULT_THREAT_PROPS`) — never a hardcoded source map.

## Behaviors (Pure Functions)

All functions take explicit inputs. No hidden state. No side effects. Deterministic
given the same inputs.

### `evaluate(...)` — Main Evaluation Pass

```
Input: threats, focus, params, hiddenTypes, silencedTypes, now, alerts, focusToken, focusCityUa, cityScope, lang
Output: EvaluationResult

For each threat:
  1. Skip resolved or ghost (isGhost check)
  2. Skip hidden types (not on map at all)
  3. Record fix in speed cache (if not stale)
  4. Compute predicted position (if flying)
  5. Add to mapThreats (all visible threats, raw fixes)
  6. Skip stale, advisory, areaOnly, no focus → no zone evaluation
  7. Compute distance from the predicted position (Haversine)
  8. Compute speed (server > measured > nominal from ThreatProps)
  9. Call zoneTier() + holdTier()
  10. If in-zone and inside the active official alert's oblast → reason candidate (nearest wins);
      hidden types never reach here, silenced ones stay eligible (attribution, not a re-alert)
  11. Silenced types stop here: attributed above, but never tiered into zones or scored
  12. If tiered: compute score, add to zoneThreatsMap, categorize inner/outer
  13. Return EvaluationResult

Official-alert facts (engine-owned, computed before/within the same pass):
  14. focusOblastAlertActive / focusOblastYellowAlertActive = officialStateFor(alerts, focusToken, focusCityUa, cityScope)
  15. cityAlerts = computeCityAlerts(alerts) — region-precise labels (redCities is its RED projection)
  16. officialReason/reasonThreatId = the nearest candidate, else the region name, else null (no focus/token)
```

### `zoneTier(props, distKm, speedKmh, params)` — Zone Classification

```
Input: ThreatProps, distance, speed, zone params
Output: ThreatZone? (INNER | OUTER | null)

Rules:
  1. distKm > props.reachKm → null (out of range)
  2. props.alwaysInnerWithinReach → always INNER (aviation's country-wide warning; flag is
     plugin-provided via ThreatProps, not hardcoded to the type name)
  3. props.isFast → tier by ETA (etaMinutes → fastRedMin/fastYellowMin)
  4. !props.isFast → tier by distance (slowRedKm/slowYellowKm)
```

### `predictPosition(threat, speedMps, now)` — Dead-Reckoning

```
Input: NormalizedThreat, speed, timestamp
Output: LatLng? (null unless the track is fresh AND server-coursed)

Gates (canDrift = !isStale && flying):
  1. isStale(threat, props, now) → null (stale tracks hold their fix)
  2. no server course (bearingDeg == null && heading == null) → null (the track is
     never made to move on a client-measured heading; plugin model is respected)
  3. no anchor (updatedAtMillis ?: confirmedAtMillis) → null

Advances from the LATEST fix anchor (updatedAt, or confirmedAt when updatedAt is missing)
along bearingDeg ?: heading at speed.
Capped by ThreatProps.horizonSec and ThreatProps.maxGhostMeters.
```

### `motionHeading(threat)` — Unified Heading (icon facing)

```
Priority chain:
  1. bearingDeg (server's authoritative velocity bearing)
  2. heading (reported heading)
  3. measuredHeading (from speed cache fix track)

Used for icon facing (courseDeg) and the map's measured fallback — the dead-reckon in
predictPosition uses only the server-reported course (steps 1-2).
```

### `canDrift(threat, props, now)` — Dead-Reckon Gate

```
The single shared gate for whether a track may drift between server fixes:
  !isStale(threat, props, now) && threat.flying
Used by the map glide loop, marker placement, and predictPosition itself, so stale tracks
freeze at their reported fix and tracks NEPTUN gives no course never move.
```

### `isInbound(t, focus, params, props, now)` — Approach Predicate (consumer policy)

```
Source data only, no timers. True when the track is an approximate (`positionQuality ==
"approx"`), fresh, active track whose source-named `destination` (resolved from the course
text) lands inside the focus's yellow ring (`slowYellowKm`). A bare course-bearing is not
enough — fast types reach 1500 km and would otherwise stage from anywhere in the country.
Advisory/area-only/stale tracks never qualify. Lives in `domain/ThreatBehavior.kt`.
```

### `stageInbound(eval, threats, focus, params, propsFor, now, silencedTypes)` — Ring Staging

```
Pure re-tag of one engine result, applied by BOTH consumers (MainViewModel, AlertService) so
the map, card and siren agree:
  - slow inbound → OUTER (yellow),
  - fast inbound → INNER (still sounds red).
Both ride the yellow ring; the fast track's alarm stays red while its marker reads "approaching".
Rewrites zoneThreats, threatsInner/Outer and activeZone; no-op when nothing is inbound. Only
tracks the engine already placed in a zone are staged, so a far out-of-range track never gets a
synthetic alarm. The engine itself stays a pure reporter of source data.

`OrbitBehavior` places an inbound track on the yellow ring around its destination instead of
parking it on the city centre. It only applies when the raw fix is already within `slowRedKm`
of the destination (the "would land on us" case) — farther tracks keep their normal drift.
The widget does not stage yet (see ROADMAP.md).
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

### `scoreThreat(threat, props, distKm, etaMin, redKm, yellowKm, now)` — Per-Threat Score (0–10)

```
Multiplicative combination:
  props.baseSeverity × distanceFactor × reliabilityFactor × confirmFactor
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

### `officialAlertActiveFor(alerts, token, cityUa, scope)` — Siren Gate

```
Engine gate (engine/OblastAlert.kt). Returns true when any alert covers the focus point.
Matching is by canonical region identity only — never letter stems, 4-char prefixes or
substrings. `focusToken` is a canonical oblast boundary id (e.g. "odeska"); the alert's own
region is resolved to one via `CompactOblastBoundaries.canonicalId(oblast ?: key ?: name)`.
scope=false → oblast-wide matching (`OblastAlert.inOblast`)
scope=true  → oblast + city coverage (`OblastAlert.coversCity`): same oblast, then the city's
              registered canonical raion key, or an exact full-name match for a bare city alert.
Crimea and Sevastopol share one coverage group (`sameAlertRegion`); Kyiv City is merged into
`kyivska`. Falls back to oblast-wide when the city name is unknown.
The red siren flag (`focusOblastAlertActive`, level "red") and the yellow flag
(`focusOblastYellowAlertActive`, level "yellow") are derived with the same scoping: the red
flag through `officialAlertActiveFor`, the yellow flag through its twin
`officialYellowAlertActiveFor`, both in `ThreatEngine.evaluate()`. Red wins over yellow in
consumers' UI priority; the flags stay independent so messaging can be per-level. Consumers
never re-implement this matching. Night mode overrides scope: when the night window is
active and `nightOfficialAlertCityScope` is true, city-level scoping applies at night
regardless of the day setting.
```

### `deriveOfficialAlertReason(alert, threats, focus, params, lang, now)` — Human-Readable Reason

```
ThreatEngine method. Finds the nearest active threat in the alert's oblast that falls
inside the user's configured zones; falls back to the region name when nothing is in range
or there is no focus point. Hidden types are never named; silenced types are, because
naming the cause of a siren that is already ringing is attribution, not a re-alert.
`evaluate` applies the same rule during its single pass (`reasonEligible`); this method is
for consumers deriving a reason for a region other than the evaluate focus (AlertService's
latched episode). The region name renders in the app language (UA raw, EN transliterated,
RU a real Russian form).
Returns (formatted reason string, threat ID).
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

  Exception: the national MiG-31K track (`isNationalMig`) always estimates null — its pin
  is a country centroid, not a position, so no speed/ETA may be derived from it.
  Null speed hides the card's speed and ETA pills; zone tiering is unaffected
  (`alwaysInnerWithinReach` still rings INNER).

Thread-safe. Owned by engine. Not a global singleton.
```

## Non-Negotiable Constraints

1. **Single evaluation logic.** UI and service consume identical outputs from one engine.
   No duplicated zone/tier/prediction logic.
2. **Pure & deterministic.** Explicit inputs, no hidden state, no async delays in core
   functions. Given the same inputs, always produces the same outputs.
3. **Source-agnostic.** Engine works with `NormalizedThreat` and `ThreatProps`. Never
    touches source-specific formats (NEPTUN JSON, etc.) and never names a concrete source
    catalog — per-type values arrive only via the injected `ThreatEngine(catalog)`.
4. **Source-owned type properties.** The engine owns the `ThreatProps` struct +
    `DEFAULT_THREAT_PROPS` fallback; every per-type value lives in its `Source`
    implementation and flows `Source.typeCatalog → SourceRegistry.typeCatalog`.
    New threat types work without engine changes; new sources ship their own catalog.
5. **Haversine for distance.** All distance calculations use Haversine, not
   equirectangular.
6. **Motion heading is shared — but only server-reported course drives drift.** Icon facing
   (`courseDeg`/`motionHeading`) may use the measured fix-track heading as a last resort;
   dead-reckoning (`predictPosition`) uses only the server-reported course (`bearingDeg`/`heading`)
   and is gated on `canDrift`, so a marker never moves along a direction the source never gave it.
7. **Zone distance uses predicted position for all types.** Both fast and slow threats tier
   based on the dead-reckoned (predicted) position, so the zone label always matches the map
   icon position — whatever the active plugin's prediction model shows is what the user sees.
8. **`alwaysInnerWithinReach` types always ring INNER within reach.** MiG-31K takeoff = country-wide warning; the flag comes from `ThreatProps` (aviation sets it), so no type name is hardcoded in the engine. Only opt-out is the type's bell toggle.
9. **Advisory/areaOnly never tier.** These are informational only.
10. **Dark-only theme.** No light theme. Theme is a plugin interface; only dark ships.
11. **Zero UI regressions.** Existing Compose UI, map markers, cards, settings must
     receive data in their expected format without breaking changes.
12. **Thread-safe speed cache.** Handles concurrent access from Main and IO.
13. **Explicit `now` parameter.** All time-dependent functions take an explicit timestamp.
     Enables deterministic testing.
14. **Official-alert evaluation is engine-owned.** The gate, red-city labels and the
    reason all derive in `ThreatEngine.evaluate` / `engine/OblastAlert.kt`; consumers only
    orchestrate (region latch, announce-once, sound policy) and read the facts. The widget
    and the notification service consume the same `focusOblastAlertActive` /
    `focusOblastYellowAlertActive` facts (`officialAlertActiveFor` /
    `officialYellowAlertActiveFor`) — no third implementation anywhere.

## Consumer Behaviors (Reference)

These are NOT engine concerns but must be preserved in the consumer layer.

### Alert Service

| Behavior | Trigger | Notes |
|---|---|---|
| Zone siren | Plugin verdict SOUND for the winning threat | Frequency preset, floor, digest (below) |
| Zone silent update | Verdict SILENT (downgrade, steady, winner-switch) | Content refresh, no sound |
| Official siren | `officialAlertActiveFor()` true | Region-latched, persists across restart |
| All-clear | Raw official episode ends for the latched focus region | One clear per episode: red, yellow, or red-then-yellow; never more than one. Keyed on the raw ending, so a mid-episode scope drop (alert narrowed away from your city) still gets its all-clear |
| Offline critical | Offline 5 min, or 1 min while an official alert (red/yellow) is active on the focus oblast | Once per episode, honors the critical-offline toggle; milestone emission (SourceRegistry `degradedSince` + `connectionMilestones`) vs notification (service) |
| Offline bypass silent | Sub-toggle of offline critical | Plays sound in silent mode |
| Night siren overrides | Night window active | Separate zone + official override flags |
| Sleep mode ("Just let me sleep!") | Night toggles all off (preset) | Zone + official alerts muted by the prefs themselves; no service gate |
| Resolved tally | Threat removed from stream | Scoped to focus oblast or all-Ukraine |

### Notification policy (NotifyPlugin — frequency, not capability)

- Capability ("can it ever sound": armed bells, official toggles, per-type enables) is separate from frequency ("how often": the preset). The service executes verdicts; all judgment lives in the plugin.
- Tiers carry a 10% spatial hysteresis band (`ZONE_HYSTERESIS_MARGIN`): upgrades immediate, downgrades/exits hold. Shared by map + service.
- An episode opens on first zone sighting and closes only when the track dies (stale / resolved / gone). Flicker ticks never close it, so they never re-sound. A user-shot same-id respawn inside the grace is the same kill, never a new onset. ONCE_PER_TYPE memory is per-type per sitting on top of this: a sounded type stays gated across id flicker/re-keys and re-arms only when no live threat of that type remains.
- Floor (inside every preset, never a service bypass): escalation to INNER is a gated
  opportunity. ONCE_PER_THREAT sounds the first INNER per threat (per-threat escalation);
  ONCE_PER_TYPE and DIGEST respect their gates (no preset-bypass re-sound).
- Presets: Every change (entries + re-entries + escalations sound) / Once per threat / Once per type (until the sky is clear of it) / Digest (max N sounds per window: off, 2/10/60 min, or per alarm sitting; counted per type or across all; default 10/sitting/all). The master toggle gates the whole policy: off = Every change (the default), presets only apply while on.
- Every swallowed sound is logged with its policy reason (RATE_LIMITED / ONCE_PER_THREAT / ONCE_PER_TYPE). Preset switch = fresh start; digest tweaks clear only rate buckets.
- Official alerts keep onset-always semantics outside the presets (byte-for-byte prior behavior).
- Boundary rule (load-bearing): the service transports policy inputs and executes verdicts — it never interprets them. Every user-facing pref (including meta-switches like "policy off") is mapped to plugin semantics inside plugin-owned code (`NotifyPrefs.from`), never with an `if` in `AlertService`. Test for violations: a behavior change that requires touching `AlertService` means the boundary is wrong — move the judgment into the plugin. The gated file list and the exception process live under "Core gate" in `ARCHITECTURE.md` key invariants.

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
| Press haptics | Any press (buttons, rows, switches, chips, tabs) | Raw-Vibrator tick, gated on the haptics pref |

### Flourish

| Behavior | Trigger | Gate |
|---|---|---|
| Death animation (live) | Selected threat removed | `deathAnimationEnabled`, `FlourishPolicy`, on-screen or `followBullet` |
| Death animation (replay) | Tally notification tap | `deathAnimationEnabled`, map visible |
| Aviation flyby (auto) | INNER-tier AVIATION takeoff | `flybyAnimationEnabled`, map visible, app foreground |
| Aviation flyby (manual) | Notification tap on AVIATION | `flybyAnimationEnabled` |
| Strike camera follow | Each live strike | `followBullet`, replay not active |
| Strike haptics | Each bullet | Vibrator available |
| Tally notification | `removedThreats` in service | `neutralizedTallyEnabled`, oblast filter |
| Auto shoot-down skip | Threat removed off-screen while `followBullet` off | Skipped — no countdown, no strike |

### Logging (Observational Only)

| System | Persisted | Retention | Purpose |
|---|---|---|---|
| DebugLog | Yes | 500 / 24h | Alert decision audit trail |
| ConnectionLog | Yes | 50 episodes | ONLINE/OFFLINE/DEGRADED episodes |
| ApiMonitor | Yes | 100 / 7d | SDK changes, malformed frames, unknown types |
| ConnEvent | No | Current episode | Offline milestones, retry scheduling |

No log feeds back into engine evaluation. All logging is write-only.

## Plugin contract

The `Source` interface, `SourceRegistry` health authority and theme plugin contract are
documented in `ARCHITECTURE.md` (module map + "Official alert sources" invariant). This doc is
the behavioral contract only: engine + consumers. The interface has grown beyond the original
sketch (`id`, `sourceType`, `operationalMode`, `enabled`/`setEnabled`, `testConnection`, and the
registry's takeover merge) — see the code for the current shape.

## Status

The engine/plugin/UI refactor (original "Sessions 1–7" plan) is complete and squashed onto
`main`. This document is the living behavioral contract — update it alongside any engine
behavior change.
