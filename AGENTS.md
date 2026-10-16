# Oko — Release Workflow

## Trigger phrase: "release it"

Only when the user says **"release it"**, perform a full release:

1. Read the `## [Unreleased]` entries in `CHANGELOG.md`.
2. Infer the new version from `app/version.properties`.
3. Show the inferred version + the notes that will be generated (from the CHANGELOG entries) and wait for confirmation ("go"). Upload only after confirmation.
4. Run the single command (no args — the version auto-bumps its patch, e.g. 0.3.8 → 0.3.9; use `-PnewVersion=<ver>` only for an explicit override):
   - `.\gradlew.bat :app:releaseDirect` — sideload/beta path: builds the APK and uploads it + `version.json` to the FTP server.
   - `.\gradlew.bat :app:releasePlay` — Google Play path: builds the `play` App Bundle only (no self-update, no upload).
5. The upload already re-fetches the live `version.json` and fails if the version/digest don't match what was just built; still eyeball it for version + both translations.
6. Move the released entries under a new `## [<ver>]` heading in `CHANGELOG.md` and clear `## [Unreleased]` + mm-dd_h:m:s.
7. Tag the release commit `git tag <versionName>` (no `v` prefix) and push it. F-Droid's update checker uses `UpdateCheckMode: Tags` with no pattern, so the tag *is* the build commit — a missing or `v`-prefixed tag silently stops F-Droid updates. Also refresh `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` (max 500 chars, one per locale) — that is what the F-Droid listing shows for the release, and it is not auto-derived from this changelog.
8. A release that fails after the bump puts `app/version.properties` back by itself — `bumpVersion` snapshots the file first and `restoreVersionOnFailure` restores it unless the release published. So a red test or a rejected upload leaves the version where it was, and re-running the same command still produces the version you expected rather than skipping one.

## While working

- Never look at more files than you need to, esp if I tell you specifically what to touch. Skip verify for pure-docs/string-only edits"
- Always find elegant solutions, not the easy code!
- Append user-visible changes to `CHANGELOG.md` under `## [Unreleased]` as you go, so any session can release them. Be highly brief. Add mm-dd_h:m:s.
- Changelog entries are short one-liners, EN only: `- EN text mm-dd_h:m:s`. UA/RU release notes fall back to EN at release time. No multi-paragraph essays.
- The server `version.json` is generated from the **built APK** (`output-metadata.json` → versionCode/versionName, plus the APK's `sha256`) and the `## [Unreleased]` entries in `CHANGELOG.md` (auto-derived at release time). `uploadRelease` cross-checks the version against `app/version.properties` and fails on drift, uploads the APK before the manifest, then verifies the live copy. FTP creds live in `app/upload.properties` (git-ignored).
- Version numbers: `versionCode` is a monotonic integer; `versionName` is human-readable. Keep both bumped together (the `bumpVersion` task does this).
- **Always translate UI strings.** Every new `Strings` entry gets real UA and RU text in the
  same change, matching the existing tone of each language file — never EN placeholders.
- Reply with short, concise, only-needed! No long explanations

## Development conventions

Read `BEHAVIORS.md` before any engine work — it is the source of truth for the threat
evaluation contract. Read `ARCHITECTURE.md` for module map and data-flow context.

### Engine conventions

- **No mirror rule.** UI and service call `ThreatEngine.evaluate()` — one call site,
  no duplicated logic. Official-alert facts (`officialAlertActiveFor` gate, `redCities`,
  reason) are also engine-owned (`engine/OblastAlert.kt` + `ThreatEngine`); consumers only
  orchestrate. See `BEHAVIORS.md` for the contract.
- **Source-agnostic.** Engine works with `NormalizedThreat`, `ThreatProps` and `OblastAlert`
  (the alert currency). Never touches NEPTUN JSON or source-specific formats.
- **Plugin-provided type properties.** `ThreatProps` come from the active plugin.
  Engine defaults exist for unknown types. Never hardcode type names in engine logic.
- **Explicit `now` parameter.** All time-dependent functions take a timestamp.
  Enables deterministic testing.
- **Dark-only theme.** Theme is a plugin interface; only dark ships for now.
  Never hardcode theme assumptions in the engine.

### Coding conventions

- Minimal patches; don't rewrite whole files for small changes.
- Don't add comments unless asked.
- UA/EN text goes through `Strings` (`Strings.get(lang).StringSet`), not Android resource
  localization.
- **Editing files with non-ASCII text** (Cyrillic — `Strings.kt`, `Cities.kt`, etc.): never use
  raw `Get-Content`/`Set-Content` in PowerShell 5.1 — it reads/writes ANSI and corrupts UTF-8
  (mojibake + adds a BOM). Use .NET instead:
  `$u = New-Object System.Text.UTF8Encoding($false)`; `[System.IO.File]::ReadAllText($f, $u)` /
  `[System.IO.File]::WriteAllText($f, $text, $u)`.
- User settings/prefs go through `UserPrefs` (`domain/UserPrefs.kt`, DataStore-backed); don't add a second prefs store.
- Backwards compatible code, or migrating old users is not a concern -- we're in alpha mode still.

### Always build/verify before finishing

Only after a meaningful code change, verify before declaring the task done:

- `.\gradlew.bat :app:assembleSideloadDebug` — the compile gate, ~1 min (configuration cache on).
  `assembleDebug` builds both flavors and costs double for nothing here.
- `.\gradlew.bat :app:testSideloadDebugUnitTest` — when touching engine logic
  (`ThreatEngine`/`NormalizedThreat`/`ThreatProps`/`SpeedCache`). Add `--tests "*AlertNotificationManagerTest*"`
  style filters while iterating; the bare task name `testDebugUnitTest` is ambiguous across the
  `play`/`sideload` flavors and fails. The full suite runs automatically before any release —
  don't pay for it per change.

Fix any failures before finishing.

### Never paste full logs or data blobs

- Summarize build output, errors, and stack traces rather than dumping them into the chat.
  Point to `file:line` or the saved tool-output file instead of reproducing it inline.
- Don't paste large API/JSON payloads or long log dumps into responses.

### Keep ARCHITECTURE.md current

When you add a source file or change a documented invariant, update the module map /
key-invariants section of `ARCHITECTURE.md` in the same change, so the docs never rot.

Reply with direct, short, only required lingo, bullet points, choices clearly, explanations just the key points.


