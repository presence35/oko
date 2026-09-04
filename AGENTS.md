# Oko — Release Workflow

## Trigger phrase: "release it"

Only when the user says **"release it"**, perform a full release:

1. Read the `## [Unreleased]` entries in `CHANGELOG.md`.
2. Infer the new version from `app/version.properties`.
3. Show the inferred version + the notes that will be generated (from the CHANGELOG entries) and wait for confirmation ("go"). Upload only after confirmation.
4. Run the single command (no args — the version auto-bumps its patch, e.g. 0.3.8 → 0.3.9; use `-PnewVersion=<ver>` only for an explicit override):
   - `.\gradlew.bat :app:release`
5. Verify the live result at `https://odesaplay.com.ua/other_apps/ukrainedrones/version.json` (version + both translations).
6. Move the released entries under a new `## [<ver>]` heading in `CHANGELOG.md` and clear `## [Unreleased]`.

## While working

- Find elegant solutions, not the easy code.
- Append user-visible changes to `CHANGELOG.md` under `## [Unreleased]` as you go, so any session can release them.
- Changelog entries are short one-liners: `- EN text / UA text`. Each line is split on the first ` / ` to produce the release notes for `version.json`. No multi-paragraph essays.
- The server `version.json` is generated from `app/version.properties` (versionCode/versionName) plus the `## [Unreleased]` entries in `CHANGELOG.md` (auto-derived at release time). FTP creds live in `app/upload.properties` (git-ignored).
- Version numbers: `versionCode` is a monotonic integer; `versionName` is human-readable. Keep both bumped together (the `bumpVersion` task does this).

## Development conventions

Read `BEHAVIORS.md` before any engine work — it is the source of truth for the threat
evaluation contract. Read `ARCHITECTURE.md` for module map and data-flow context.

### Engine conventions

- **No mirror rule.** UI and service call `ThreatEngine.evaluate()` — one call site,
  no duplicated logic. See `BEHAVIORS.md` for the contract.
- **Source-agnostic.** Engine works with `NormalizedThreat` and `ThreatProps`. Never
  touches NEPTUN JSON or source-specific formats.
- **Plugin-provided type properties.** `ThreatProps` come from the active plugin.
  Engine defaults exist for unknown types. Never hardcode type names in engine logic.
- **Explicit `now` parameter.** All time-dependent functions take a timestamp.
  Enables deterministic testing.
- **Haversine for distance.**
- **Speed cache is engine-internal.** Not a global singleton. Consumers never touch it.
- **Dark-only theme.** Theme is a plugin interface; only dark ships for now.
  Never hardcode theme assumptions in the engine.

### Coding conventions

- Minimal patches; don't rewrite whole files for small changes.
- Don't add comments unless asked.
- UA/EN text goes through `Strings` (`Strings.get(lang).StringSet`), not Android resource
  localization.
- **EN-only strings during normal work.** Do NOT translate new strings to UA — write only the
  EN text (put it in the UA slot too as a placeholder so `Strings` compiles). A dedicated
  "translate" command/session fills real UA later--Saves tokens.
- **Editing files with non-ASCII text** (Cyrillic — `Strings.kt`, `Cities.kt`, etc.): never use
  raw `Get-Content`/`Set-Content` in PowerShell 5.1 — it reads/writes ANSI and corrupts UTF-8
  (mojibake + adds a BOM). Use .NET instead:
  `$u = New-Object System.Text.UTF8Encoding($false)`; `[System.IO.File]::ReadAllText($f, $u)` /
  `[System.IO.File]::WriteAllText($f, $text, $u)`.
- User settings/prefs go through `UserPrefs` (`domain/UserPrefs.kt`, DataStore-backed); don't add a second prefs store.
- Backwards compatible code, or migrating old users is not a concern -- we're in beta mode still.

### Always build/verify before finishing

After a meaningful code change, verify before declaring the task done:

- `.\gradlew.bat :app:assembleDebug`
- `.\gradlew.bat :app:testDebugUnitTest` — when touching engine logic
  (`ThreatEngine`/`NormalizedThreat`/`ThreatProps`/`SpeedCache`).

Fix any failures before finishing.

### Never paste full logs or data blobs

- Summarize build output, errors, and stack traces rather than dumping them into the chat.
  Point to `file:line` or the saved tool-output file instead of reproducing it inline.
- Don't paste large API/JSON payloads or long log dumps into responses.

### Keep ARCHITECTURE.md current

When you add a source file or change a documented invariant, update the module map /
key-invariants section of `ARCHITECTURE.md` in the same change, so the docs never rot.

### Repository state

Single squashed history on `main` containing only the refactored app — the
pre-refactor project and the `refactor/` scaffold are gone. The refactor plan
lives in `BEHAVIORS.md` under "Session Status".
