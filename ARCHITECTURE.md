?# Architecture — Ukraine Drones

Technical map of the codebase. Read this before exploring so you can jump straight to the
file(s) you need instead of re-deriving the structure. Keep it current: if you add a file or
change a documented invariant, update the relevant section.

## Quick facts

- Single-module Android app (`:app`) — a live air-threat map for Ukraine.
- Jetpack Compose (Material 3, dark-only) + MapLibre Native SDK (OpenGL/Vulkan hardware-accelerated raster & vector tiles). Kotlin 1.9.24, JDK 17, minSdk 26 /
  targetSdk 35, namespace `com.presaince.oko`.
- No runtime backend of ours: data comes straight from the public
  [NEPTUN](https://neptun.in.ua) API (WebSocket stream). No Firebase, no push.
- Update feed: static `version.json` + APK on `odesaplay.com.ua`, self-checked daily, in-app install is temporary while in beta mode, eventually it will be deprecated for official Google Play route.
- Coroutines + flows throughout; singletons expose `StateFlow`s.

## Package structure

Source files are grouped into subdirectories by subsystem (`data/ domain/ engine/ source/
connection/ service/ ui/ widget/ flourish/ theme/ lang/`). The UI/domain/widget/flourish/lang
layers share the root package `com.presaince.oko`; the subsystems keep their own packages
(`com.presaince.oko.engine`, `.source`, `.connection`, `.service`, `.theme`, `.data`) where
isolation matters (the engine kernel, the source SPI, the connection layer). There is no
single flat package — sub-packages are already the norm; keep adding files inside their
subsystem's package rather than the root.

## System overview

The UI and the background service are **independent consumers of shared inputs** — both
re-derive state from the same singletons, never from each other.

```
                    ┌──────────────────────────┐
  NEPTUN WS ────────►│      NeptunSource        │
  (OS network gating) ──►│ (ResilientConnectionSupervisor + │
                     │   NeptunRawDecoder + MonitorCoreImpl) │
                    └──────────┬───────────────┘
                               │ Source SPI
                    ┌──────────▼───────────────┐
                    │      SourceRegistry      │  AppSources (composition root)
                    └──────────┬───────────────┘
                               │ allThreats / allAlerts / health
                 ┌─────────────┴─────────────┐
                 ▼                           ▼
          MainViewModel                AlertService
                 │                           │
                 ▼                           ▼
            Compose UI                 Notifications
```

```
UserPrefs ────────┬──► MainViewModel        Shared logic (call, don't duplicate):
                  └──► AlertService         engine/ThreatEngine.kt (evaluate, predictPosition)
LocationTracker ──┬──► MainViewModel        NightMode.kt / Cities.kt (resolveFocus)
                  └──► AlertService
```

## Core ownership

| Concern | Source of truth | Consumers |
| --- | --- | --- |
| NEPTUN connection | `NeptunSource` (transport + decoder + supervisor) | UI, `AlertService` (via `SourceRegistry`) |
| Connection state machine | `ConnectionState` sealed interface (transport) + `SourceState` (consumer SPI) | UI, `AlertService`, widget |
| Network validation | OS `ConnectivityManager` callback inside `ResilientConnectionSupervisor` | supervisor reconnect machine |
| Official oblast alerts | `SourceRegistry.allAlerts` (priority-ordered sources, takeover merge; primary = `NeptunSource.alerts`; no REST fallback — `UbillingPlugin` removed 2026-09 for 1–2d stale sentinel data); **derivation** (`officialAlertActiveFor`, `redCities`, `raionName`, reason) owned by `engine/` | UI, `AlertService`, widget |
| Threat prediction | `engine/ThreatEngine.kt` | ViewModel, service, MapView, widget |
| Zone tier math | `engine/ThreatEngine.kt` | ViewModel, service |
| Night rule resolution | `NightMode.kt` | ViewModel, service |
| User preferences | `UserPrefs` | all |
| Persisted operational state (conn log, offline-restore, official-announced episode identity, debug/system log, reconnect stamps, update-check state) | `ServiceState` | `ConnectionLog`, `DebugLog`, `AlertService` |
| UI orchestration | `MainViewModel` | Compose |
| Background monitoring | `AlertService` | notifications |
| Connection history | `ConnectionLog` | system status, Logs screen |
| Decision audit | `DebugLog` | Logs screen |
| Map rendering | `MapView.kt` | Compose |
| Threat icons | `IconCatalog.kt` | UI |

## Data flow

- **Threat ingest.** NEPTUN WS in `NeptunSource` (`ResilientConnectionSupervisor` socket → `NeptunRawDecoder` frames → `MonitorCoreImpl` state) → separate `StateFlow`s: `connectionState`, `threats`, `alerts`; `removedThreats` SharedFlow for map death animations. `SourceRegistry` merges source feeds into `allThreats`/`allAlerts` (takeover semantics); consumers read the registry, never a specific source. Consumers read flows directly — no intermediate `NeptunState`. NEPTUN's alerts carry the whole-oblast/region split: each `OblastAlert` is tagged `wide` from the `raions`/`oblasts` arrays, so map coloring is region-precise (fill = wide only; city labels = `coversCity`) instead of guessing from the name.
- **Position prediction** (both consumers): the full contract — `SpeedCache` speed priority,
  `predictPosition` dead-reckoning gates (`canDrift` = fresh + `flying`, server-coursed only),
  `motionHeading`/`courseDeg` facing chain, per-type horizon/ghost caps, slow-distance vs
  fast-ETA tiering — is the **engine behavioral contract** in `BEHAVIORS.md`; `ThreatEngineTest`
  pins it. App-side facts: MapView glides markers on its own 1s tick (30fps tween); the service
  clears on a 20s grace.
- **Update flow.** `UpdateManager.check()` → `Available` → `download()` (progress) →
  `buildInstallIntent()` → system installer. `AlertService` also checks silently every day at
  16:20 while it runs and posts one "new version available" notification per new build
  (deduped by `last_notified_update_code`); tapping it re-opens the app and pops the update dialog.

## Module map

Grouped by subsystem. Every file with its responsibility; a terse *Note:* flags implementation
detail that matters when editing that file.

### App entry / theme

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Single activity; dark theme (via `DarkThemePlugin`); starts `AlertService`; legacy osmdroid cache cleanup. *Note:* location→notification permissions defer until first-run onboarding resolves; "Later" sets `permission_prompt_deferred` (re-armed each cold start). |
| `theme/ThemePlugin.kt` | Theme plugin contract (`name`, `isDark`, `colors`, `typography`). |
| `theme/DarkThemePlugin.kt` | The only shipped theme (dark-only); `MainActivity` applies `DarkThemePlugin.colors`. |
| `theme/AppPalette.kt` | The single source of truth for every color in the app (`object`). Shipping code never hardcodes color literals — it references a token (Compose: `Color(AppPalette.X)`, canvas/MapLibre/service: `AppPalette.X.toInt()`). `DarkThemePlugin` derives its `darkColorScheme` from these tokens. |

### Data ingress (NEPTUN)

| File | Responsibility |
| --- | --- |
| `connection/ConnectionState.kt` | Sealed interface state machine: `Disconnected` → `Connecting` → `Connected` → `Degraded` → `Offline`; `ConnectionMilestone` (M3/M5_CRITICAL/M6/M10/M20_GAVE_UP, once per episode, **owned by `SourceRegistry`**); convenience extensions `isConnected`, `isDegraded`, `isOffline`, `offlineSinceOrNull`, `reconnectStartMillisOrZero`. Transport-internal (not the consumer SPI). |
| `connection/ResilientConnectionSupervisor.kt` | The only production connection supervisor — **transport-only**. OS network gating (no spin while offline, wake on validation), 42 s byte-silence watchdog, full-jitter backoff (`backoffDelayMs`, tested), single-generation socket lifecycle (only `executeConnect` mints; intentional closes claim the disconnect one-shot), and stuck-offline watchdog (force retry past max-backoff + timeout with a live network). Publishes raw `ConnectionState` (`reconnectStartMillis` is a plain wall stamp, no flap stitching) and `connEvents`; never computes episode age or milestones — that is `SourceRegistry`'s job. Owned by `NeptunSource`. |
| `AppSources.kt` | App-wide composition root (`object`): builds and owns the `SourceRegistry` with `NeptunSource` + the peace-time `TestSource`, exposes `registry`, `appForeground`/`setAppForeground`; `init(context)`/`clear()`. Replaces the old `ConnectionHolder`/`AppPluginHolder` singletons. |
| `Threat.kt` | NEPTUN display metadata + JSON parsing: `ThreatType`/`ThreatTypeCatalog`/`Reliability` (labels, staleness, nominal speeds), `translateCourseAssessment` (EN course text, word-level common-word translation), `normalizedThreatFromJson` — NEPTUN JSON → `NormalizedThreat` directly (the engine currency; no `Threat` display DTO). The alert currency + matching gates moved out to `engine/OblastAlert.kt`. |

### Source SPI (source-agnostic ingestion layer)

Consumers talk only to `SourceRegistry`; the source feed (WS socket, decoder, reconnects) stays
private inside each `Source`. Every source reports normalized engine currency
(`NormalizedThreat`/`OblastAlert`), never a source-specific format.

**Fundamental Ownership & Invariant: Source Owns Reality**
- The **Source** is the authoritative source of truth for raw tracks, reported fixes, course vectors,
  and type properties (including `staleAfterMs`, `ghostCapMs`, nominal speed, and reach).
- The **Engine** (`ThreatEngine`) and **Consumers** (UI, `AlertService`) are strictly downstream readers.
  They do NOT apply unauthorized track pruning, synthetic dead-reckoning caches, or custom timeouts
  that contradict the active Source's declared contract. A track lives as long as the Source and its
  catalog say it lives; the engine only evaluates zones/scores/staleness based on that catalog.

| File | Responsibility |
| --- | --- |
| `source/Source.kt` | The source SPI interface: `id`/`name`/`sourceType` (WS/REST), `operationalMode` (STREAMING/POLLING/STANDBY), `typeCatalog`, `threats`/`alerts`/`connectionState: StateFlow<SourceState>`/`enabled` + `setEnabled`, `testConnection()` (`SourceTestResult`), `removedThreats` SharedFlow, and default no-ops (`markUserShot`/`wasUserShotRecently`/`retryNow`/`onAppForeground`/`siteUrl`). Source-agnostic: no NEPTUN types in the contract. |
| `source/SourceState.kt` | `SourceState` enum (DISCONNECTED/CONNECTING/CONNECTED/DEGRADED/OFFLINE) — the consumer-visible connection state, replacing the old `PluginConnectionState`. |
| `source/SourceRegistry.kt` | Health authority over all sources; **every merge/health derivation reads `enabledSources` (registered sources the user switched on), never the raw registration list** — so a disabled source can't feed, own, or degrade anything. Merges threats/alerts with **takeover** semantics (authoritative source's snapshots are sole truth; stale holders fill only when nothing is authoritative); exposes `perSourceState`, `wsHealthy`, `degraded`, `degradedSince` (monotonic-stamped), `coveredByFallback`, `isOffline(now)`, `lastThreatUpdateAt` + `isThreatDataStale(now)` (**source-agnostic** staleness), `activeAlertSource`, aggregate `connectionState` (worst over enabled sources), merged `allThreats`/`allAlerts` + `typeCatalog`, a `sourceEvents` SharedFlow (toggles + alert-owner handovers) and **owns the offline-episode** (`degradedSince` + `connectionMilestones` — the 3/5/6/10/20 min + GAVE_UP milestones, emitted once per episode and gated on `isOffline(now)`). For alerts only a **CONNECTED** WS socket is authoritative — `DEGRADED` (quiet >30s) is stale data and falls back to the union-hold. Reconnect diagnostics (`connEvents`/`retryState`/`dismissLogCard`/`annotateConnectionLog`/`setActiveAlertSource`) delegate to the registered `ConnectionLogSource`; controls (`retryNow`/`onAppForeground`/`markUserShot`/`wasUserShotRecently`) fan out to enabled sources, and `annotateConnectionLog` (log-only, no connection effect) lets consumers record one-shot rows like the Ignore tap. `siteUrl` = branding link of the primary source. |
| `source/ConnectionLogSource.kt` | Optional `Source` capability: reports `connEvents`/`retryState` + reconnect-log card controls, so the registry can forward Logs-tab state from the WS source without knowing it. Milestones are owned by the registry, not the source. |
| `source/NeptunSource.kt` | The only production `Source` for launch. Composition root owning `ResilientConnectionSupervisor` + `NeptunRawDecoder` + `MonitorCoreImpl`; owns NEPTUN's per-type `NEPTUN_TYPES` catalog (values as NEPTUN sends them; exposed as `Source.typeCatalog`) — the ONLY file that may reference that map; `start(scope)` launches `supervisor.start()` and a connectionState collector (`mapConnectionState` + `decoder.handleTransportDrop()` on Offline) plus threat/alert mirrors; forwards reconnect controls and user-shot API. |
| `source/TestSource.kt` | Peace-time simulator (`sourceType = WS`, disabled by default). While enabled it fetches `testplugin.json` from the update server and plays a timed script of threat/alert events (movers, resolves, clears). Reports CONNECTED while running; `stop()`/disable clears its output exactly like a real source. |
| `source/ThreatRemoved.kt` | `ThreatRemoved` (map death animation + resolved tally currency) moved out of `connection/`; hosts `RESOLVED_REPLAY_GRACE_MS` (60 s). |

