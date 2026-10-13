# Roadmap

Planned-but-not-started features, tracked so a future session can pick them up. Nothing here is
committed to a release.

## App intensity — ready-made setups (implemented, kept as the design record)

The app is deliberately paranoid about real-world failure: background survival, offline handling,
aggressive sound, restart-after-reboot. That is correct near the front line and absurd in a living
room, and the switches that throttle it were scattered across four settings pages. The closing
wizard page now says so out loud ("built for bad conditions… turn it down in Reliability"), so the
remedy had to be reachable without reading that sentence literally.

**Personas, one shot each.** `AppIntensity` = ALWAYS_READY / BALANCED / QUIET / WATCHING. Picking
one writes the fields in `IntensityPatch` and is then forgotten — no live mode, because a profile
that re-asserts itself silently undoes hand-tuning, which is the one thing a settings screen must
not do. The same four cards are offered again in Settings → Reliability, because the alpha note
tells people to go turn things down and hunting four pages is not "turning it down".

What each owns, and what none of them may touch:

| | Aware | Normal | Quiet | Watching |
|---|---|---|---|---|
| always sound / bypass silent | on / on | off / off | off / off | off / off |
| critical-offline override | on | on | on | **off** |
| restart after reboot / periodic GPS | on / on | on / off | on / off | **off / off** |
| battery ask | armed | armed | armed | **silenced** |
| repeat-sound policy | every change | once per threat | digest 10 min, per type, max 5 | digest 60 min |
| rings armed | all 4 | fast-yellow off | fast-yellow off | **all 4 off** |
| night window | **all 8 bells armed, both siren overrides on** | untouched | untouched | untouched |
| types that ring | all | all | **all but aviation/recon/unknown** | **all but unknown** |
| follow bullet / HQ blasts | on / on | on / on | **off / off** | **off / off** |
| death animation | advanced | advanced | **simple** | classic |
| shelters | on | on | on | **off** |

Map visibility is never in the patch — a persona decides what rings, never what is drawn. Neither
is morale, the tallies, language, the pinned city, map appearance, or the **zone radii**: the rings
are the user's own reach, and a preset tap silently resizing them was a change nobody asked for
(removed after shipping exactly that surprise). Someone who picks "Quiet" still gets the
neutralized counter, because that counter is the cheap thirty-second payoff and the effects are the
expensive noise.

**The night window is claimed, not stated** — `IntensityPatch.night` is the one nullable field,
`IntensityNight`, set only by ALWAYS_READY (all eight bells armed, both siren overrides on). Every
other preset must leave it `null`, or applying a setup would silently cancel the user's own "Just
let me sleep!"; when a patch does claim it, the writer drops that sleep snapshot, the same rule
`UserPrefs.editNightBell` already follows for a hand edit. `AppIntensityTest` asserts both halves,
including that the ALWAYS_READY night preset resolves to `isMuted == false`.

**The trap this surfaced, and the fix.** `NotifyPrefs.from(enabled, preset, …)` maps the
repeat-sound master **off** to `EVERY_CHANGE`, and the stored default left that master off — so the
stored `zonePolicy = ONCE_PER_THREAT` never ran and every install rang on every single update while
the pref said otherwise. Fixed at the source: `UserPreferences.notifyPolicyEnabled` now defaults to
**on**, so the default is one bell per threat (re-bell on reaching the red ring, every escalation
still sounds). Every patch still states the master explicitly rather than relying on the default,
and two tests now pin the pair: `AppIntensityTest` asserts that the shipped default *resolves* to
`ONCE_PER_THREAT` through `NotifyPrefs.from`, and that `Normal` equals the default field for field.

## Tell the user the app was asleep, and push for the battery exemption (notice SHIPPED)

The notice is built and opt-in: Settings -> Reliability -> "Asleep notice" (`sleepNoticeEnabled`,
default OFF). This section is kept as the measurement record and for what remains, which is no
longer about building the notice.

