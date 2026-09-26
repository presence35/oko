# Ukraine Drones (NEPTUN)

A live air-threat monitoring app for Ukraine. Single-module Android app (`:app`) built with
Jetpack Compose (Material 3, dark-only) and OSMdroid, streaming from the public
[NEPTUN](https://neptun.in.ua) API over WebSocket — no backend of our own, no Firebase, no
push.

For the technical map of the codebase — module structure, ownership boundaries, invariants —
see [`ARCHITECTURE.md`](ARCHITECTURE.md). For the threat-evaluation engine's behavioral
contract, see [`BEHAVIORS.md`](BEHAVIORS.md). For release/dev workflow conventions, see
[`AGENTS.md`](AGENTS.md).

## Build

```
.\gradlew.bat :app:assembleDebug   # debug APK, no secrets needed
.\gradlew.bat :app:testDebugUnitTest
```

Requires AGP 9.4 (built-in Kotlin 2.2.10), JDK 17, minSdk 26 / targetSdk 36 (namespace `com.odesaplay.oko`).

Release builds (`:app:release`) require git-ignored `app/keystore.properties` (signing) and
`app/upload.properties` (FTP creds) — see `AGENTS.md` for the full release workflow.