### State / orchestration

| File | Responsibility |
| --- | --- |
| `MainViewModel.kt` | `AndroidViewModel`. Combines NEPTUN + GPS + prefs (flourish data/policy live in `flourish/Flourish.kt` — `FlourishRecord`/`FlourishShow` and the `FlourishPolicy` gates used by `buildUiState`) → `StateFlow<UiState>`; drives the update flow (daily start check, a Settings-open check that raises `updateReminderTick` — a snackbar with a Download action — instead of a clickable toast, which Android can't make touchable, plus manual check/download/install); `neutralizeThreat` long-press hook. Reads the `AppSources.registry` flows (`allThreats`/`allAlerts`, health); `liveFeed = combine(threatsFlow, alertsFlow)` sampled at 120 ms for UI. All zone/tier/prediction and official-alert facts come from one `engine.evaluate(...)` (mirror rule); the UI keeps only display projections (`alertOblastIds`/`alertRaionKeys` for the red region fills — engine-derived via `computeFillKeys` as canonical boundary IDs, `alertingOblastCount` for the Logs header) plumbed from the engine's alert gates. No global wall-clock StateFlow — consumers with time-based UI run their own scoped clocks (see the recomposition contract below). Derives `UiState.protectionState` (`ACTIVE`/`REDUCED`/`OFFLINE`) via `deriveProtectionState` from monitoring liveness, notification permission, armed channels, official alerts, critical-offline override, silenced types, and NEPTUN state — the single source for the header status chip. |
| `ConnectionLog.kt` | `object` singleton. Persisted ring buffer (last 50 episodes) fed by the watchdog; commits drops only past `OFFLINE_GRACE_MS`. `ConnStatus` = ONLINE/OFFLINE/DEGRADED — every drop records an `OFFLINE` episode. `ConnLogEntry.activeSource` records which source owned the alert feed during the episode (null = primary). Pure `commitLogState` (tested); rendered in the Logs screen. Non-blocking persistence (no `runBlocking` on IO). |
| `DebugLog.kt` | `object` singleton. Persisted audit trail (last 500 decisions, rolling 24h window) written by `AlertService`, read by the Logs screen. Records every alert/threat decision in the active region — official on/off, zone entries, region threats — with day/night and effective sound, whether a notification was shown and why not. Zone `FIRED` rows are recorded synchronously at post time (`recordZoneFired`, which also seeds the sweep verdict so no follow-up row is emitted); `DebugLog.sweep` runs throttled and is **read-only for the decision path**: it renders per-threat rows from the service's maps (`zoneThreats`/`alertable`) plus that tick's plugin `verdicts`/`winnerId` (winner SOUND→FIRED, SILENT→ALREADY_NOTIFIED, SUPPRESS→policy reason, losers→COALESCED), never re-derives formulas. Suppression reasons RATE_LIMITED/ONCE_PER_THREAT/ONCE_PER_TYPE mirror `PolicyReason`. Pure `computeSweep`/serialize/parse (tested). Non-blocking persistence (no `runBlocking` on IO). *Note:* the whole feature is additive — removing it is deleting the write hooks + this object + `DebugLogScreen`. |
| `domain/NotifyPlugin.kt` | Frequency policy for zone notifications (presets EVERY_CHANGE / ONCE_PER_THREAT / ONCE_PER_TYPE / DIGEST + digest knobs, floor, per-type/any scope, minute/episode windows). Pure `tick` over per-threat facts → SOUND/SILENT/SUPPRESS verdicts; episodes close only on track death (never on flicker); shot-grace freeze; `seedKnown`/`snapshot` for restart continuity; `whatIf` retrospective over FIRED rows for the Settings subtitles. Tested (`NotifyPluginTest`). |
| `ServiceState.kt` | DataStore-backed store (distinct from `UserPrefs`) for persisted *operational* state, not user preferences: serialized completed `ConnectionLog` entries, `offline_pending_since`, the reconnect stamp (`reconnect_start_millis`), the official-alert announced-episode identity (`official_announced_token`/`since`/`reason_id`/`city`), `active_zone_alerts`, serialized `DebugLog`/system log, and update-check state (`last_update_check`/`last_notified_update_code`/`last_sdk_manifest_hash`). Read by `NeptunSource` (reconnect stamp), written/read by `ConnectionLog`, `DebugLog`, and `AlertService` (episode identity, offline persistence). This is the split proposed under "`UserPrefs` — resolved" below (formerly the `ZonePrefs` god-object risk) — it already shipped, just not under the name or shape originally sketched there. |
| `Shelters.kt` | Odesa shelter dataset: `Shelter`/`NearestShelter` (adult ~5 km/h, kid ~3 km/h walk minutes), `ShelterIndex` (JSON parse, Odesa bbox, nearest ranking). |

### Engine kernel (source-agnostic)

| File | Responsibility |
| --- | --- |
| `engine/NormalizedThreat.kt` | Mapping-library-agnostic `LatLng`, `TrailPoint`, `NormalizedThreat` (source-agnostic threat model; `type` is a plain String, `flying` gate, `simulated` watermark for simulator-emitted tracks — the engine never branches on it), `fallbackCourse` (NEPTUN's `A(id)` pseudo-course). |
| `engine/ThreatProps.kt` | `ThreatProps` struct (isFast, reachKm, alwaysInnerWithinReach, staleAfterMs, ghostCapMs, nominalSpeedMps, horizonSec, maxGhostMeters) + `DEFAULT_THREAT_PROPS` fallback. Mechanics only — NO per-type values; every value lives in its `Source` impl and flows `Source.typeCatalog → SourceRegistry.typeCatalog`. |
| `engine/Distance.kt` | Haversine `distanceHaversine`/`bearingHaversine`; equirectangular `distanceFlat`/`bearingFlat` (short-range display basis). |
| `engine/SpeedCache.kt` | Engine-internal per-threat fix queue → measured speed/heading; `SpeedSource` (RECORDED/TYPICAL). |
| `engine/OblastAlert.kt` | Source-agnostic alert currency + matching gates: `OblastAlert`, `inOblast` (prefix or whole-word, so Crimea's republic form hits the "Крим" stem), `isOblastWide` (NEPTUN tag, fallback name heuristic), `coversCity` (4-char stem match; oblast-wide covers every city), `officialAlertActiveFor` scope gate. Engine-owned (moved from `data/Threat.kt`). |
| `engine/ThreatEngine.kt` | The core: `evaluate` (inner/outer zones, mapThreats, scores, activeZone, threatLevel, **plus the engine-owned official-alert outputs** `redCities`/`focusOblastAlertActive`/`focusOblastYellowAlertActive`/`officialReason`/`reasonThreatId`), `zoneTier`, `predictPosition` (drift capped by a **distance** floor `DRIFT_MAX_METERS` ≈ 5 km so a marker never crosses the country while a track sits quiet), `motionHeading`, `isStale`/`isExpired`/`isGhost`, `canDrift`, `computeProximity`, `scoreThreat`/`aggregateScores`, `computeRedCities(alerts, fillRegions)` — region-precise labels (wide alerts cover the whole oblast; raion alerts cover their raion's cities when the fill is on, else the whole oblast), `computeFillKeys(alerts, fillRegions)` — the region-fill keys derived DIRECTLY from the alerts via pure canonical lookup (`isOblastWide` discriminator, `CompactOblastBoundaries.canonicalId` and `CompactRaionBoundaries.canonicalKey`/`get`; `fillOblastTokens` whole-oblast fills + `fillRaionKeys` raion fills, raions only when a boundary polygon exists; keys are canonical boundary IDs so UI layers compare with exact set equality), `deriveOfficialAlertReason`; `ThreatZone`, `ZoneParams`, `ThreatEvaluationResult`, `ThreatProximity`. |
| `engine/TypeMapping.kt` | `ThreatType`→engine string (`toEngineString`). No reverse mapper — `NormalizedThreat` is the app-wide currency; NEPTUN JSON parses straight to it (`data/Threat.kt`). |
| `engine/TypeBridge.kt` | App↔engine bridge: `String.toThreatType()`, `threatTypeInfoByString`, `isFastType(type, catalog)` (from `ThreatProps.isFast`), `typicalSpeedKmh(type, catalog)` (from `nominalSpeedMps`). Callers pass the registry catalog — never a concrete source map. |
| `engine/OblastUtils.kt` | Geographic/text utilities (kept from the old `ThreatEvaluator`): `inOblast`, `inFocusOblast`, `matchOblast`, `canonicalToken`, `threatBody`, `alertRegionName`, `coversCityRaion` (scoped raion coverage matching for cities), `OblastMatch`. *Note:* `alertRegionName` renders the alert's region in the UI language (UA raw / EN transliterated) for the official-reason fallbacks; the nearest-in-zone reason picker lives on `ThreatEngine.deriveOfficialAlertReason`. |

### Domain logic

| File | Responsibility |
| --- | --- |
| `NightMode.kt` | Shared night helpers for **both** consumers (mirror rule): `isNightActive`, `effectiveZoneParams`/`effectiveArmed`, `NightConfig`/`NightZones`/`ZoneArmed`. |
| `Cities.kt` | ~483 places grouped by oblast in three zoom tiers (`CityTier`: 26 curated MAJOR always / MEDIUM from mid-zoom — 14 curated non-seats only / rest MINOR up close; non-curated places derived from GeoNames CC BY 4.0 via `tools/gen_cities.ps1`, 2 km dedupe, same-name towns resolve to the major city first, then by population) + `CityLabelOverlay` (colors labels red for the `redCities` set — region-precise: whole oblast for a whole-oblast alert, covered cities only for a raion alert; a red-filled polygon keeps its labels white; MAJOR labels reveal progressively by `MajorReveal` — top-5 OVERVIEW from the country view, 8 MID from mid-zoom, the rest LATE up close); EN names from the app's own КМУ №55 transliteration; `resolveFocus` maps focus point → oblast stem via `cityOblast` (majors only). |
| `CityRaions.kt` | Auto-generated `cityRaion: Map<cityUa, raionAdjectival>` — each city's post-2020 raion (e.g. "Дніпро" → "Дніпровський"), assigned by point-in-polygon against OSM admin_level=6 boundaries, matching NEPTUN's raion alert keys. Generated by `tools/gen_cities_raion.py`. |
| `OblastBoundaries.kt` | Simplified oblast boundary polygons (RDP ~0.01° from EugeneBorshch/ukraine_geojson GeoJSON). `byStem` maps 25 oblast stems to rings of `[lat, lon]` pairs. Used by `MapView` to draw subtle red fills on alerting oblasts when the `fillAlertRegions` toggle is on. Generated by `tools/gen_oblast_boundaries.py`. |
| `RaionBoundaries.kt` | Auto-generated post-2020 raion boundary polygons (OSM admin_level=6 via Overpass, RDP ~0.01°). `forKey(oblastStem, raionName)` resolves a raion's rings, scoped by parent oblast because raion adjectival names repeat across oblasts; keys match `CityRaions` and NEPTUN's raion alert names (case-insensitive); unknown raion names return null (never the parent oblast). Used by `MapView` to shade the alerting raion when `fillAlertRegions` is on and the alert is raion-level. Generated by `tools/gen_raion_boundaries.py`. |
| `GeoConstants.kt` | Shared geographic constants: Ukraine bounding boxes (tight for UI clamping, wide for tile coverage), Odesa city-centre fallback coordinates. Used by `DeathFxController`, `UkraineTileProvider`, `MapView`, `MainViewModel`. (Root package, not `domain/`.) |
| `Transliteration.kt` | Official КМУ №55 Ukrainian→Latin romanization (the EN gate). |
| `UserPrefs.kt` | (formerly `ZonePrefs.kt`) `AppLanguage`/`ThreatCardSize`/`ThreatIconSet`/`OverlapMode` + DataStore store (`user_prefs`): all toggles/thresholds/language/follow/pin/visibility, night config, onboarding flag (`wizard_completed`), `boot_restart_enabled`. `haptics_enabled` is tri-state (absent = follow the system haptic setting). Also `threatMapFlow`/`threatAlertFlow`; the resolved-threat tally's focus-oblast default + "All of Ukraine" opt-in (`neutralized_tally_all_ukraine`). ~74 preference keys — wide, but genuinely just user-facing preferences now; the persisted *operational* state that used to live here (`ConnectionLog`, offline-restore, official-announced identity) has moved to `ServiceState`. |
| `Strings.kt` | UA/EN `StringSet` table (never Android resource localization); `formatRelativeTime`, `formatDateTime` (app language, not device locale). |
| `WidgetSnapshot.kt` | `WidgetSnapshot` + pure `computeWidgetSnapshot(...)` — deterministic projection of threat state for the widget, computed via the engine (`ThreatEngine(typeCatalog).evaluate`, `engine.isStale`, `distanceFlat`, `resolveFocus`); `officialAlert` comes from `eval.focusOblastAlertActive` and `officialYellowAlert` from `eval.focusOblastYellowAlertActive`. Counts + per-type `typeCounts` mirror the footer-strip semantics; `primaryThreat` = nearest live threat (id + position) so the widget can reveal it. Takes `degraded`/`offline` booleans derived by the caller from `SourceRegistry` (mirror rule) and maps them to the three-tier pill: `sourceOnline = !offline`, `sourceDegraded = degraded`. Takes `Map<String, NormalizedThreat>` + `List<OblastAlert>` + the registry `typeCatalog` directly (no `NeptunState`, no concrete source import). Tested by `WidgetSnapshotTest`. |
| `IconCatalog.kt` | Single source for threat icons: vector/photo/army/comic/russian sets, per-set facing (`baseDeg`), `ThreatIcon` composable; assets in `app/src/main/iconpacks/`. |
| `Toasts.kt` | Shared toast helper: one function decides placement — top (below the header banner, via `ToastHost(topInset)`) normally, bottom (above the floating zone/shelter buttons) when a card/popup is visible. Dark themed pill. Callers never hardcode gravity. |
| `Compat.kt` | *(deleted — Session 6)* engine `LatLng`/`ThreatZone`/`ZoneParams` are now imported directly (`com.presaince.oko.engine.*`) instead of root-package typealiases. |

### UI (Compose)

| File | Responsibility |
| --- | --- |
| `MainScreen.kt` | Top-level Compose UI: header, alert banner, map, threat strip, `ZonesSheet`, `UpdateDialog`, first-run wizard + battery prompt. *Note:* wizard gated on a dedicated `wizard_completed` pref surfaced as tri-state `UiState.wizardCompleted` (`null` = DataStore not loaded yet → blank dark frame, neither map nor wizard composes; `false` → wizard-only screen, map is not even composed so no tile flash); "Replay first launch" keeps the map composed beneath (`wizardFromSettings`). While the wizard is up `mapVisible` is false so strikes/haptics/replays are suppressed; card flip timed to `DEATH_EXPLOSION_START_MS`; the popup card's measured height feeds the map as `popupCoverPx` and the zones sheet's as `zonesSheetCoverPx` (both via `onSizeChanged`), so the map frames reveals/fits inside the actually-visible band; the tally-tap replay flourish closes every modal and forces the map screen. The auto-shootdown countdown strip shows the number of strikes actually pending/in flight (`DeathFxController.pendingStrikeCount`), not the count of threats still on the map. The bottom region is a single decision point driven by `flourishActive` (countdown / auto-strike / death / replay / flyby, all equal members): when true the threat strip is hidden and `flourish/FlourishFooter` owns the region (left-aligned Stop pill, whole bar tappable), ejecting to a non-fun map via `cancelTick` → `DeathFxController.clear()` + `ejectAllFun()` (flyby cleared, card NOT opened); when false the pure-threat `ThreatStripFooter` shows (icon pills, or the calm "no threats" message passed in as a precomputed string). Floating zone buttons also hide during any flourish via the same `flourishActive`. Header branches on `UiState.protectionState`: `OFFLINE` → the full-tap "SERVICE OFFLINE" banner; `REDUCED` → an amber "Reduced" chip; an INNER-zone live alert with the siren override off shows an amber "siren follows system volume" chip. |
| `ConnectionStatus.kt` | Connection pill (online / degraded-orange / offline) + `SystemStatusDialog` (hosted in `MainScreen`): per-source dot, the three-tier connection legend, the NEPTUN attribution link, and a prominent "Logs" button opening the Logs screen. The orange middle state mirrors the registry's `degraded` — the WS feed is not delivering (disabled, silent >30s, or down); red mirrors `registry.isOffline(now)` — degraded past the 5-min episode grace with no fallback delivering. |
| `Haptics.kt` | Global press-haptics: `LocalHapticsEnabled` CompositionLocal (provided from the `hapticsEnabled` pref at the MainScreen root) + two press-driven primitives — `Modifier.hapticClickable(onClick)` for custom rows/cards and `rememberHapticInteractionSource()` for Material components owning their tap handling (`Switch`/`Button`/`TextButton`/`IconButton`/`Tab`/`FilterChip`, via their `interactionSource` param). Handlers stay raw: haptics fire from the press interaction (the same signal as the press animation; pointer-event listeners proved unreliable here), never from click-handler wrappers. Raw `Vibrator`, short one-shot at full amplitude (`USAGE_ALARM` on API 30+ — same always-on channel as the shoot-down flourish) because Compose's haptic API is muted by system touch-feedback settings and predefined `EFFECT_TICK` is a silent no-op on many OEMs. Also hosts `animationsOff()` (zero animator scale → snap instead of animate) and the imperative `hapticTick()` for non-Compose tap sites. Applied across map controls, settings rows, and popup cards. |
| `LogsScreen.kt` | Full-screen Logs: one card list over decisions (the audit trail) and connection episodes, switched by chips (Decisions / Connections / Sources / System / Tests). A **Sources** tab lists every registered alert source with its type (WS/REST), real `SourceState`, operational mode (Streaming/Polling/Standby), an enable switch (`setEnabled`), and a **Test** button that runs `testConnection()` and shows the result inline, plus a live activity feed (toggles + takeovers); connection cards tag `activeSource` on fallback episodes. A **Tests** tab lists live per-source `testConnection()` results (ok/summary + timestamp) for every registered source, with a rerun button each; the battery-OEM simulator lives on the **System** tab. The generic empty-state is skipped for Sources/Tests (they render their own content/empty text). The Decisions tab offers group-by (Timeline / Proximity = official, red zone, yellow zone, in-oblast, left / Type), a standard sort-direction icon toggle (newest/oldest) that applies within every grouping, and a "shown only" switch (only rows where a notification was actually shown); controls stay visible even when the list is empty so the shown-only switch can be flipped back. A double-arrow reveals more rows; a leading per-threat-type icon on threat rows (red trident = official on, green check = all-clear), an "ago" + absolute timestamp, day/night + effective sound, "Notification shown" or "No notification — \<reason\>", Clear button. |
| `MapLibreGeoJson.kt` | Generates GeoJSON FeatureCollections for Ukraine land borders, oblast and raion administrative borders, active alert fills, and range warning circles. |
| `MapLibreLayerManager.kt` | Manages MapLibre GeoJSON sources and GPU vector layers (`LineLayer`, `FillLayer`) with dynamic style updates; layer colors come from `AppPalette`. |
| `MapLibreStyle.kt` | Generates MapLibre raster dark style JSON with `BuildConfig.CARTO_API_KEY` and dark background. |
| `MapLibreView.kt` | `MapLibreHostView` + `MapLibreBridge`: Compose wrapper around MapLibre Native SDK MapView, handling lifecycle, camera, GPU vector layers, and projection bridge. |
| `MapView.kt` | `NeptunMapView`. Owns map rendering with MapLibre Native: GPU-accelerated alert fills and border lines via `MapLibreLayerManager`, and Compose Canvas overlays for threat markers, course rotation, shelter pins, city labels, death flourish FX, and GPS dot. Threat markers display only the last four ID digits when the Logs → System "Show threat IDs on map" toggle is on (off by default — IDs are hidden); threat cards do not display IDs. Threat icons scale with map zoom by default (1x→3x across only the final ~3 zoom levels before `NORMAL_MAX_ZOOM`); same-coordinate threats de-overlap per `OverlapMode`. The auto shoot-down `removedThreats` collector is delegated to `flourish/DeathFxController.bindAutoStrike` (policy lambdas only); viewport gating lives in the flourish. Locate requests animate directly to `NORMAL_MAX_ZOOM`. Camera moves funnel through `ui/CameraCoordinator.kt` (`MapCameraCoordinator`). |
| `UkraineBorder.kt` | Two static outlines generated by `tools/gen_ukraine_border.py`: `UKRAINE_BORDER` — the closed outer hull of the oblast boundary polygons (`OblastBoundaries.kt`), and `UKRAINE_LAND_BORDER` — the same ring with coastline edges removed, rendered as a GPU GeoJSON line layer in MapLibre. |
| `SettingsScreen.kt` | Collapsible sections: language, map centre, per-type Map/Alerts toggles + icon packs + icon zoom, card size, alert toggles, the **Just Fun** section (last card: calm messages, shoot-down animation + follow-the-bullet, neutralized count + all-Ukraine — calm messages is effective only when the Just Fun master is on), night-mode card, updates, battery exemption, guide; one-time explainers. The night-mode section tints its whole collapsible card darker purple + border (`NightSectionBg`/`NightSectionBorder`), no moon icon on the enabled toggle; battery exemption lives in the Alerts section; shelter button toggle + shelter directory row live in their own dedicated **Shelter** section; sections stay user-collapsible even while searching; a Reset-tips row re-arms every first-use hint (toast counters + explainers); icon packs ship PHOTO/ARMY/COMIC/RUSSIAN only (CLASSIC removed — a stored `"CLASSIC"` pref falls back to PHOTO, classic vectors remain the internal pack-fallback); the card-size tiles preview the small card as its real compact top-left chip (~75% of tile width) and the large card full-width. |
| `ZonesSheet.kt` | "Edit zones" sheet: Slow (km)/Fast (min) sliders + per-zone bells; edits day or night values depending on the active window; night rows carry day reference ticks. |
| `ThreatPopupCard.kt` | Threat popup (small chip / large card); `AlertsOffChip` when type alerts are off; neutralized/neutralizing variant. The ETA pill's blue GPS dot mirrors the map location dot (same core + white ring, subtler radial glow). |
| `ThreatTogglePanel.kt` | Shared Fast/Slow grouping, `ToggleChip`/`IconToggle`, `SlimThreatToggles` (reused by first-run dialog + Settings). |
| `FeatureExplainer.kt` | One-time explainer popups keyed by setting id; seen state via `UserPrefs.explainerSeen`. |
| `FeatureGuide.kt` / `FeatureDiagrams.kt` | Static feature guide + its diagram drawables. |
| `FirstLaunchWizard.kt` | The 5-page first-run wizard (language+tips → threat care → location follow-me/city-pick → zone controls → core features), extracted from MainScreen; thin yellow→blue progress bar on the **top** edge of the Next pill, whole pill dims while disabled. *Note:* threat care is a 2-column vertical grid (fast ⚡ | slow 🐢, 52dp icons) with no icon-pack picker (icon packs live only in the Just Fun panel); the zone-controls step shows big red/yellow concentric circles, a REAL slow-red radius slider + armed toggle (wired to the same ViewModel setter as the map) with the slider hint beneath it, then vertical rows for shelter / red-dot / gear with short captions (`wizardShelterDesc`, `zoneRedLabel`, `editZonesLabel`); Just Fun is **off by default** on fresh installs (the master switch is the global flourish kill-switch, enforced by the engine gates, not just a panel toggle) with the "⚙ settings" mention rendered as an inline colored gear; every interactive control ticks via `pressTick`. |
| `CityChipPicker.kt` | Shared major-city chip grid (`CityChipGrid`) used by the wizard's location step and Settings' pin-city row. |
| `ShelterScreen.kt` | "Go to shelter" list: nearest Odesa shelters ranked by distance to the focus point, adult/kid walk times (kid row only when the "With kids" setting is on), a GPS-age header with a force precise-fix button (re-prompts location permission), transliterated names in EN; "open in maps" (`geo:` intent); the map button is a red-filled (official alert) or ghost-outlined pill in `MainScreen.kt`. |
### Flourish (isolated death + tally subsystem)

| File | Responsibility |
| --- | --- |
| `flourish/Flourish.kt` | Pure flourish core: `FlourishRecord`/`FlourishShow`/`ReplayProgress` (per-group copy + overall position for the footer bar); `FLOURISH_STAGGER_MS` + `REVEAL_MIN_SPAN_*`; `clusterFlourish` (viewport-adaptive replay grouping) / `clusterFlourishByOblast` (oblast-token grouping used in All-of-Ukraine mode — `canonicalToken` from `engine/OblastUtils` with nearest-city fallback); `flourishesBoundingBox` (25% margin, min-span floors); `FlourishPolicy` (the neutralized-card gate, pure + tested). |
| `flourish/DeathFxController.kt` | Map-side facade: owns `ThreatDeathOverlay` + strike camera glide/return, shot/kill haptics, a random viewport-edge take-off origin (clamped to Ukraine, so a projectile never launches from "another country") and the tally-tap replay orchestration (exposes `replayProgress: ReplayProgress?` for the footer's per-group "Resolving N threats" + overall progress bar; `startReplay` owns the replay job so `clear()` cancels a show mid-flight; while queued/running `isReplayActive` makes MapView hold its follow-me pan + default fit so the show jumps straight onto its targets). The auto-shootdown countdown defers to manual explosions at each tick and just before firing (`overlay.isActive` → hold at the current number via `overlay.active.first { !it }` until the death animation ends — no flags/queue) and is viewport-gated: when `followBullet` is off, an off-screen anchor skips the countdown/strike entirely (`isOnScreen` via `bridge.project` + 16px inset; on-screen still animates in place). The `removedThreats` collector lives here (`bindAutoStrike`) — MapView only supplies policy lambdas (`deathAnimationEnabled`, `isMapInFocus`, `hiddenTypes`, outcome/icon/rotation resolvers) and no longer owns the countdown. Camera notes: `getMapCenter()` is snapshotted into a new GeoPoint everywhere (osmdroid hands back its live mutable projection point — holding it made "return home" land randomly); replay jumps per group via `zoomToBoundingBox(box, false)`. Pacing: each group's targets are PRE-SPAWNED on arrival with staggered `fireAtDelayMs` leads (the overlay renders pre-fired deaths as standing icons, so nothing pops up as its bullet launches); the fire loop aligns to the prespawn clock; intermediate groups pan `REPLAY_PAN_BEAT_MS` (120ms) after the last impact via quickBoom deaths; only the final group plays the full 5s lifecycle. The tally-tap tick is consumed only on a real decision — transient blockers (cold start, Settings, shelters) retry; animation-off toasts + audits (`DebugLog.recordFlourish`, detail localized via the `showDetail` lambda); a live official alert does NOT block the replay (explicit user action) though a NEW alert onset mid-show still ejects it via `clear()`. The whole facade is master-gated (`justFunEnabled` mirrors the Just Fun master pref): `strike`/`strikeDud`/`startAutoCountdown`/`startReplay` no-op while it's off — which also gates the long-press easter egg — `strike`/`strikeDud` return whether a projectile actually launched (the long-press handler applies its marker-hide / user-shot-grace / haptics side effects only then, so a disabled flourish leaves the marker untouched), `strikeHaptics` is master-gated too, and flipping the pref off `clear()`s anything in flight. Any map-covering modal (paused/mapVisible/shelters) or lifecycle ON_PAUSE also ejects a RUNNING show via `clear()` (queued-but-unstarted ticks stay queued and play on uncover; first-3 ejections toast a hint via `notifyFlourishEjected` + `flourishEjectHintRemaining`). `clear()` glides the camera back to the saved home FIRST (a strike/replay parks it), then tears down behind the scenes — an early eject never leaves the view stuck on the target; the replay remembers its pre-center in the same saved-home slot. Auto-strikes publish a `pendingStrikeCount` (strikes pending + in flight) for the countdown strip. `MapView` keeps only thin policy hooks and delegates every flourish mechanic here. |
| `flourish/NeutralizedTally.kt` | Service-side facade: tally count + 21-record resolution memory + the silent tally notification (tap replays the show, swipe resets), with a recent-ids ring deduping NEPTUN's re-sent removals so duplicates never double-count nor plant twin replay records. `reset()` keeps the ring (re-sends within the grace don't re-count/re-post ~1 min after a swipe/tap). Owns `CHANNEL_NEUTRALIZED`/`NOTIF_NEUTRALIZED`/`EXTRA_FLOURISH_*`/`ACTION_NEUTRALIZED_DISMISS`. `AlertService` keeps only the enabled-pref subscription gate + focus-scope filter; the facade itself is master-gated (`onResolved` no-ops while the Just Fun master is off, and flipping it off resets the tally). |
| `flourish/AlarmEpisodeTally.kt` | Morale-only per-alarm summary, fully independent of `NeutralizedTally` (own count/memory/dedup-ring/`NOTIF_ALARM_EPISODE`). Buffers focus-oblast resolutions while the scoped official level (red or yellow) is live, posts one `CHANNEL_ALARM_EPISODE` summary (all-clear fun tone, `IMPORTANCE_DEFAULT`) when the window closes — re-posted on the same id so missed alarms never stack. Zero-count posts a silent "All quiet" line only when official alerts are on; official off skips the nothingness but a positive count still posts. Reuses `NeutralizedTally.EXTRA_FLOURISH_*` keys for the replay tap. |
| `flourish/AviationFlyby.kt` | Pure policy/geometry for the MiG-31K takeoff flyby: `nextShow` picks each new INNER-tier AVIATION once per process (never while the app is backgrounded or off the map screen, and never while the Just Fun master or flyby toggle is off), a fresh random bearing every pass; `tapShow` is the same gate for user-initiated passes. `endpoints` computes edge-to-edge entry/exit through the viewport center; `spriteTransform` returns the mirror-compensated rotation so the jet never renders upside-down. When it lands `MainViewModel.onFlybyFinished` opens the threat card. Tested by `AviationFlybyTest`. |
| `flourish/AviationFlybyOverlay.kt` | The flyby's Compose overlay (owned here, not in screens): full-size jet sprite + contrail Canvas driven by an Animatable pass. Rotation and trail origin come from `IconGeometryCache` per icon set through the same mirror→rotate chain graphicsLayer applies; legacy constants are only a decode-failure fallback. |
| `flourish/FlourishFooter.kt` | The flourish's bottom-region UI (owned here, not in screens): countdown digits, tally-replay progress, and the neutralizing label with a left-aligned Stop pill; the whole bar is a tap-to-stop target. Fully isolated from the threat strip — the caller passes `active` (the single "any flourish" boolean), so it never knows about threats or the map footer. Self-sized; no dependency on ThreatStripFooter's measured height. |
| `flourish/IconGeometry.kt` | Alpha-silhouette metrology for icon art: `computeIconGeometry` recovers the true facing (PCA principal axis, end disambiguated by the pack's declared `baseDeg`) and the exhaust anchor (rear-band centroid) as slot-local fractions — no per-pack pixel tuning. Pure core tested by `IconGeometryTest`; `IconGeometryCache` decodes/caches per `ThreatIconSet`. |
| `flourish/ThreatDeathAnimation.kt` | `ThreatDeathOverlay`: 5s neutralized flourish (projectile enters from just off the screen edge along a random edge-clamped origin -> explosion). Decoupled from any specific map engine: `draw` takes `(lat, lon) -> PointF?` projection lambda and standard Android Canvas, rendered within a sibling Compose Canvas overlay. Per-death `durationMs`: `quickBoom` deaths (intermediate replay groups) compress the explosion to impact + ~0.8s flash; boom/fade curves and pruning derive from each death's own duration. Perf practices: explosion glow is a lazily pre-rendered per-density bitmap (no per-frame `RadialGradient` allocation), icons are cached bitmaps with fresh drawable wrappers (the per-frame alpha mutation must never touch a live marker's icon), concurrent deaths capped at `MAX_DEATHS = 14` (sized for a full replay, the old 6 silently ate bullets), and the map redraws at 30fps while active (16ms -> 33ms; invalidate redraws the whole overlay stack). `DEATH_EXPLOSION_START_MS` drives the card flip; dud on duplicate resolutions; `isActiveFor(id)` guards double-strikes; the target icon vanishes at the explosion (no fade); `active` StateFlow tells the UI when a bullet/explosion is on screen. |

### Background / alerting

| File | Responsibility |
| --- | --- |
| `AlertService.kt` | Foreground service; reads `AppSources.registry` flows (`allThreats`, `allAlerts`, `removedThreats`, health) via combine — never a specific source (`specialUse` on API 34+, `dataSync` on API 29–33 — Android 15 caps `dataSync` FGS at 6h per 24h in the background, which would stop a 24/7 monitor) — the always-on monitor. Owns background monitoring and the notification lifecycle: siren/chime/all-clear (plugin verdicts: SOUND/SILENT/SUPPRESS, coalesced winners), the always-visible monitor notification switching to offline wording + Retry on a drop (no separate one-shot offline alert) and a large trident icon tinted from the engine's official level (red/yellow/none — mirrors the header; never reacts to the app's own red/outer zones), resolved-threat tally, per-notification vibration, `DebugLog` feed. Zone-alert frequency is plugin-owned (`domain/NotifyPlugin.kt`): episodes open on first sighting, close only when the track dies (stale/resolved/gone); first INNER per episode always sounds; user-shot same-id respawns never re-alert (shot-grace freeze); presence persists as id-tier JSON and reseeds on restart (one silent cold-start repost). Engine tiers carry a 10% spatial hysteresis band (callers thread `prevTiers`). | The resolved-threat tally counts by **focus oblast** by default (GPS-follow or pinned city's oblast, matched via the shared `inOblast` (`engine/OblastUtils.kt`)); an "All of Ukraine" opt-in (`neutralized_tally_all_ukraine`) lifts it to any resolution country-wide. Tapping the tally notification opens `MainActivity` directly with the last **21** remembered resolutions (position + type + region token) baked into the tap for the map's replay flourish; a red alert (official or INNER zone) erases that memory. Zone-alert frequency is plugin-owned (`domain/NotifyPlugin.kt`): episodes open on first sighting, close only when the track dies (stale/resolved/gone); first INNER per episode always sounds; user-shot same-id respawns never re-alert (shot-grace freeze); presence persists as id-tier JSON and reseeds on restart (one silent cold-start repost). Engine tiers carry a 10% spatial hysteresis band (callers thread `prevTiers`). *Note:* the official-alert all-clear is region-latched: it fires only while the focus is still on the region whose alert was ringing (`state.focusToken == officialRegionToken`); switching the focus away to a non-alerting region — **or re-pinning to a different city, even within the same oblast** (the announced city is tracked alongside the token) — silently drops tracking (no false all-clear, no lingering siren), and returning to the still-alerting region re-announces fresh. The silent reason-refresh only re-posts while the new reason is a threat inside the user's zones; a reason that falls back to the bare oblast name never re-raises a dismissed notification. `ACTION_NEUTRALIZED_DISMISS` (swipe or tap-consumed tally) resets the tally. The offline drop is persisted (`offline_pending_since`) so it re-flags after a service kill; evaluates via the shared domain functions, never local formulas. Official-alert lifecycle keys on the RAW episode (`focusOblastAlertRaw`): the City-level scope toggle only gates announce/suppress — flipping it mid-alert never fires a false all-clear nor re-rings (suppression cancels silently; returning coverage announces fresh). Reconnect milestone flags reset **and** the milestone notification is cancelled on the reconnect transition; official-alert notifications track their own notified-state (turning the toggle back on mid-alert re-announces); `ConnectionLog`/`DebugLog` restore is awaited before `NeptunClient.start()`. Notification taps that carry a threat use their own `PendingIntent` request code (1) so the reveal extras can't be clobbered by the plain status/tally/milestone intents (0); the ongoing status title reads "Monitoring GPS" when following and "Monitoring \<city\>" when pinned, and it switches to the offline wording with a Retry action once a drop outlasts the shared grace. A daily 16:20 coroutine checks `UpdateManager` silently and posts one silent "new version available" notification per new build (`last_notified_update_code` pref, `NOTIF_UPDATE`); its tap carries `EXTRA_SHOW_UPDATE` with its own `PendingIntent` request code (4). |
| `FallingDebrisBuffer.kt` | Safety delay countdown timer holding back the audible All-Clear chime after an alert drops. Pure, source-agnostic timer with zero city tracking and zero secondary mirrors; aborts instantaneously upon any new threat onset. |
| `MonitoringStatus.kt` | In-memory `running` StateFlow mirroring whether `AlertService` is alive in this process. Set true by `AlertService.start()`/`onStartCommand`, false by `onDestroy`; the UI replaces the whole header with a tappable "SERVICE OFFLINE" banner when it's false. The socket alone is not proof alerting works — the connection is owned by the app process, not the service. |
| `AlertWatchdog.kt` | WorkManager periodic worker (15 min) that restarts `AlertService` if `MonitoringStatus.running` is false and `bootRestartEnabled` is true. Scheduled from `BootReceiver` and `MainActivity.onCreate`. Closes the process-kill silent dead state. |
| `BootReceiver.kt` | Restarts `AlertService` on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, gated on the user's `boot_restart_enabled` pref (Settings → Alerts). When monitoring is not auto-restarted, posts a persistent "Monitoring paused after reboot — tap to start" notification. Always schedules `AlertWatchdog`. |
| `NeutralizedDismissReceiver.kt` | Delete intent for the tally notification (resets the count). |
| `LocationTracker.kt` | `object` singleton. Coarse fix via continuous `PASSIVE_PROVIDER` (always live, zero extra radio — copies other apps' fixes) plus our own `NETWORK_PROVIDER` subscription only while the screen is on (2-min / 0-m), falls back to last known -> `StateFlow<LatLng?>`; tracks the last fix time (`lastFixAtMs`) and offers a `forceRefresh()` precise fix for shelters / calibration — it keeps GPS armed (continuous listener, ~24s) before falling back to an active network one-shot, and works with coarse-only permission. On start with no fresh fix it retries a cheap network one-shot until the provider warms, and, while following, kicks the GPS retry so the dot arrives promptly without a restart. A 15-min periodic GPS sync loop runs when enabled (pref `periodic_gps_enabled`, default off) and only while the screen is on, so no satellite lock is ever grabbed in your pocket. || `BatteryOptimization.kt` | Battery-exemption helpers. |

### Widget

| File | Responsibility |
| --- | --- |
| `widget/ThreatWidget.kt` | Glance home-screen widget (`provideGlance` + `provideContent`, `SizeMode.Responsive`). Passive renderer of the persisted [WidgetSnapshot] - never evaluates zones/tiers itself (mirror rule). Three density buckets (compact 2x1 / standard 4x2 / detailed 4x3) picked from `LocalSize`; dark-only palette; tap opens the app. The primary threat icon (the nearest threat) is its own tap that reveals that threat on the map via the same reveal extras as a notification tap; the status badge mirrors the app-grace-filtered online/offline pill. `ThreatWidgetReceiver` is the manifest-declared `GlanceAppWidgetReceiver`. |
| `widget/WidgetUpdater.kt` | `object` singleton. Started by `AlertService` (the already-running monitor, ~zero marginal battery). Guards on `hasPlacedWidgets()` before starting the pipeline. Combines `AppSources.registry.allThreats/allAlerts` + `LocationTracker.location` + zone/follow/pin/language/type-gate prefs + a 30s clock → `computeWidgetSnapshot` → persists to the `widget_snapshot` DataStore and calls `updateAll()` only when a widget is actually placed. Persists `primaryThreat` (id/lat/lon/type) so the widget can reveal the nearest threat. Exposes `readSnapshot`/`readLang`/`readIconSet` |
for the widget (icon set mirrors the user's `threatIconSet` pref, so the widget uses `IconCatalog.res(type, set)` 
like the rest of the app). |

### Updates / misc

| File | Responsibility |
| --- | --- |
| `UpdateManager.kt` | `UPDATE_BASE_URL`, `check()`/`download()`/`buildInstallIntent()` (FileProvider); `fetchSheltersJson()` pulls the daily shelter-list copy. |

### Build / release

| File | Responsibility |
| --- | --- |
| `app/build.gradle.kts` | Android config + custom tasks: `bumpVersion`, `release`, `uploadRelease`. |
| `app/version.properties` | `versionCode`/`versionName` — source of truth for the build + `version.json`. |
| `server/version.json` | Committed example of the generated update feed. |

## Key invariants

Treat these as a contract. If you change one, update **every** place that relies on it.

- **Onboarding gates on `wizard_completed`, not `language_chosen`.** The dedicated flag is what
  shows the wizard (`UiState.wizardCompleted`, tri-state: `null` = DataStore still loading —
  compose neither map nor wizard), what defers permission requests (`MainActivity`), and what
  re-runs via "Replay first launch" (which clears only this flag). `language_chosen` remains only
  as legacy migration input.
- **No GPS fix + no pinned city = no claims.** The focus is `null` and the attribution is
  country-wide (`resolveFocus`'s final branch — token `null`, banner "Ukraine"), so no oblast
  zones or official-alert scope is asserted for an unknown position. While following GPS with no
  fix at all, the UI and the ongoing notification show a persistent "No GPS fix" warning.
- **One episode authority.** The offline/degraded episode (its monotonic `degradedSince` and every derived milestone) is owned by `SourceRegistry` alone. Transport (`ResilientConnectionSupervisor` + `ConnectionState`) publishes raw connection-state changes only — never its own episode timestamps or milestone flow. There is exactly one place that computes "how long have we been degraded".
- **Single evaluation logic.** `MainViewModel` (UI) and `AlertService` (notifications) each
  construct their own `ThreatEngine(registry.typeCatalog.value)` and call `engine.evaluate(...)`
  directly — no reimplemented zone/tier/prediction/alert logic in either consumer. Every other
  consumer does the same: `MapView`, `ThreatPopupCard`, `DebugLog`, `WidgetSnapshot` all build
  their engine / read props from `registry.typeCatalog`, never from a concrete source import.
  Importing a concrete source catalog (`source.NeptunSource.NEPTUN_TYPES` or any successor)
  from `engine/`, UI, service, widget or domain code is a layering violation — per-type values
  flow only as `Source.typeCatalog → SourceRegistry.typeCatalog → ThreatEngine(catalog)`.
  The full
  engine contract (tiering, dead-reckoning, scoring, official alerts) lives in `BEHAVIORS.md`,
  pinned by `ThreatEngineTest`. Official-alert scope (oblast/city) is engine-owned: the
  `officialAlertActiveFor` gate lives in `engine/OblastAlert.kt`, and the UI banner, `redCities`
  labels and the service's siren all read engine outputs. Oblast-wide alerts (name/oblast ends
  in "область" or "республіка") cover the whole stem — every city in the region lights and
  rings; the City scope narrows only alerts that name a specific city/raion. `inOblast` matches
  by prefix or whole word, so Crimea's "Автономна Республіка Крим" hits the "Крим" stem. The UI
  pill (`neptunDown`) immediately reflects connection drops (no grace filter) so the header and
  notifications agree; the grace is only applied to the connection log.
- **Official alert announces once per episode, surviving service restarts.** `AlertService`
  persists the announced episode identity (focus token + NEPTUN `since` + reason threat id) to
  `ServiceState` (`officialAnnounced*` — persisted operational state, not a user preference), loaded inside `startMonitoring()` before the first tick
  (no startup race), and reconciles it after a START_STICKY restart, so an alert already fired
  before a service kill never re-rings. The persisted identity is cleared only when the episode
  genuinely ends (all-clear / focus switch / alert gone). Re-enabling the official-alerts toggle
  does NOT re-announce a live alert that already rang (an alert that started while muted
  announces naturally). Silent reason refreshes only re-post while `NOTIF_ALERT` is still
  showing (`alertNotificationShowing()`) — a tapped/swiped alert is never resurrected as a new
  siren by a same-episode reason update. When NEPTUN omits `since`, dedup falls back to the old
  in-memory behavior (may re-ring once after a restart).
  Settings are not lifecycle events: the **City level** scope toggle only gates
  announcement/suppression. Each tick computes both the raw episode (`officialAlertActiveFor(...,
  scope = false)` → `MonitorEvent.State.focusOblastAlertRaw`) and the scoped activity; the
  all-clear keys on the raw episode ending, so flipping City level mid-alert never synthesizes a
  false all-clear nor re-rings. There is **one episode latch** (`officialRegionToken`), raised
  when the first official ring (red or yellow) fires for the region and released only by a focus
  switch (silent) or the raw-end all-clear — so a mid-episode scope drop (alert narrowed away
  from the focus city, or a yellow that follows a red) still announces the single raw-end
  all-clear. Scope-driven suppression (City level switched on without coverage,
  or coverage dropped) cancels the notification silently; returning coverage announces fresh.
  Official facts (`focusOblastAlertActive` / `focusOblastYellowAlertActive`) come from the same
  engine gates the header and widget use — the monitor notification trident mirrors the header
  exactly and never reacts to the app's own red/outer zones (the header border carries that
  signal).
- **Night mode is shared, not mirrored.** Both sides call `isNightActive`/
  `effectiveZoneParams`/`effectiveArmed` resolved per tick (`now`); a new
  night knob needs only a pref + a `NightMode.kt` change. Vibration is fixed, not per-night.

- **Widgets read snapshots, never evaluate.** The home-screen widget is a passive renderer of
  `WidgetSnapshot`, computed solely by `WidgetUpdater` via `computeWidgetSnapshot` — which calls
  the engine (`ThreatEngine.evaluate`, `isStale`, `distanceFlat`) plus `resolveFocus`; the
  `officialAlert` flag comes straight from `eval.focusOblastAlertActive` (no third
  implementation). A change to zone/tier/prediction/alert logic must **not** be reimplemented in
  the widget layer; update `computeWidgetSnapshot` instead. Counts mirror footer-strip semantics.

- **Threat type gating.** `threatMapFlow` gates map rendering, `threatAlertFlow` gates alerts —
  decoupled toggles: map-off doesn't silence alerts; alerts-on auto-enables map visibility (an
  armed alert is never hidden). A type hidden from the map is dropped from the map and the
  footer strip; a type with alerts off stays fully mapped (never dimmed — dimming is
  staleness-only) but is omitted from the footer strip, with a red crossed bell on its popup.

- **Focus point — one shared resolver.** `followMe` → camera + zones + alerts centre on the
  last-known GPS fix (staleness is fine; the focus never falls back to a pinned city while
  following), else the pinned city; pinning disables follow-me. Attribution via
  `resolveFocus` → `cityOblast` stem match, **major cities only** — the ~300 minors are
  map-context, never banner/alert. All three consumers (`MainViewModel`, `AlertService`,
  `WidgetUpdater`) call the same `resolveFocus`, so a stale fix keeps ringing where the user
  last was instead of drifting to a pin. With no fix at all the location is `null` — no fake
  region alert, and `gpsFixMissing` drives the persistent "No GPS fix" warning.

- **Zone tiering.** The formula — fast-by-ETA vs slow-by-distance, `reachKm` caps,
  `alwaysInnerWithinReach` (aviation's country-wide INNER), speed from `SpeedCache` — is the
  engine contract in `BEHAVIORS.md`. App-side facts: map circles show the slow km thresholds
  only; advisory threats never tier/sound; armed bells are per group×tier (slow/fast ×
  red/yellow), stored for day and night, resolved per tick by `effectiveArmed`. **Slider
  coupling:** the yellow threshold is relative to red — setters clamp yellow to `red+2 … max`
  (slow 1–20 km red / yellow ≤50; fast 1–5 min red / yellow ≤20) in `UserPrefs`; the UI sliders
  derive the yellow range from the red value live.

- **Expiry / ghosts.** `staleAfterMs`/`ghostCapMs` per type and the `canDrift` dead-reckon gate
  are the engine contract in `BEHAVIORS.md`. App-side facts: stale threats stay mapped
  **dimmed** (alpha 0.45, tappable) but excluded from strip, tiers, gauge, alerts; removal only
  on server resolve / `remove` frame / ghost. **AVIATION never locally expires:** a MiG-31K
  takeoff pin sits at the launch airbase without fix refreshes, so age-based expiry would kill
  every real alert before it rang — only the server's `status: "stale"` retires it early, and
  its ghost cap is its own (2h). When the **selected** threat disappears that way,
  `MainViewModel` swaps the popup for the neutralizing card (flips at
  `DEATH_EXPLOSION_START_MS`, fades across the explosion) — only while the map screen is visible
  and the shelter overlay is down; it *does* play during an alert; with `deathAnimationEnabled`
  off nothing animates. MapView
  glides markers on its own 1s tick (30fps tween); the service clears on a 20s grace.

- **Threat facing always matches its motion.** The heading resolution chain (`motionHeading`:
  `bearingDeg` → `heading` → measured), the dead-reckon course rule (server-reported only,
  gated by `canDrift`) and the `fallbackCourse(id)` pseudo-course for stationary threats are the
  engine contract in `BEHAVIORS.md` — a change must stay in the engine so the marker never faces
  a direction it doesn't move.

- **Place names transliterate, never translate.** Any Ukrainian proper noun in the EN UI →
  `Cities.uaToEn` → `Transliteration.transliterate`; military vocabulary is hard-coded
  (`COURSE_PATTERNS`/`COURSE_GLOSSARY`/`ThreatTypeCatalog`). No network translation.

- **REST never clobbers WS.** REST merge keeps the newer record per threat id
  (`updatedAtMillis`); a REST snapshot can be CDN-stale.

- **Official alerts come from a priority-ordered set of sources, one live owner at a time.**
  `SourceRegistry` is the health authority: it derives per-source state, aggregate
  `connectionState`, `wsHealthy` (a WS source enabled AND actually delivering — `CONNECTED`, not
  silent/`DEGRADED`), `degraded` (`!wsHealthy`), `degradedSince`, `coveredByFallback` (a non-WS
  source delivering real data), and `isOffline(now)` (degraded past the episode grace with no
  fallback). The merged alert feed uses **takeover** semantics, not union: while a source is
  *authoritative* (WS socket CONNECTED, or REST poller engaged and having actually fetched), its
  snapshots are the sole truth; if nothing is authoritative the last-known state is held (never
  fabricated all-clear).
  This means an active REST fallback (Ubilling) supersedes the primary's stale held alerts during
  an outage, so a real all-clear can't be masked.   Alert **scope** (oblast/city) is engine-owned:
  both consumers read the `officialAlertActiveFor(...)` gate in `engine/OblastAlert.kt`, and the
  map's red city labels (`redCities`) come from the engine result — oblast-wide alerts cover the
  whole stem, City scope narrows only city/raion-named alerts. At night, `nightOfficialAlertCityScope`
  overrides the day `officialAlertCityScope` when active. NEPTUN's list is held (never
  cleared) while its socket is down — but once a fallback takes over,
  the fallback's snapshot is authoritative for the regions it reports. A continuous
  Neptun→Ubilling→Neptun handover never re-rings: the runtime announce latch keys on the alert
  staying active, not on `since` (Ubilling reports `since = null`); only a service kill
  mid-outage falls back to the documented `since`-based restart reconcile (may re-ring once).
  **Connection health is three-tier, and the tiers mean different things.** Green = a WS source is
  delivering its live feed. Orange (**degraded**) = the WS feed is *not delivering* — disabled,
  silent (no frame for `DEGRADED_STALE_MS` = 30 s), or down. This is a **data** state, immediate,
  independent of fallback coverage. Red (**offline**) = degraded has persisted past
  `OFFLINE_EPISODE_MS` (5 min, a notification-timer concept only) with no fallback delivering —
  the offline notification episode. The 5-minute grace never gates the degraded data state: disable
  NEPTUN → orange immediately; only a genuine outage with nothing covering escalates to red. The
  tiers are advisory for alerts (they never suppress tiering or the widget); offline (red)
  outranks degraded in the pill. The degraded/offline derivation lives once in `SourceRegistry`;
  the header, notification, and widget only consume it.
  The reconnect loop itself is network-aware via a `ConnectivityManager.NetworkCallback`: on a
  validated link it stays fast (1–15s), on a provably-dead link it widens to
  `NO_NETWORK_RECONNECT_MS` (60 s) and resumes immediately when the OS reports a network again.

- **Official alert is region-latched, never focus-bound for its end.** The official-alert
  all-clear (both notification and UI banner) must fire only for the region whose alert was
  ringing. Switching the focus away (pin → GPS, or to another city) to a non-alerting region
  must **not** announce an all-clear for the old region nor keep its banner — it silently drops
  the active-alert tracking, and returning to a still-alerting region re-announces fresh.
  Implemented in `AlertService` via `officialRegionToken`; the UI banner (`MainViewModel`)
  always reflects the *current* focus region only.

- **No cloud / no push.** Monitoring is a local foreground service (`specialUse` on API 34+,
  `dataSync` below; the manifest declares both types plus
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "continuous air-raid alert monitoring for user safety"`
  for Play review); alerts stop when it stops. No intermediate server buffers anything.

- **Monitoring is always-on; "Stop Monitoring & Exit" is session-only.** Every cold start
  (`MainActivity.onCreate`) re-arms `AlertService`; the old `monitoring_enabled` persisted off-switch
  is gone because it produced a silent dead state (the NEPTUN socket stays alive from the app
  process, so the map looks fine while no alert/notification machinery runs). The only persisted
  off-switch is `boot_restart_enabled` (Settings → Alerts → "Restart monitoring after reboot",
  guarded by a security warning) which gates `BootReceiver`. If the service dies while the app is
  open, `MonitoringStatus.running` goes false and the header is replaced by a red tappable
  "SERVICE OFFLINE" banner — the one loud signal that alerting is off when the connection isn't.

- **Battery-first location.** Continuous tracking is coarse `NETWORK_PROVIDER` only (~2 min /
  250 m), never fine GPS. A 15-min periodic GPS sync one-shot runs by default (`periodic_gps_enabled`,
  default on) to correct cell-tower drift and give Android real location access; `forceRefresh()` is
  the on-demand precise one-shot for calibration/shelters.

- **Siren channels.** Notification stream by default; alarm stream (DND-piercing) only with
  `sirenOverride`. All-clear never overrides.

- **Single color source of truth.** Shipping code never hardcodes color literals (no `0x…`,
  `Color.rgb/argb`, `parseColor`). Every color is an `AppPalette` token — Compose: `Color(AppPalette.X)`,
  canvas/MapLibre/service: `AppPalette.X.toInt()`. `DarkThemePlugin` derives `darkColorScheme` from the
  same tokens. Animation explosions (`ThreatDeathAnimation.kt`) and chart diagrams
  (`FeatureDiagrams.kt`) remain exempt.

## Ownership boundaries

### NeptunSource (was NeptunConnectionClient)

Owns:
- WS socket + reconnect machine + milestone timer (`ResilientConnectionSupervisor`)
- frame parsing + feed state (`NeptunRawDecoder` + `MonitorCoreImpl`)
- per-source `SourceState` mapping
- authoritative track lifecycle and source type properties catalog (`NEPTUN_TYPES` owning `staleAfterMs`, `ghostCapMs`, reach, nominal speeds)

Must not:
- decide alert tiers
- access UI state
- post notifications
- delegate track pruning or lifecycles to arbitrary downstream timers outside the SPI catalog

### MainViewModel

Owns:
- UI state derivation
- selected threat
- UI flows

Must not:
- own the WebSocket lifecycle
- directly perform map rendering
- duplicate zone math
- compute official-alert facts locally (read them from the engine result)

### AlertService

Owns:
- background monitoring
- notification lifecycle
- official-alert orchestration (region latch, announce-once persistence, sound policy) — reading
  engine-produced facts, never re-deriving the gate or formulas

Must not:
- depend on Compose
- use UI-only selected state
- implement new zone formulas locally
- re-implement the official-alert gate/reason (engine-owned)

### MapView

Owns:
- rendering
- map interaction
- visual animation (frame-synchronized via MapLibre camera listeners and native invalidation)

Must not:
- decide whether a threat should alert
- persist application state

### UI Composables (e.g. ThreatPopupCard, Header, Footer)

Owns:
- pure presentation of state provided by ViewModel / UI models
- user interaction callbacks

Must not:
- instantiate or query `ThreatEngine`
- compute domain metrics (zoneTier, isStale, proximity formulas) locally
- block or gate initial paint on complex background state recomputations

## Domain vs Presentation Layering

Domain evaluation belongs strictly to domain owners (`ThreatEngine`, `MainViewModel`, `AlertService`, `WidgetUpdater`):

- **MainViewModel and AlertService:** both drive evaluation through the engine independently because the UI requires interactive state while background monitoring continues when UI is not active.
- **Engine as Single Source of Truth:** `ThreatEngine` owns zone tiering, dead-reckoning, staleness/ghost rules, scoring, proximity, and official-alert facts. Composables and presentation layers never re-run or re-implement domain decisions.
- **Pointer-first selection:** `selectionUi` emits an immediate shell (Frame 0) upon user selection so the card renders instantaneously, and asynchronously enriches with proximity and zone calculations as background updates complete.
- **Frame-synchronized overlay:** `MapView` overlay tracking is driven directly by native MapLibre camera move callbacks (`addOnCameraMoveListener`) rather than Compose recomposition cycles, eliminating pan/zoom tracking lag.

## Deliberate tradeoffs / risks

### Domain evaluation ownership

`MainViewModel` and `AlertService` both drive evaluation through the engine.

- **Why:** the UI needs continuously refreshed state; background monitoring must continue
  independently of the UI.
- **Mitigation:** both consumers call the engine — `ThreatEngine.evaluate(...)` /
  `computeProximity(...)` — on their own instance built from the shared `typeCatalog`
  (`AppSources.registry.typeCatalog.value`). The engine owns zone tiering, dead-reckoning,
  staleness/ghost rules, scoring, proximity **and the official-alert facts** (`redCities`,
  `focusOblastAlertActive`, reason); neither consumer re-implements a decision formula. Any
  change lands once, in `engine/`, and both paths are covered by `ThreatEngineTest`.
- **Note:** each `ThreatEngine` carries its own `SpeedCache` (speed history is per-consumer,
  not a shared singleton). Speed fallback is deterministic (server → trail → nominal), so
  consumers can't disagree near a zone boundary. The service additionally reads the raw
  (scope=false) gate and the `since`/region of the active alert for its region-latch
  orchestration — facts, not formulas.
- **A third consumer does the same thing:** `WidgetUpdater.computeWidgetSnapshot` builds its
  own `ThreatEngine` and calls `.evaluate(...)` on the same 30s clock it samples from, for the
  same reason — the widget must render current state independent of whether the UI process is
  alive. No fourth implementation exists anywhere.

### Sub-packages, not flat

The UI/domain/widget layers share the root package; the subsystems (`engine/ source/
connection/ service/ theme/ data/`) have real sub-packages (Package structure). Import
ceremony between them is the cost of isolation; the engine kernel stays free of app-layer
types.

### Foreground service instead of backend/push

No intermediate server buffers anything, so nothing is missed server-side — but the app must be
kept alive (hence the battery-exemption flow), and alerts stop when monitoring stops.

### Direct third-party API dependency

NEPTUN is consumed directly, parsing isolated in `Threat.kt`. Risk: upstream schema/contract changes. The REST/WS merge protects against
CDN-cached staleness.

### `UserPrefs` — resolved: state split out, preferences remain wide (RESOLVED, formerly "`ZonePrefs` is becoming a god object")

The persisted-state half of this risk is fixed: `ConnectionLog`, offline-restore state, and
the official-announced episode identity no longer live in the preferences store — they moved
to `ServiceState` (see module map, State / orchestration), which is exactly the
`ConnectionLogStore`-style split this section used to propose, just broader in scope and
never under that name. `UserPrefs` (the renamed `ZonePrefs`) now holds only user-facing
preferences: toggles, thresholds, language, follow/pin, night config, icon packs, and the
`wizard_completed`/`boot_restart_enabled` flags.

What remains: ~74 preference keys in one file. That's wide, but it maps to a genuinely wide
settings surface (`SettingsScreen.kt`'s language / per-type toggles / icon packs / card size /
Just Fun / night-mode / updates / battery / shelter sections) and no longer mixes concerns —
it's no longer a "god object" in the sense that mattered (state vs. preferences), just a large
preferences file. A `MapPrefs`/`ThreatPrefs`/`AlertPrefs`/`NightPrefs`/`UiPrefs` split remains
an option if the file gets harder to navigate, but it's a nice-to-have now, not a risk.

## Failure modes

| Failure | Behavior | Owner |
| --- | --- | --- |
| NEPTUN offline | Offline pill + monitor notification with Retry; `registry.retryNow()`; last-known oblast alerts held; drop persisted (`offline_pending_since`) across restarts; reconnect start persisted for milestone continuity. | `AppSources`/`NeptunSource`, `AlertService` |
| Stale threats | Dimmed on map (alpha 0.45); excluded from tiers/alerts/strip/gauge; ghosts removed after ~30 min. | `engine/ThreatEngine.kt` + consumers |
| Service process interrupted | Recovery depends on Android's foreground-service lifecycle; connection log + debug log restored from DataStore when restarted. | `AlertService`, DataStore |
| Monitoring silently dead (service stopped, app open) | The NEPTUN socket keeps working (owned by the process, not the service), so the map looks healthy while alerts/notifications are off. `MonitoringStatus.running` flips false → `UiState.protectionState` becomes `OFFLINE` and the whole header becomes a red "SERVICE OFFLINE — tap to reactivate" banner. `AlertWatchdog` (WorkManager 15-min periodic) restarts the service if it died while the process was alive. | `MonitoringStatus`, `AlertWatchdog`, `MainScreen` |
| Reboot | `BootReceiver` restarts the service on `BOOT_COMPLETED` (unless the user disabled `boot_restart_enabled` in Settings). When not auto-restarted, a persistent "Monitoring paused" notification is posted. `AlertWatchdog` is always scheduled. | `BootReceiver`, `AlertWatchdog` |
| Package replaced | `BootReceiver` restarts the service on `MY_PACKAGE_REPLACED` (same gate). | `BootReceiver` |
| Location unavailable | Follow-me uses the last-known fix; no fix at all → country-wide focus + "No GPS fix" warning; pinned only when not following. | `LocationTracker`, `Cities` |

## Testing

JUnit unit tests in `app/src/test/java/ua/ukrainedrones/`. Invariant → test: the engine
(`zoneTier`, `predictPosition`, staleness/ghost, scoring, official alerts) is pinned by
`ThreatEngineTest`, which both UI and service consumers rely on.

- `engine/ThreatEngineTest.kt` — `evaluate` zoning, `zoneTier` tiering, `predictPosition`,
  `motionHeading`, `isStale`/`isGhost` caps, scoring, AVIATION override, null-speed fast,
  official-alert outputs (scoped/raw gate, `redCities`, reason).
- `OblastAlertScopeTest.kt` — `inOblast`/`isOblastWide`/`coversCity`/`officialAlertActiveFor`
  matching rules (the `engine/OblastAlert.kt` gates).
- `engine/TypeMappingTest.kt` — `ThreatType`↔String, `Threat`↔`NormalizedThreat` round trip.
- `source/SourceRegistryTest.kt` — source merging, `typeCatalog`.
- `CitiesTest.kt` — city-list integrity; majors-only `nearestCity`/`resolveFocus`.
- `ThreatTest.kt` — JSON parsing, type mapping, course translation.
- `TransliterationTest.kt` — КМУ №55 romanization, no semantic translation, digraph rules.
- `UpdateManagerTest.kt` — `versionNameGreater`.
- `NeptunClientTest.kt` — reconnect backoff (`ResilientConnectionSupervisor.backoffDelayMs`), `ConnectionState` degradation.
- `NightModeTest.kt` — night-window resolution + effective params/armed.
- `ConnectionLogTest.kt` — episode-commit rules (grace window, blips, recovery, ring-buffer cap).
- `DebugLogTest.kt` — serialize/parse round trip, ring-buffer cap, auto-clear, `computeSweep` verdicts (fired/coalesced/bell-muted, steady-state dedup, tier escalation, exits, stale/type-off region rows).
- `VibrationTest.kt` — `vibrationPattern` levels.
- `StringsFormatTest.kt` — `formatDateTime` per-language correctness.
- `TestThreats.kt` — shared `threat(...)` builder helper.

Run: `.\gradlew.bat :app:testDebugUnitTest`

## Build & release

- `.\gradlew.bat :app:assembleDebug` — debug APK (no secrets needed).
- `.\gradlew.bat :app:release` — bumps version, builds release APK, uploads APK + generated
  `version.json` over FTP. Requires git-ignored `app/keystore.properties` (signing) and
  `app/upload.properties` (FTP creds). Release notes from `notes_en.txt` / `notes_ua.txt`.
- Full release workflow is documented in `AGENTS.md` ("release it").

## State plumbing / recomposition contract (perf)

How hot data reaches the UI without recomposition storms (added 2026-08; keep in sync with
MainViewModel.kt):

- **UI samples the stream.** MainViewModel reads `combine(connectionState, threats, alerts).sample(120)` for the UI path only; AlertService keeps consuming the raw flows.
- **Clock is not state.** There is no wall-clock StateFlow on the ViewModel — a global 1s ticker
  recomposed the whole tree for nothing. `lastFrameAt` is a flow collected only by the connection
  sheet; the shelter screen's GPS fix-age label runs a 10s local clock scoped to its own
  composition (it only ticks while that screen is open); the map's marker smoothing loop keeps its
  own 1s tick. None of these are fields of UiState, so a no-op tick never rebuilds the tree.
- **Pointer-first selection is not UiState either.** Selected threat / proximity / neutralized card /
  fake-neutralize live in `selectionUi: StateFlow<SelectionUi>` (+ derived `selectedThreatId`
  for the map marker highlight). `selectionUi` uses a two-tier pipeline: it emits the threat shell
  immediately on tap (Frame 0) without waiting for background `uiState` recomputation, then enriches
  proximity and zoneTier asynchronously. Only `ThreatCardHost` collects it, so tapping a threat never
  recomposes header/map/footer. FlourishPolicy gating is evaluated in that chain.
- **Off-main state building.** The uiState combine chain ends in .flowOn(Dispatchers.Default)
  before stateIn; uildUiState must stay free of main-thread/Android-UI dependencies.
- **Stability.** Model classes passed to composables (Threat, ThreatProximity, City,
  ZoneParams, LatLng, night types, flourish types, RevealRequest) are annotated
  @Immutable; UiState itself too. MapView's overlayKey is memoized via
  
emember(<fields it reads>); staleness dimming happens in-place in the 1s marker loop,
  never through the key.
- **Popup clock is a leaf.** ThreatPopupCard's elapsed-time text comes from
  ThreatElapsedText, a small composable with its own 1s clock — the card body does not
  recompose per second.