**The measurements.** From sent logs (2026-10-03/05, Pixel 7 / Android 17 and SM-A505FN /
Android 11): with the screen off and the device idle the whole process is CPU-suspended by Doze for
5-6 minutes at a stretch. Measured off the power timeline and the decision log, the app was blind
for 48% of the day on the Samsung and 17% on the Pixel, longest blind window 39 min and 22 min, on
301 and 165 stalls. Not a bug in the loop: every gap has `interactive=false, deviceIdle=true`, and
the 466/634/469/405/450 s stalls contain zero power samples AND zero connection-log entries, so the
process is suspended end to end. Only the two long stalls (2476 s, 1025 s) contain connection
events, and those are the Doze maintenance windows where the CPU came back. One demonstrated miss on
the Samsung is a `ZONE_ENTER/FIRED` 7.8 min after a 7.8 min blackout.

**`SLEEP_GAP_REBASE_MS` is a correct detector; do NOT lower it.** The wake tick is not skipped:
`resumeAfterSleep` drops only the deltas that cannot be trusted (`lastZoneTiers`, `lastSweepAtMs`,
`emptySince`), and that same tick then decides from a clean slate. A stall therefore shows up as a
gap BETWEEN two decisions, not as two gaps. Lowering the threshold to ~90 s would push ordinary
hiccups onto the rebase path and discard hysteresis for nothing.

**Shipped:** the typed `SleepWindow` / `SleepEvidence` / `SleepLedger` fact; the pure `SleepPolicy`
(`decide` ordered so a switched-off feature, an unreadable feed, a sub-floor gap and a harmless
window are all `Silent` before the mute is consulted; a raid mute `Hold`s rather than drops; a
granted exemption `Dismiss`es); server-time evidence anchored on the feed's newest timestamp across
threats AND alerts, so no clock skew can fabricate or hide a miss; one reused notification id, so
everything coalesces into one updating row; `setTimeoutAfter(12 h)` for retirement; and the opt-in
switch. The retirement replaced the original "clear it when the app is opened" idea on purpose:
clearing on `onResume` would destroy the only reminder for an overnight event every time the user
glanced at their phone. The notice's button now opens the OEM's own battery/autostart screen when
the device has one (`BatteryOptimization.exemptionIntent`), so `requestExemptionOemAware` finally
has a production caller too; the two functions share one table but keep DIFFERENT fallbacks on
purpose, because a `PendingIntent` cannot catch a missing component at tap time and therefore
pre-checks the vendor package is installed, while the imperative path tries and catches.

**Remaining:**

- **Let the measured sleep history choose the copy instead of the vendor string.**
  `getOemInfo()`'s manufacturer table exists only to pick a screen, and `isAggressive` changes one
  *title adjective*: the EN and UA bodies are byte-identical and the click path is identical. The
  KDoc that claimed a Pixel returns `isAggressive = false` is corrected, and `hasAutoStartIntent` is
  read only by `BatteryOptimizationTest`, never in production.
- **Keep the loud path off the battery.** A wake lock held across the idle tick, or
  `setExactAndAllowWhileIdle` on the official-alert path, are the only things that shrink the
  windows themselves. They cost battery, which is the trade this app cannot afford to lose. Try them
  only if the exemption ask proves insufficient, and measure on the Samsung (worst by 3x).
- **Validate the notice on a device.** Build 780 (Ukraine) has 47 `PAUSED` rows, 34 at or above the
  4-min floor, and no `REASON_CRASH`, so the path runs ~47 times without crashing, but nothing in
  the bundle can show whether the row rendered. The Samsung (65.5% blind, longest 35 min) is where
  "one permanent row" needs looking at.

Open question: is the promise "we will not sleep through an official alert" (achievable with an
alarm path) or "we will not sleep at all" (not achievable on an OEM-throttled Android 11 without
the exemption)? The copy depends on the answer.

## Inbound approach model (staging follow-up)

Inbound tracks are now re-tagged by a consumer-side policy (`domain/ThreatBehavior.kt`:
`isInbound`/`stageInbound`, applied by `MainViewModel` + `AlertService`): a source-named
destination inside the yellow ring makes a track inbound — slow reads OUTER, fast still sounds
red — and both ride the yellow ring instead of landing on your position. Remaining work for the
full design (worked out 2026-09-26):

- Split facts from policy: the engine reports raw data, a policy layer owns the classification,
  so the widget (and any future consumer) stages too — today only the app + service do.
- User setting: "an incoming threat means red or yellow" (default yellow), mapped plugin-side
  like `NotifyPrefs.from` rather than a hardcoded slow/fast rule.
