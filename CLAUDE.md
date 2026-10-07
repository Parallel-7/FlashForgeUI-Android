# CLAUDE.md — FlashForgeUI Android

Native Android (Kotlin + Jetpack Compose) app for LAN monitoring and control of FlashForge
printers: Adventurer 5M / 5M Pro / AD5X, Creator 5 / 5 Pro, and partial (status-only) Adventurer
3/4. Port of the desktop [FlashForgeUI-Electron](https://github.com/GhostTypes/FlashForgeUI-Electron)
app. Single module `:app`, package `me.ghost.ffui` (namespace + applicationId). Pre-release beta.

User docs: `README.md`. Build setup for humans: `DEVELOPING.md`.

## Git workflow

- **Commit and push straight to `main`** — sole maintainer; no feature branches or PRs unless
  asked. Still only commit/push when asked.
- Remote: `Parallel-7/FlashForgeUI-Android`. History was rewritten on 2026-10-07 to purge an old
  APK and keystore blob — any clone from before then must be re-cloned, not pulled.

## Protocol code lives in `ff-5mp-api-kt` — never re-implement it here

All FlashForge wire protocol — HTTP REST (8898), TCP G-code (8899), `serialNumber` + `checkCode`
auth, model detection, per-model backends, `@Serializable` wire shapes, palettes — is owned by the
library **`ff-5mp-api-kt`** (`me.ghost.ffapi`, GitHub `GhostTypes/ff-5mp-api-kt`, sibling checkout
at `../../1flashforge_printers/ff-5mp-api-kt`; read its `CLAUDE.md` + `docs/parity.md`).

- Consumed as `me.ghost:ff-5mp-api-kt:<ver>` from **`mavenLocal()`** (pin in `app/build.gradle.kts`).
- Need a new endpoint, `/detail` field, G-code command, or backend behavior? Change the library,
  `./gradlew :ffapi:publishToMavenLocal` there, bump the pin here. If you're writing
  socket/HTTP/serialization code in `app/`, stop.
- The app keeps only: UI/state, Room, session orchestration, Spoolman client, NFC, and the
  hardware-proven `UdpDiscovery` (the library has an equivalent `PrinterDiscovery`; migrating is
  optional — **never change the empty broadcast payload**).
- Upstream ground truth for protocol intent, in `../../1flashforge_printers/`: `ff-5mp-api-ts`
  (reference client the library ports), `flashforge-api-docs` (protocol docs),
  `FlashForgeUI-Electron` (desktop app: notifications, Spoolman, layout), `flashforge-emulator-v2`
  (headless printer emulator for legacy/modern testing).

## Build, test, run

Gradle wrapper **9.3.1** (required by AGP 9.1.1) on **JDK 25**. Shell is PowerShell
(`.\gradlew.bat`); Bash can use `./gradlew`.

```
./gradlew assembleDebug         # four per-ABI APKs -> app/build/outputs/apk/debug/ (no universal APK)
./gradlew test                  # unit + Robolectric + Roborazzi screenshot tests
./gradlew installDebug          # installs the matching ABI
./gradlew recordRoborazziDebug  # re-record screenshot baselines after intentional UI changes
```

- ABI splits are on because of libmpv's native libs. Emulator = `app-x86_64-debug.apk`, phones
  usually `app-arm64-v8a-debug.apk`.
- `minSdk 26`, `targetSdk`/`compileSdk 36` (`android-36.1`); app code is Java 11.
- Per-machine, gitignored: `local.properties` (`sdk.dir=…`) and `debug.keystore` at repo root
  (regen command in `DEVELOPING.md`). Release signing reads `KEYSTORE_PATH` / `STORE_PASSWORD` /
  `KEY_PASSWORD`; without them the release APK is unsigned. R8 is off.
- Fresh machine can't resolve the library → publish it to mavenLocal first (above).
- **CI** (`.github/workflows/ci.yml`): checks out the library at tag `v<pinned version>`, publishes it
  to mavenLocal, then runs `test` + `assembleDebug`. A library pin bump therefore needs a matching
  pushed library tag. Until the library is public, CI needs the `FFAPI_REPO_TOKEN` secret.
- `scripts/seed_printers.py` / `scripts/emulator-printers.ps1` re-pair printers into the emulator
  after a DB wipe via the debug-only `DebugPrinterSeeder` intent extra. Check-codes live in the
  gitignored `scripts/printers.local.json`.

### Driving a device/emulator (use the `android-cli` skill)

1. **`android layout --device=<serial>`** first — Compose-aware JSON with `text`, `interactions`,
   `state` and a tappable `center`; `--diff` shows only changes. It also sees system permission
   dialogs.
2. **Screenshots** (`adb exec-out screencap -p > .build-outputs/x.png`) only for genuinely visual
   checks. Save under `.build-outputs/` (gitignored), not `/tmp`.
3. `uiautomator dump` only as a last resort (slow/flaky on this Windows setup).

After launch, check `adb logcat -d -t 200 | grep -iE "FATAL|AndroidRuntime|ffui"`.

## Architecture

```
me.ghost.ffui
├── FfuiApplication          builds the process singletons (no DI framework — keep it manual)
├── MainActivity             edge-to-edge, NFC foreground dispatch, debug seed intent
├── api/                     UdpDiscovery (hardware-proven), SpoolmanApi + SpoolmanModels
├── data/
│   ├── AppDatabase (v5) / PrinterDao / PrinterEntity / PrinterRepository
│   ├── ActivePrinterSession lives in PrinterRepository.kt — owns the library backend +
│   │                        FlashForgeHttpApi/FlashForgeClient; identify, poll loop, ConnectionState
│   ├── PrinterSessionManager process-lifetime owner of sessions map + activeSerial, notifier wiring,
│   │                        startup reconnect, foreground-service + background throttle
│   ├── PollCadence / PrintEventDetector / ConnectionResolver   pure logic, unit-tested
│   ├── SettingsDataStore    global prefs (incl. NFC tagged spools/boxes, device-local)
│   └── SpoolmanRepository / SpoolBox / ThumbnailCache / DebugSeed
├── service/                 PrinterMonitorService (specialUse FGS keep-alive), BatteryOptimization
├── notifications/           PrinterNotifier (complete / cooled / error)
├── nfc/                     NfcManager (tag read/write, scan modes)
└── ui/                      MainViewModel (thin facade over the manager), FlasherApp (bottom nav),
                             JobState, PrinterModelNames, components/ (MpvPlayer, IfsPalette, …),
                             dashboard/ controls/ discovery/ files/ info/ settings/ spools/ theme/
```

- **Sessions.** `PrinterSessionManager.sessions: StateFlow<Map<serial, ActivePrinterSession>>` +
  `activeSerial`; dashboard is a `HorizontalPager` + `PrinterTabBar` synced to `activeSerial`.
  Connecting an open serial just switches tabs. Connected serials persist for startup reconnect.
- **Connect resolves via discovery first.** A short UDP scan matches by serial and updates the
  saved IP if it moved. A user-tapped connect that misses discovery *and* fails at the transport
  level (`Offline(transportFailure=true)`) prompts for a new IP; background reconnects never prompt.
- **Model detection by `pid`** (35 5M, 36 5M Pro, 38 AD5X, 40 Creator 5, 41 Creator 5 Pro) from
  first `/detail`; legacy printers via TCP `~M115`. `/product` flags + per-printer overrides →
  `PrinterCapabilities`; UI is capability-gated (hide unsupported).
- **HTTP `/detail` is the single source of truth** for modern printers (status, temps, IFS inline).
  TCP is control-only (LEDs, homing, M104/M140 temps on 5M/AD5X). Only the legacy backend polls TCP.
- **Creator 5 backends are `httpOnly`** — never touch 8899; temps go over HTTP (`-100` = off,
  except nozzles where `0` = off). Base C5 reports chamber `-108` → no chamber controls.
- **Job state** maps through the library's `MachineState` (`JobState.kt`): both `pause`/`paused`
  spellings, unknown ⇒ busy, `friendlyStateLabel` everywhere — never show raw firmware tokens.
  Poll cadence follows the same enum (1.5 s printing … 15 s auth-failed; `pollFloorMs` throttles
  in background).
- **Errors:** library `AuthException` → `AuthFailed`; `ApiErrorException` → `Offline` with the
  firmware message (C5 in cloud mode → "not in LAN mode").
- **Background monitoring:** opt-in toggle keeps `PrinterMonitorService` alive while ≥1 printer is
  connected; `START_STICKY` null-intent restart calls `resumeMonitoringAfterRestart()`. When off,
  `MainViewModel.onCleared` tears sessions down. Settings nudges for the battery-optimization exemption.
- **NFC tag format is a public contract** — tags in the wild depend on it: text record
  `SPOOL:<id>` or `BOX:<location>`; optional trailing URI record is never read back. Tagged state is
  device-local (DataStore), never written to Spoolman. Spoolman usage tracking is manual only.

## Hard rules / gotchas

- **Cleartext HTTP must stay permitted** (`res/xml/network_security_config.xml`) — printers have no
  TLS; removing it silently breaks polling.
- **Firmware numbers are inconsistent** (`5` vs `5.0`): numeric wire fields are `Float?` (only `pid`
  is `Int?`), same for Spoolman models. No conversion at the Compose boundary.
- **Camera (libmpv):** one `MpvController` per printer, one attached `MpvVideoSurface` at a time
  (cameras are single-client; fullscreen swaps the surface on the same controller).
  `activate()` order is `attachSurface → force-window=yes → vo=gpu` (vo first = black screen). FPS
  counts `onSurfaceTextureUpdated`, not `estimated-vf-fps` (0 under `untimed`). Only the settled
  pager page streams.
- **Room: do NOT write migrations** during prototyping. Bump `@Database(version)` and let
  `fallbackToDestructiveMigration(dropAllTables = true)` wipe. This flips to mandatory real
  migrations before production — this file will say so.
- **`allowBackup` stays ON** deliberately (restores saved printers on a new phone). Don't change it
  or add exclude rules without asking.
- **Temp dialogs clamp** to firmware ceilings (nozzle 265 °C, bed 100 °C, chamber 80 °C), accept one
  decimal and send the rounded integer — never strip the separator (that turned `21.5` into `215`).
- Networking on `Dispatchers.IO`; release the TCP lock (`~M602`) and close sockets on teardown.

## Conventions

- `StateFlow` collected with **`collectAsStateWithLifecycle()`** — never plain `collectAsState()`.
- `kotlinx.serialization` + OkHttp only (no Retrofit/Moshi). `Json { ignoreUnknownKeys = true;
  explicitNulls = false }`.
- **All user-visible strings in `res/values/strings.xml`**, prefixed by screen (`dashboard_`,
  `spools_`, `common_`, …), positional args (`%1$s`), `plurals` for counts. No literals in composables.
- Theme tokens, no hardcoded colors: `MaterialTheme.colorScheme` or `ui/theme/Color.kt`
  (`GeometricOrangePrimary` nozzle, `GeometricBluePrimary` bed). Edge-to-edge — honor insets.
- All DB access goes through `PrinterRepository`, async.
- KDoc new public API; no TODO stubs; idiomatic Kotlin (`val`, strict nullability).
- **UI copy:** short and scannable (~50-char subtitles, one sentence); no protocol jargon
  (no "TCP"/"HTTP", "LED" not "Factory LED"); no instructional helper text under controls — make
  it a button instead; labels should be self-explanatory.
- Nothing is "done" until exercised on real hardware — keep the table below honest.

## Verification status

The maintainer uses the app day-to-day on a real **5M Pro** and **AD5X**; the rows below record
what has been specifically exercised. The README's printer table must stay consistent with this.

| Area | Status |
|---|---|
| UDP discovery, `/detail` polling, pid detection, capability gating, IFS read | Hardware: AD5X (fw 3.1.0) + 5M Pro |
| Camera, fullscreen, FPS counter | Hardware: AD5X |
| Scan roll → IFS slot (NFC read + Spoolman + `msConfig_cmd`) | Hardware: AD5X + NTAG215 |
| Spoolman tab, NFC spool/box UI | Emulator + local Spoolman; NFC **write** untested on hardware |
| Notifications (foreground) | Event detection verified on AD5X read path |
| Background service, throttle, sticky restart | Build/test only — no long-print or OOM soak |
| Discovery-first connect + edit-address prompt | Build/test only |
| TCP temps/motion/LED, files + start print, filtration, slot editor | Built, unverified on hardware |
| Legacy Adventurer 3/4 (TCP backend) | Emulator only |
| Creator 5 / 5 Pro (all paths) | Build only — no hardware, not run on emulator |

## Open items

- Spoolman instant-load: render last-known `spools` immediately, refresh silently. Keep it simple —
  no Room table; a DataStore JSON blob at most if cross-process-death persistence is wanted.
- `SpoolsScreen` can stick on `NotConfigured` if Spoolman is enabled with a blank URL and the URL is
  set later without an app restart.
- AD5X single-color starts send `gcodeToolCnt: 1` app-side; drop the workaround when the library
  carries the fix.
- File upload exists in the library but is intentionally not exposed in the app.
- Not started (desktop parity): G-code terminal, Discord/webhook notifications.

## Skills (`.claude/skills/`)

`android-cli` (device/emulator/build), `testing-setup`, `edge-to-edge`, `compose-styles`,
`navigation-3`, `adaptive`, `best-practices`.
