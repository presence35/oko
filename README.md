# Ukraine Drones (NEPTUN)

A live air-threat monitoring app for Ukraine. Single-module Android app (`:app`) built with
Jetpack Compose (Material 3, dark-only) and MapLibre, streaming from the public
[NEPTUN](https://neptun.in.ua) API over WebSocket — no backend of our own, no Firebase, no
push.

For the technical map of the codebase — module structure, ownership boundaries, invariants —
see [`ARCHITECTURE.md`](ARCHITECTURE.md). For the threat-evaluation engine's behavioral
contract, see [`BEHAVIORS.md`](BEHAVIORS.md). For release/dev workflow conventions, see
[`AGENTS.md`](AGENTS.md).

## Build

```
.\gradlew.bat :app:assembleDebug              # debug APK, no secrets needed
.\gradlew.bat :app:testSideloadDebugUnitTest  # or testPlayDebugUnitTest — bare
                                              # testDebugUnitTest is ambiguous across flavors
```

Requires AGP 9.4 (built-in Kotlin 2.2.10), JDK 17, minSdk 26 / targetSdk 36 (namespace `com.odesaplay.oko`).

Release builds require git-ignored `app/keystore.properties` (signing); uploading also needs
`app/upload.properties` (FTP creds). Use `:app:releaseDirect` (sideload APK + upload) or
`:app:releasePlay` (Play App Bundle) — see `AGENTS.md` for the full release workflow.

Building a release **without** our keystore fails on purpose, so a mistake can't silently ship an
unsigned APK. The one legitimate exception is a distributor that compiles from source and signs the
result itself, which is how the F-Droid build works:

```
.\gradlew.bat :app:assemblePlayRelease -PallowUnsignedRelease
# → app/build/outputs/apk/play/release/app-play-release-unsigned.apk
```

## License

GNU General Public License v3.0 only — see [`LICENSE`](LICENSE). The `play` flavor is the
source-build target; `sideload` adds the in-app self-update path.