- Uncertainty edge: tier and place approximate tracks by the far edge of `uncertaintyKm`, so an
  uncertain fix cannot claim overhead even without a named destination.
- Widen "inbound" beyond a named destination (a course that genuinely points at the focus), with
  the same geometry shared to the widget.

## Multi-city official-alert monitoring (TL;DR)

Replace the single-focus alert model with a tappable watch set: a "Monitor cities" card grid in
Settings (26 MAJOR cities, tap on/off, pin auto-included). Each watched city is
checked per tick with the existing shared gates (`officialAlertActiveFor`/`coversCity`) — official
alerts only; zone tiering stays tied to GPS. Siren + city-named notification per new
region onset, all-clear chime per region end (remaining cities rewrite the notification). UI banner
gains a display-only "also in alert" line; widget's official-alert status covers focus ∪ watch set.
Core work: per-region episode latching in `AlertService` (replaces the single
`officialAnnounced*`/`officialRegionToken` state) driven by a pure, tested policy
(`domain/OfficialWatch.kt`); mirror-side computation in `MainViewModel`. Full plan with file list
was worked out 2026-08-26 (see session notes / this entry).


## Rotate the FTP/upload password

`app/upload.properties` holds the server password in plaintext (it is git-ignored, but it has
sat in chat logs, in build output, and on every machine that has ever run a release). Rotate the
FTP credential, then update that file. Worth doing whenever credentials appear in a transcript.

## Harden the log drop box (`server/upload.php`)

The beta log endpoint is deliberately unauthenticated — a token baked into the APK is extractable,
so it guards nothing while adding a setup step to every tester. As shipped it has a per-IP rate
limit, a body cap, a JSON-shape check, a sanitized `.json` filename in a fixed out-of-docroot
directory, and a 90-day prune. Still worth revisiting if the tester group grows: per-IP limits
assume clients can't rotate addresses, and the endpoint is publicly reachable on the same host
that serves the APK.

## Bypass DNS blocking on filtered networks (not started)

Some networks refuse to resolve the feed's name while the host itself is fine — reported on a
Moldova Airbnb Wi-Fi (2026-10-13), where `neptun.in.ua` fails in Chrome and the app alike and only
mobile data works. The app already *detects* it (`OfflineCause.DnsFailure` minted from
`UnknownHostException`, rendered as "Server unreachable / DNS blocked — switch network") but cannot
bypass it, and that state's own KDoc says retrying is pointless.

Fix: a `ResilientDns : okhttp3.Dns` wired into the feed client
(`ResilientConnectionSupervisor:92`, add `.dns(ResilientDns())`). The system resolver stays
authoritative and is tried first; only on `UnknownHostException` do we re-query over DoH at an IP
literal — `https://1.1.1.1/dns-query` / `https://8.8.8.8/dns-query` (their certs carry the IP as a
SAN), so the fallback itself needs no name resolution, the exact deadlock it exists to break. Query
`?name=<host>&type=A` with `accept: application/dns-json`, parse `Answer[type==1]`, order results
IPv4-first (a black-holed IPv6 route plus `retryOnConnectionFailure(false)` and a 10s connect means
one bad AAAA eats the whole attempt). A healthy network pays nothing.

Caveat: only helps when the block is DNS-level. If the router filters the Cloudflare IPs themselves,
DoH-by-IP fails too — unconfirmable on that network now. Unit-testable with a fake system `Dns` and
no network: system-refuses → DoH answers → addresses returned; IPv4-first ordering; both resolvers
refuse → `UnknownHostException`.

## We need to track if neptun.in.ua changes their API hash
neptun.in.ua/sdk/build-manifest.json

// In UpdateManager's daily check, add:
ApiMonitor.checkForChanges(context)

Also log unkonwn threat types
// In your Threat.fromJson() or wherever you parse type
val typeRaw = json.optString("type", "unknown")
val type = typeRaw.toThreatTypeOrUnknown() // falls back to UNKNOWN instead of crashing
###

## In-app tutorial popup (TL;DR)

Lightweight tutorial dialog that appears on ~10th app open or day 3 (whichever comes first),
explaining core concepts the first-launch wizard doesn't cover:

- How advisory vs non-advisory threats work (amber badge = observation, not danger)
- Why some threats show at oblast level (areaOnly — amber dot, no precise point)
- How the connection status pill works
- That Telegram channels are faster than official air-raid alerts
- How zone alerts differ from official air-raid alerts

