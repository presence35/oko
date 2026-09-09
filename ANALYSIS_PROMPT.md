# Codebase Analysis Prompt — Ukraine Drones (Oko)

You are analyzing the **Oko** Android app — a live air-threat map for Ukraine.
Your task is to perform a thorough, multi-dimensional analysis of this codebase.

## Project Overview

Single-module Android app (`:app`) — a live air-threat map. Jetpack Compose
(Material 3, dark-only) + OSMdroid. Kotlin 1.9.24, JDK 17, minSdk 26 /
targetSdk 35, namespace `ua.ukrainedrones`. No backend — data comes from the
public [NEPTUN](https://neptun.in.ua) API (WebSocket stream + REST).

## Key Documentation

Read these three files **in full** before analyzing any source code:

1. **`ARCHITECTURE.md`** — Technical map: package structure, system overview,
   core ownership table, data flow, module map (every file with responsibility),
   key invariants, ownership boundaries, deliberate tradeoffs/risks, failure modes,
   testing strategy, state plumbing/recomposition contract.

2. **`BEHAVIORS.md`** — Engine behavioral contract: inputs/outputs, threat model
   (`NormalizedThreat`, `TrailPoint`), type properties (`ThreatProps`), all pure
   functions (`evaluate`, `zoneTier`, `predictPosition`, `motionHeading`,
   `canDrift`, `distanceHaversine`, `etaMinutes`, `scoreThreat`,
   `aggregateScores`, `officialAlertActiveFor`, `deriveOfficialAlertReason`,
   staleness lifecycle, speed cache), non-negotiable constraints, consumer
   behaviors (Alert Service, UI, Flourish, Logging), plugin contract.

3. **`AGENTS.md`** — Release workflow, development conventions (coding rules,
   engine conventions, file editing rules for non-ASCII), build/verify steps,
   state plumbing, agent routing profiles.

## What to Analyze

Produce a structured report covering these dimensions:

### 1. Architecture & Modularity
- Assess the package/sub-package structure. Is isolation clean? Are there
  any leaks of abstraction between layers (engine ↔ UI ↔ service)?
- Evaluate the "no mirror rule" — do `MainViewModel` and `AlertService`
  truly both call `ThreatEngine.evaluate()` without duplicating logic?
- Check the plugin architecture (`ThreatSource`, `PluginRegistry`, `AppPluginHolder`).
  Is the SPI clean? Are there hardcoded type names anywhere in engine logic?

### 2. Engine Correctness
- Trace the full `evaluate()` pass: does it match BEHAVIORS.md spec?
- Verify `zoneTier` follows the fast-ETA vs slow-distance rule correctly.
- Check `predictPosition` gates (`canDrift = !isStale && flying`, server-coursed only).
- Verify `scoreThreat` multiplicative formula and `aggregateScores` diminishing returns.
- Check `officialAlertActiveFor` scope gate and `computeRedCities` region-precision.
- Look for any deviation from the "non-negotiable constraints" in BEHAVIORS.md.

### 3. Data Flow & State Management
- Trace NEPTUN WS + REST merge in `NeptunConnectionClient`. Is the generation-based
  lifecycle correct? Are the separate StateFlows (`connectionState`, `threats`,
  `alerts`, `removedThreats`) properly isolated?
- Check `PluginRegistry` takeover semantics. Does the priority-ordered source
  merging work correctly? Is the `wsHealthy`/`degraded`/`isOffline` three-tier
  connection state consistent?
- Verify the state plumbing/recomposition contract: `sample(120)`, no global wall-clock
  StateFlow, `selectionUi` separation, `@Immutable` models.

### 4. Concurrency & Threading
- Are coroutines/flows used correctly? Any potential race conditions?
- Is the `SpeedCache` truly thread-safe and engine-internal (not a singleton)?
- Check that `buildUiState` stays free of main-thread/Android-UI dependencies.
- Verify monotonic clock usage for in-process ephemeral deltas.

### 5. Testing Coverage
- List the test files and assess what each covers.
- Identify gaps: are there engine behaviors without tests? Edge cases untested?
- Check that `ThreatEngineTest` pins the full behavioral contract.

### 6. Performance
- Evaluate the recomposition strategy: sample rates, clock scoping, selection
  isolation, `@Immutable` annotations, `flowOn(Dispatchers.Default)`.
- Check map rendering performance: 30fps marker loop, glide, death animation
  concurrency caps (`MAX_DEATHS = 14`), bitmap caching.
- Any obvious performance bottlenecks or unnecessary recompositions?

### 7. Code Quality & Conventions
- Check adherence to AGENTS.md conventions (EN-only strings, `Strings` usage,
  no Android resource localization, .NET for non-ASCII file edits).
- Are there any god objects? (ZonePrefs is flagged as a candidate — assess.)
- Minimal patch philosophy — any places where a small change would require
  rewriting a large file?
- Are comments used sparingly (only where non-obvious)?

### 8. Security & Secrets
- Check how secrets are handled (keystore.properties, upload.properties,
  carto.properties, telegram.properties — all git-ignored).
- Are any secrets hardcoded or leaked in build configs?
- Review the `TELEGRAM_BOT_TOKEN` / `CARTO_API_KEY` buildConfigField usage.

### 9. Potential Bugs & Risks
- Identify any likely bugs based on the documented invariants and failure modes.
- Check the `ZonePrefs` god-object concern — what are the actual risks?
- Assess the "monitoring silently dead" failure mode — is `MonitoringStatus`
  correctly wired?
- Check the official-alert region-latch implementation for edge cases.

### 10. Update & Release Pipeline
- Evaluate the `bumpVersion` + `release` + `uploadRelease` Gradle tasks.
- Check the version.json generation logic.
- Assess the FTP upload approach for reliability/security.

## Deliverable

Produce a structured report with:
- **Summary** (top-line assessment)
- **Findings by dimension** (numbered sections above, each with specific
  file:line references where applicable)
- **Critical issues** (must-fix)
- **Suggestions** (nice-to-have improvements)
- **Codebase health score** (1-10 with justification)

Be specific — cite file paths and line numbers from the source code.
Don't pontificate; flag only things you can verify.

## Source Code Location

All source files are under `D:\Desktop\oko\app\src\main\java\ua\ukrainedrones\`
and `D:\Desktop\oko\app\src\test\java\ua\ukrainedrones\`.

Build files: `D:\Desktop\oko\app\build.gradle.kts`, `D:\Desktop\oko\build.gradle.kts`.

Key entry points: `MainActivity.kt`, `MainViewModel.kt`, `AlertService.kt`,
`engine/ThreatEngine.kt`, `connection/NeptunConnectionClient.kt`,
`plugins/PluginRegistry.kt`.

## Constraints

- Do NOT run the build or tests — this is a static analysis.
- Do NOT make code changes.
- If you find a file that doesn't exist or a class that can't be located,
  note it as a potential documentation gap.