Implementation: new pref key (`tutorialShownCount`/`tutorialShownAt`) in `UserPrefs`, check in
`MainViewModel.init` or `MainScreen` composition. Simple AlertDialog with swipeable pages or
single rich-text dialog. Steer users to Settings → Feature Guide at the end. Strings in
`Strings.kt` (UA + EN).

## Threat clustering at low zoom (TL;DR)

When multiple threats overlap at low zoom levels, show a single count badge instead of 10
stacked icons. Zoom in reveals individual threats. Requires a spatial index or grid-based
grouping in the map rendering layer (`MapView.kt`). Could piggyback on the osmdroid → MapLibre
migration or be done standalone with a simple grid-bucket approach.

Timer in footer "how long this alert has been active"
Fill settings pills: city name, fill, border only

## Deferred alert-path follow-ups (only if reproduced)

Two known-weak spots found while auditing the all-clear/episode path (2026-10-06). Neither is a
correctness bug today; both change product behaviour, so they wait for a real report.

- **Zone-empty grace is one tick.** `AlertService.ALL_CLEAR_GRACE_MS = 0L`: the zone-empty path
  cancels the alert notification and releases the raid mute on the second consecutive empty tick
  (~1 s while alerting), because `emptySince` already debounces once. A GPS/feed flicker that
  blinks a threat out and back can therefore blink the notification off and re-post it. Raise it
  (5 s was the original value) only if a field report shows the flicker; otherwise it is a magic
  constant with no reproduced symptom.
- **Critical-offline alarm ignores the raid mute.** `AlertService.handleState`'s critical-offline
  branch fires `dispatchCriticalOffline` + a notification while `bellsMuted()` is true; it honours
  only the offline-specific "ignore 30 min" deadline. That is arguably correct — the alarm means
  "the app has gone blind", which is worst during a raid, and `criticalOfflineBypassSilent` exists
  to make it pierce silence. If it must be muted too, route it through the one silence-decision
  owner (`SilentReason`) rather than adding a local `&& !bellsMuted()`.

## Alert-path testability (partly done)

`buildPrimary`/`reconcileEpisode`/`reconcileNotif` are private locals inside
`AlertService.handleState`, so the suite could not reach them and four all-clear bugs shipped
unverified. `handleState` + `MonitorState` are now `internal` and `AlertEpisodeClearTest` drives
real ticks against a fabricated state (`AlertService.monitoringReady` orders the test against the
onCreate restore; `clearPersistedServiceState()` isolates the shared DataStore). Remaining:

- `Primary.zone: ThreatZone?` still means two things — "this is a zone threat" (three consumers
  branch on it) and, in `postAlert`, "derive the channel from the level". Replace with a sealed
  `AlertSource` (`Zone(tier, threat)` / `Official`) so a channel tweak cannot reclassify an alert;
  that double meaning is how the official-alerts-on-the-wrong-channel bug was introduced and how a
  plausible-looking wrong fix slipped past review.
- Optionally extract one pure `decideEpisode(EpisodeState, EpisodeFacts) -> EpisodeOutcome` if the
  tick seam stops being enough; not needed for the current tests.
- **`UserPrefsTest`'s night-sleep snapshot test is order-dependent.** `touching a night bell drops
  the sleep snapshot` passes alone and flips in a full run as soon as another test class touches
  the shared `user_prefs` DataStore — observed when `clearPersistedServiceState()` was briefly
  widened to clear prefs too. The test calls `UserPrefs.clearAll()` itself, so the coupling lives
  in whatever the other class wrote. Prefs isolation belongs to whoever owns that store's tests:
  do not widen the service-level fixture to cover `user_prefs`.

## Shake-to-shoot feedback (TL;DR)

Shake the phone to shoot a screenshot + open feedback: accelerometer trigger (debounced,
only while map is foreground), captures map bitmap, opens bottom-sheet with the shot
attached, 1 rating + 1 free-text field, auto-attached version/city. Alternative to the
hovering top-right feedback button — same sheet, different trigger. Needs a Settings
opt-out (shake off by default?) and a manual button fallback since shake is
undiscoverable. Transport TBD: in-app sheet POST vs external link (Telegram/Form).
