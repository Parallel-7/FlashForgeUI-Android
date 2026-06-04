# CLAUDE.md — FlashForgeUI Android

Guidance for Claude Code working in this repo. This is an **early, rough prototype**: of the
porting roadmap, only **UDP auto-discovery** and **initial connection / status polling** are
actually working. Treat the feature matrix in `GEMINI.md` as aspirational, not done — verify
against the code before assuming a feature works.

## Git workflow

- **Commit and push straight to `main`.** The maintainer is the sole developer on this prototype
  and prefers it — do **not** create a feature branch or open a PR for normal work unless they
  explicitly ask. (This overrides the usual "branch off the default branch" default.) Still only
  commit/push when asked.

## Reminders to surface

- **Going public / publishing this repo:** if the user mentions making the repo public,
  open-sourcing it, sharing a clone URL, or anything similar, **remind them** that
  `.build-outputs/app-debug.apk` (a 19MB APK) lives in old commits (up to and including
  `4b06d26`) even though it's been removed from `HEAD`. Before going public they'll likely
  want it purged from history with `git filter-repo --path .build-outputs/app-debug.apk
  --invert-paths` followed by `git push --force` — this **rewrites** the affected commits
  (their SHAs change) but **does not delete** any commits from the log. Don't run this
  unprompted; just surface it when relevant.

## What this app is

Native Android (Kotlin + Jetpack Compose) port of the desktop FlashForgeUI Electron app, for
LAN monitoring & control of FlashForge Adventurer 5M / 5M Pro / AD5X printers. Single Gradle
module (`:app`), package `me.ghost.ffui` (both `namespace` and `applicationId`).

## Protocol code lives in `ff-5mp-api-kt` — do NOT re-implement it here

The FlashForge **wire protocol** (HTTP REST on **8898**, TCP G-code on **8899**, per-request
`serialNumber` + `checkCode` auth, model detection, the per-model backend strategy, all
`@Serializable` wire shapes) is **owned by a separate library** and the app depends on it:

- **Repo:** `C:\Users\coper\Documents\GitHub\1flashforge_printers\ff-5mp-api-kt`, package
  `me.ghost.ffapi`, GitHub `GhostTypes/ff-5mp-api-kt` (private). It's a `com.android.library`
  AAR; a 1:1 port of the reference `ff-5mp-api-ts`. Read its `CLAUDE.md` + `docs/parity.md`.
- **Coordinates:** `me.ghost:ff-5mp-api-kt:0.1.0`, consumed via **`mavenLocal()`** (see Build).
- **No package collision:** app is `me.ghost.ffui`, library is `me.ghost.ffapi`.

**The rule:** when a task needs HTTP/TCP transport, a new `/detail` field, a new G-code command,
model/capability detection, or a backend behavior change, **make that change in `ff-5mp-api-kt`,
`publishToMavenLocal`, and bump the version here.** Do **not** add a new `FlashForge*`/protocol
class, wire model, or backend back into `me.ghost.ffui`. The app keeps only: UI/state, Room
persistence, the session orchestration in `ActivePrinterSession`, the (Spoolman) integration, and
the hardware-proven `UdpDiscovery`. If you're tempted to write socket/HTTP/serialization code in
`app/`, stop — it belongs in the library.

## Source-of-truth docs

- **`GEMINI.md`** — original assistant guide + porting roadmap. Useful for intent/roadmap;
  its "Implemented" column overstates current reality.
- **`ff-5mp-api-kt`** (above) — the protocol implementation the app runs on. For protocol
  *intent*, the original reference repos in `C:\Users\coper\Documents\GitHub\1flashforge_printers\`
  (`ff-5mp-api-ts`, `FlashForgeUI-Electron`) remain the upstream ground truth the library was
  ported from — read the matching code there when extending the library.

## Build, test, run

Builds via the **Gradle wrapper** pinned to **Gradle 9.3.1** (required by AGP 9.1.1; older
Gradle is rejected). Runs on **JDK 25** (Temurin). Windows shell is PowerShell — use
`.\gradlew.bat`; the Bash tool can use `./gradlew`.

```
.\gradlew.bat assembleDebug        # build per-ABI debug APKs -> app/build/outputs/apk/debug/
.\gradlew.bat test                 # unit + Robolectric + Roborazzi screenshot tests
.\gradlew.bat installDebug         # install the matching ABI on connected device/emulator
.\gradlew.bat recordRoborazziDebug # (re)record screenshot baselines
```

- **ABI splits are on** (`splits.abi`, `isUniversalApk = false`) to keep the libmpv-bloated
  APK small. `assembleDebug` emits **four per-ABI APKs** — `app-{armeabi-v7a,arm64-v8a,x86,x86_64}-debug.apk`
  — and **no universal `app-debug.apk`**. Install the right one explicitly: emulators are
  `app-x86_64-debug.apk`; physical devices are usually `app-arm64-v8a-debug.apk`. (`installDebug`
  auto-picks by device ABI; only the explicit `android run`/`adb install` paths need the right file.)

- `minSdk 26`, `targetSdk 36`, `compileSdk 36` (uses `android-36.1`). App code is Java 11
  source/target; the Gradle/AGP toolchain itself runs on JDK 25.
- **Depends on `ff-5mp-api-kt` via `mavenLocal()`** (see the protocol-library section above).
  `settings.gradle.kts` adds `mavenLocal()`; `app/build.gradle.kts` has
  `implementation("me.ghost:ff-5mp-api-kt:0.1.0")`. The artifact must be present in the local
  Maven repo or the app won't resolve — if a fresh checkout / clean machine fails to build, run
  `./gradlew :ffapi:publishToMavenLocal` **in the library repo** first. After changing the
  library, re-publish there and (if the version changed) bump the coordinate here. The library
  pulls kotlinx-serialization/-coroutines + OkHttp transitively, but the app also uses them
  directly, so keep its own declarations.
- **Per-machine setup (untracked, must exist locally — both are gitignored):**
  - `local.properties` with `sdk.dir=<Android SDK path>` (e.g.
    `C:\Users\...\AppData\Local\Android\Sdk`).
  - `debug.keystore` at repo root. The `debugConfig` signing config expects it with the
    standard debug creds. Regenerate with:
    `keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
- Secrets Gradle Plugin reads a root **`.env`** file (template: `.env.example`); e.g.
  `GEMINI_API_KEY`.
- Release signing pulls from `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD` env vars.
- For device/emulator/SDK orchestration, logcat, and screenshots, use the **`android-cli`**
  skill (in `.claude/skills/`).

### Driving the app on a device/emulator (do it the fast way)

When you need to read the UI, find a control's coordinates, or verify state on a running
device, the order of preference is:

1. **`android layout --device=<serial>` first, always.** It's Compose-aware and returns a flat
   JSON of every node with `text`, `interactions` (`clickable`/`checkable`/`scrollable`/…), and
   a pixel **`center`** you can feed straight into `adb shell input tap <x> <y>`. This is how you
   locate switches, buttons, sliders, and confirm on/off (`state:["checked"]`) or whether a row
   is present at all — no pixel-guessing. Add `-d`/`--diff` to get only what changed since the
   last dump (great for "did my tap do anything?"). Grep it for the node you want, e.g.
   `android layout --device=emulator-5554 | tr '}' '\n' | grep -i 'keep monitoring'`.
2. **Screenshots only for genuine visual verification** — colors, theming, layout/spacing,
   rendered images (camera feed, thumbnails), or anything `layout` can't express. `adb exec-out
   screencap -p > <file>.png` then Read it. Don't screenshot just to find a button or check a
   toggle — `layout` already told you. Save under `.build-outputs/` (gitignored), not `/tmp`
   (the Read tool can't reach `/tmp` on Windows).
3. **`uiautomator dump` only as a last resort** — i.e. for a non-Compose surface (a *system*
   dialog, the launcher) that `layout` can't see, AND only if `android layout` didn't already
   return it. (In practice `layout` *does* pick up system permission dialogs — the battery-
   optimization "Allow/Deny" buttons showed up with their `center`s — so try it there too before
   reaching for uiautomator.) On this Windows setup `uiautomator dump` redirects its output path
   weirdly and is slow/flaky; avoid unless you truly have no other option.

Install for manual testing: emulator is x86_64, so
`adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk` (confirm with
`adb shell getprop ro.product.cpu.abi`). After launching, scan for startup crashes with
`adb logcat -d -t 200 | grep -iE "FATAL|AndroidRuntime|ffui"` before assuming it's healthy.

## Architecture (as actually built)

Protocol transport (`FlashForgeHttpApi`, `FlashForgeClient`/`FlashForgeTcpClient`, `PrinterModel`,
`PrinterCapabilities`, the per-model `backend/` strategy, and all wire `@Serializable` models) now
lives in **`ff-5mp-api-kt`** (`me.ghost.ffapi.*`) — see the protocol-library section above. What
remains in the app:

```
me.ghost.ffui
├── api/                      App-side networking only (protocol moved to ff-5mp-api-kt)
│   ├── UdpDiscovery          UDP broadcast scan (WifiManager.MulticastLock). WORKS — do NOT
│   │                         change the (empty) broadcast payload; it works on real hardware.
│   │                         (Kept in-app deliberately; the library also has PrinterDiscovery,
│   │                         but this hardware-proven path is what ships.)
│   └── SpoolmanApi /         Spoolman-server REST client + @Serializable models — NOT FlashForge
│       SpoolmanModels        protocol, so it stays here (see Spoolman note below).
├── data/                     Room persistence + session ownership
│   ├── AppDatabase (v4) / PrinterDao / PrinterEntity / PrinterRepository
│   │                         PrinterEntity.toConfig() maps the Room row -> library PrinterConfig.
│   ├── ActivePrinterSession  (lives INSIDE PrinterRepository.kt, not its own file)
│   │                         Owns a library `PrinterBackend` + `FlashForgeHttpApi`/`FlashForgeClient`
│   │                         pair (all from me.ghost.ffapi.*); identify via HTTP /detail (modern)
│   │                         or TCP ~M115 (legacy fallback); adaptive poll loop with
│   │                         ConnectionState (Connecting/Connected/Offline/AuthFailed). Auth is
│   │                         detected via the library's typed `AuthException` (not string-match).
│   │                         Adaptive cadence (1.5s printing, 2.5s paused, 3s offline, 5s idle,
│   │                         10s error, 15s auth-failed). `pollFloorMs` lets the manager throttle
│   │                         the cadence in the background.
│   ├── PrinterSessionManager process-lifetime owner of the sessions map + activeSerial + the
│   │                         PrinterNotifier wiring + startup-reconnect. Drives the foreground
│   │                         service and the background throttle off ProcessLifecycleOwner.
│   └── SettingsDataStore     global prefs (startup-reconnect, hide-serials, background-monitoring
│                             toggle + throttle toggle/interval).
├── service/
│   ├── PrinterMonitorService specialUse foreground service — keep-alive shell that holds the
│   │                         process open so the manager keeps polling while the app is closed.
│   │                         Owns NO monitoring logic; just the ongoing notification. START_STICKY;
│   │                         a null-intent (system) restart calls resumeMonitoringAfterRestart().
│   └── BatteryOptimization   isIgnored() / requestIntent() for the Doze exemption the Background
│                             settings nudge users to grant.
├── notifications/
│   └── PrinterNotifier       posts per-printer complete/cooled/error alerts (events detected in
│                             ActivePrinterSession.detectEvents, gated by per-printer opt-in flags).
└── ui/
    ├── MainViewModel         thin AndroidViewModel facade over PrinterSessionManager; exposes its
    │                         `sessions` / `activeSerial` / `activeSession` and delegates control.
    │                         Owns only discovery + the foreground-only teardown in onCleared.
    ├── FlasherApp            Scaffold + bottom NavigationBar, 4 typed routes
    │                         (DashboardRoute / ControlsRoute / PrintersRoute / SettingsRoute)
    ├── JobState.kt           jobStateOf(status) — shared job-state machine for dashboard + controls
    ├── components/           MpvPlayer.kt — MpvController + MpvVideoSurface (libmpv; see camera note)
    ├── controls/             ControlsScreen + JobControlRow (the Controls tab)
    ├── dashboard/ discovery/ settings/   screens
    └── theme/                Color, Theme, Type
```

- **Concurrent multi-printer is live.** The `sessions: StateFlow<Map<String, ActivePrinterSession>>`
  (keyed by serial) plus `activeSerial` live in the process-singleton `PrinterSessionManager`;
  `MainViewModel` is a thin facade that re-exposes them (`activeSession` is the derived convenience
  flow for screens that only care about the visible printer). The dashboard renders the sessions in
  a `HorizontalPager` with a `PrinterTabBar` (tabs + swipe, two-way synced to `activeSerial`).
  Connecting an already-open serial just switches tabs — no duplicate connection. Connected-serials +
  active-tab are persisted eagerly (`persistSessionState`) for startup-reconnect.
- **Sessions outlive the UI (background monitoring).** `PrinterSessionManager` is created once in
  `FfuiApplication` and owns the sessions on a process-lifetime scope, so they survive Activity/
  ViewModel teardown. The global **Keep monitoring in background** toggle (Settings) gates this: when
  ON, `PrinterMonitorService` (a `specialUse` foreground service) is kept running while ≥1 printer is
  connected, so completion/cooled/error notifications keep firing after the app is closed; when OFF,
  `MainViewModel.onCleared` tears the sessions down with the app (legacy behaviour). A second toggle
  +10–60s slider throttles the poll cadence (`ActivePrinterSession.pollFloorMs`) while backgrounded.
  Startup-reconnect runs from `MainViewModel.init` (UI open), not process start — idempotent via the
  manager's serial-dedup. Notifications themselves are detected in `ActivePrinterSession.detectEvents`
  and rendered by `PrinterNotifier`; both predate this change and are unchanged.
- **Surviving a process kill.** The service is `START_STICKY`, so after an out-of-memory kill the OS
  recreates it with a null intent; `PrinterMonitorService.onStartCommand` then calls
  `PrinterSessionManager.resumeMonitoringAfterRestart()`, which reconnects the persisted
  last-connected serials *regardless of the startup-reconnect mode* (they were being monitored, so
  they come back). This is headless — no Activity — so `appInForeground` starts **false** (throttle
  applies) until an Activity raises it. Sticky restart does **not** happen after a user force-stop
  (intentional). Because OEM power managers can freeze/kill the service anyway, the Background
  settings section prompts for the battery-optimization exemption when monitoring is enabled and
  keeps a persistent "Allow background activity" affordance until it's granted
  (`BatteryOptimization`, re-checked on `ON_RESUME`).
- **Model is detected by `pid`** (35=5M, 36=5M Pro, 38=AD5X) on first `/detail`, not by name.
  Legacy printers (Adventurer 3/4) are detected via TCP `~M115` `Machine Type:` string when
  HTTP `/detail` fails. The library's `PrinterModel.fromDetail`/`fromMachineType` +
  `PrinterBackendFactory` pick the backend; `/product` flags + per-printer `customLedEnabled`
  resolve `PrinterCapabilities`. UI controls are capability-gated off that (hide unsupported). All
  of this lives in the library — `ActivePrinterSession` just orchestrates the calls.
- **HTTP `/detail` is the single source of truth** for modern printers (status + IFS inline). TCP
  is control-only (custom LEDs `~M146`, homing `~G28`, temps M104/M140). Only the library's
  `GenericLegacyBackend` polls over TCP.
- **No DI framework.** Dependencies are constructed manually — `FfuiApplication` builds the one
  `PrinterSessionManager` (`AppDatabase.getDatabase`, `PrinterRepository(dao)`, `SettingsDataStore`);
  each session `new`s the library's `FlashForgeHttpApi` + `FlashForgeClient` and hands them to
  `PrinterBackendFactory.create(model, printer.toConfig(), …)`. Keep it that way unless asked.

## Conventions

- **Networking is `Dispatchers.IO`.** All socket and HTTP work runs on IO; socket reads use
  `soTimeout = 10000`. Release the TCP lock (`~M602`) and close socket/reader/writer in
  teardown.
- **State via `StateFlow`**, collected in Compose with `collectAsState()` /
  `collectAsStateWithLifecycle()`.
- **Serialization is `kotlinx.serialization`** (`@Serializable`, `Json { ignoreUnknownKeys
  = true; explicitNulls = false }`). Retrofit + Moshi are declared in `libs.versions.toml`
  but currently unused — prefer the existing OkHttp + kotlinx pattern; don't introduce
  Retrofit/Moshi without a reason.
- **Theme tokens, no hardcoded colors.** Dark Slate-Blue scheme in `ui/theme/Color.kt`:
  `GeometricBackground` `#0F172A`, `GeometricSurface` `#1E293B`, `GeometricPrimary` `#3B82F6`.
  Use `GeometricOrangePrimary` `#F97316` for nozzle/hotend and `GeometricBluePrimary`
  `#3B82F6` for bed indicators. Reach for `MaterialTheme.colorScheme` over literal `Color`.
- **Edge-to-edge** is enabled (`enableEdgeToEdge()` in `MainActivity`); honor system-bar
  insets in new screens. See the `edge-to-edge` skill.
- **Room migrations: DO NOT WRITE THEM.** We are in internal development / prototyping —
  the only user is the maintainer and any local DB state is disposable. `AppDatabase` uses
  `fallbackToDestructiveMigration(dropAllTables = true)`; when `PrinterEntity` (or any other
  entity) changes, bump `@Database(version = …)` and let Room wipe the DB on next launch.
  **Never** add `Migration` objects or `addMigrations(...)` calls. This rule will be lifted
  in this file when we approach production — at that point we must never lose end-user data,
  so real migrations become mandatory. Until that change lands here, no migrations.
- All DB access goes through `PrinterRepository` asynchronously.
- Follow `GEMINI.md`'s rules: KDoc new API/services, no TODO-stub placeholders, idiomatic
  Kotlin (`val`, strict nullability, `@Serializable`).

### UI copy conventions

- **Keep user-facing strings short and scannable.** Settings subtitles should be one concise
  sentence describing what the option does — no parentheticals, no implementation details
  (e.g. don't mention "TCP" or "HTTP" in user-visible text). Target a ~50-character max.
- **No instructional helper text below controls.** If the user needs guidance (e.g. "set 0
  to turn off"), make it a button in the dialog instead of a static text label.
- **No internal/technical jargon in user-facing screens.** The printer info screen and
  settings should show consumer-friendly labels. Use "LED" not "Factory LED", prefer
  "Enable LED control for printers with custom LEDs" over references to specific protocols.
- **Toggle/option labels should be self-explanatory.** If the label alone isn't enough, add
  a short subtitle. Avoid long-winded descriptions that restate the obvious.

## Known rough edges (verify, don't trust)

- **Phase 1 is verified against a live AD5X** (firmware 3.1.0): `/detail` poll loop, `pid`
  detection, `/product` capability gating, and the inline IFS card all work. Two gotchas were
  fixed in the process: (1) **cleartext HTTP must be permitted** — see
  `res/xml/network_security_config.xml` (printers are plain HTTP/TCP, no TLS); removing it silently
  breaks all polling. (2) **firmware serializes numbers inconsistently** (decimals vs ints), so
  every numeric protocol field must tolerate a decimal literal. In the **library** (`FFPrinterDetail`
  etc.) these are typed **`Float?`** (only `pid` is `Int?`), matching the app's **Spoolman** models
  and Compose's `Float`-native UI — so a library numeric field reads straight into Compose with **no
  `.toFloat()`/`.toDouble()` conversion at the boundary** (the old `Double?`→`Float` churn was killed
  2026-06-03; `ff-5mp-api-kt` 0.1.1). Conceptually-integer fields (`printLayer`, `nozzleCnt`, …) stay
  `Float?` too — the firmware has been seen appending `.0` to whole values, so `Int?` would risk a
  parse crash. The modern read path is now hardware-verified on a real **AD5X + 5M Pro**.
- **The TCP client lives in the library now.** The app talks to the high-level
  `me.ghost.ffapi.tcpapi.FlashForgeClient` (wrapping the low-level `FlashForgeTcpClient`):
  `sendRawCommand(cmd, timeoutMs)` -> `Result<String>` (was the app's `sendCommandWithResponse`),
  `sendCmdOk` -> `Result<Unit>`, plus typed helpers (`getPrinterInfo`/M115, `setExtruderTemp`,
  `homeAxes`, `ledOn/Off`, `getFileList`, `getThumbnail`, …). `KeepAliveMode`
  (`MODERN`/`LEGACY_POLL`/`NONE`) and auto-reconnect (1s→15s backoff) are unchanged in behavior —
  just ported. Don't reach for the low-level client; use `FlashForgeClient`.
- **Legacy TCP backend is emulator-verified, not hardware-verified** (and now lives in the
  library as `GenericLegacyBackend`). Polls M119 (machine status + LED + current file), M105
  (temps), M27 (progress) per tick; job control M25/M24/M26; start M23+M24; identify via TCP M115
  when HTTP `/detail` fails; M661 file list (A4 `::` + A3 `info_list.size:`); M662 thumbnail (A4 raw
  PNG + A3 `0xa2a22a2a` magic); LED A4/Generic `~M146 r255...` (RGB) vs A3 `~M146 1/0`. Verified
  against flashforge-emulator-v2 headless A3; still not verified against real hardware. No legacy
  hardware on hand, so its status is unchanged by the library migration.
- **Temperature SET now goes over TCP G-code (M104/M140)** via the library's
  `FlashForgeClient.setExtruderTemp`/`setBedTemp` (the backend's `setNozzleTemp`/`setBedTemp`) —
  the old suspect HTTP `temperatureCtl_cmd` path is gone. Still untested against real hardware
  (it's a TCP write path; see the control-write caveat below).
- **Camera + fullscreen + FPS work (verified live on AD5X).** The dashboard `CameraCard`
  (`ui/dashboard/CameraCard.kt`) plays the MJPEG feed via libmpv (`dev.jdtech.mpv:libmpv`,
  replacing the earlier libVLC player). The player is split into `ui/components/MpvPlayer.kt`:
  a `MpvController` owns one `MPVLib` instance + the network stream, and one or more
  `MpvVideoSurface`s render it. mpv draws into a `TextureView` (so `Modifier.clip` keeps the
  rounded corners), `profile=low-latency` + `cache=no` + `untimed=yes` for a low-latency live
  feed, and `panscan` per surface (1.0 crop-to-fill for the card, 0.0 fit for fullscreen).
  - **Tap-to-expand fullscreen** is a borderless `Dialog`; it shares the *same* `MpvController`,
    so expanding swaps the output surface instead of opening a second connection (FlashForge
    cameras are single-client). **Critical mpv rules, both the hard way:** (1) only **one**
    surface may be attached at a time — the card's `MpvVideoSurface` is gated off while
    fullscreen is open; (2) `activate()` must `attachSurface → force-window=yes → vo=gpu` **in
    that order** (vo before the surface → black screen). See `MpvController.activate()`.
  - **FPS** is measured by counting `onSurfaceTextureUpdated` callbacks over a 1s window, NOT
    mpv's `estimated-vf-fps` (which returns 0 under `untimed`). Per-printer toggle
    `cameraFpsCounterEnabled`; auto-play toggle `cameraAutoPlayEnabled`.
  - `MainActivity` declares `configChanges` so rotating in fullscreen resizes in place.
  - **The libmpv native libs are why ABI splits are on** (see build note above).
- **Now built but mostly unverified against hardware:** filtration controls, the full IFS spool
  card + slot editor, file lists/printing (`FilesScreen`), multi-printer tabs, the per-printer
  settings screen, and manual motion/temperature (the Controls tab, over TCP G-code). These exist
  in code and are capability-gated, but only the AD5X HTTP read paths are hardware-verified — treat
  the control/write paths (especially anything over TCP) as suspect until tested on real hardware.
- **Notifications are built (Phase 5, partial).** Per-printer complete/cooled/error alerts work in
  the foreground, and an opt-in foreground service keeps them firing in the background (see the
  background-monitoring note in Architecture). Event detection is verified against the AD5X read
  path; the background service + throttle + `START_STICKY` restart-resume + battery-exemption prompt
  are wired and build-verified but not yet soak-tested on a real long print or a real OOM kill.
- **Spoolman integration is built (Phase 5).** A conditional 5th bottom-nav tab ("Spools", gated
  by the global `spoolmanEnabled` toggle in Settings) browses spools from a user-run Spoolman
  server. `api/SpoolmanApi.kt` (OkHttp + kotlinx, `Result<T>`, short LAN timeouts) + `SpoolmanModels.kt`
  (numeric fields `Float?`, same firmware-style guard); `data/SpoolmanRepository.kt` owns the API
  (rebuilt when the base URL changes) and exposes `spools` / `loadState` StateFlows + `refresh` and
  `useWeight`/`patchSpool`/`setArchived` pass-throughs that refresh on success. Constructed in
  `FfuiApplication` and re-exposed via `MainViewModel.spoolmanRepository`. UI under `ui/spools/`
  (grid + search/sort/archived, info dialog, edit screen); `ui/components/SpoolDisc.kt` is the
  shared disc renderer (extracted from the IFS card). Usage tracking is **manual only** — never
  print-linked auto-deduction. Verified live against the local seeded Spoolman (20 spools) on the
  emulator (`http://10.0.2.2:7912`). Known edge: `SpoolsScreen` can stay stuck on `NotConfigured`
  if you enable Spoolman with a blank URL, then set the URL and return without an app restart (the
  load `LaunchedEffect`s don't re-fire from `NotConfigured`) — unfixed, low-impact.
- **NFC spool tags (built, UI-verified on emulator, NOT hardware-tested).** Tap an NTAG215 (or any
  NDEF tag) to read/write a Spoolman spool. Global **NFC tags** Settings section: `nfcEnabled`
  toggle (greyed out when `NfcAdapter.getDefaultAdapter()` is null) + off-by-default `nfcWriteUrl`
  sub-toggle. `nfc/NfcManager.kt` is the app-scoped owner (built in `FfuiApplication`, re-exposed via
  `MainViewModel.nfcManager`): holds scan/write `mode` + `readResult`/`writeResult` StateFlows, builds
  & parses the NDEF payload, and does the tag I/O (handles `Ndef` + `NdefFormatable` for blank tags).
  `MainActivity` does NFC **foreground dispatch** (`enableForegroundDispatch` in `onResume` /
  disable in `onPause`) and routes the `Tag` to `NfcManager.handleTag`. **Payload:** one NDEF text
  record `SPOOL:<id>` (canonical — always read back); an optional URI record
  `<spoolman-url>/spool/show/<id>` is appended **only** when `nfcWriteUrl` is on and is never read
  back, so a changed server address can't break scanning. Spools tab: top-bar **scan** icon → read →
  scroll-to + flash the matching card (snackbar if not in the current list); per-card **write** icon
  (action row is now `(i)(cog)(write)`) → write dialog (prompt → success/error, names the spool);
  `Tags: All/Tagged/Untagged` filter + per-card "Tagged" badge. **Tagged state is tracked
  device-locally** in DataStore (`nfcTaggedSpools`: spoolId→ISO timestamp) — deliberately **not**
  written to Spoolman (no server mutation, no extra-field registration); the timestamp also shows in
  the spool info dialog. Only the UI states are verified (the emulator has no NFC adapter — to see
  the Spools NFC UI there, the `nfcEnabled` flag must already be set since the Settings toggle is
  greyed). The NDEF **read** round-trip is now **hardware-confirmed** (a real NTAG215 scan drives the
  scan-to-slot flow below on a live AD5X); the **write** path is still untested on hardware. Reference
  clones left in `C:\Users\coper\Documents\Prototyping\`: `SpoolCompanion` (Kotlin/Compose+Spoolman
  NFC app — the model we followed) and `OpenSpool` (ESP32/PN532 firmware + a published
  `application/json` tag standard we chose not to adopt).
- **Scan roll → IFS slot (built, hardware-verified on a real AD5X + NTAG215).** First feature to
  combine the NFC + Spoolman integrations. In the AD5X `SlotEditorSheet`, when **both** `nfcEnabled`
  and `spoolmanEnabled` are on, a **Scan roll** button scans a tagged spool, fetches it from Spoolman
  (`SpoolmanRepository.getSpool(id)` — a robust single-spool lookup, since the dashboard may never
  load the full list), snaps its material + color to the printer's fixed lists, and **auto-applies**
  `msConfig_cmd` (brief success card → the sheet auto-dismisses; errors get Retry/Close). Deps are
  threaded `DashboardScreen → IfsStationCard → SlotEditorSheet` off `MainViewModel`. **Nearest-match
  lives in `IfsPalette`:** `nearestColor` uses **CIEDE2000** over the 24 swatches (plain ΔE76 wrongly
  mapped saturated blue→Violet and burgundy→Coral on the live Spoolman library; CIEDE2000 gives
  `#0000FF`→Dark Blue, `#951e23`→Red — hex is parsed by hand, no `android.graphics`, so it stays
  pure-JVM unit-tested in `IfsPaletteMatchingTest`). `nearestMaterial` is exact-then-leading-token
  (deliberately **not** longest-prefix, which mis-snapped `PCTG`→PC / `PA6`→PA; unmatched names fall
  through to `null` so the caller keeps the current material). The 24 colors + 14 materials in
  `IfsPalette` are an exact match to the API docs' `AD5X-IFS-Material-Station.md` (only the material
  dropdown *order* differs — cosmetic). Matching is ~microseconds (benchmarked); any post-scan lag is
  the two network round-trips (Spoolman fetch + printer write), not the math. Intended to be
  **pioneered here, then backported to FlashForgeUI-Electron + the standalone Web UI.**
- **Next session — Spoolman instant-load cache (TODO, keep it simple).** The Spools tab refetches
  on every visit, so there's a brief (not bad) delay before cards appear. Want a lightweight
  background cache so the grid renders instantly from last-known data while a refresh runs behind
  it. **Do not over-engineer** — no Room table, no new sync framework. Simplest path: have
  `SpoolmanRepository` keep its `spools` StateFlow warm across tab visits (it already survives —
  it's app-scoped) and just show the cached list immediately instead of gating on `Loading`; if
  persistence across process death is wanted, a single DataStore/JSON blob of the last spool list
  is enough. Show stale data first, refresh silently, swap in on success.
- **Protocol library extraction — DONE (2026-06-02).** The standalone `ff-5mp-api-kt` library is
  built and the app depends on it; the in-tree `api/{FlashForgeHttpApi,FlashForgeTcpClient,
  PrinterModel,FlashForgeModels}` + the whole `backend/` package were deleted (see the
  protocol-library section at the top). Live-verified connect/poll/controls against a real AD5X +
  5M Pro. Known library-side gaps (documented in the library's `docs/parity.md`; none block the
  app today): no HTTP/TCP **file upload** (M28/M29 / `/uploadGcode`), no one-call `FiveMClient`
  bootstrap (the app assembles `FlashForgeHttpApi` + `FlashForgeClient` + factory itself), no
  camera-stream probe. If any of those are wanted, do them in the **library**, not the app.
- **Possible follow-up — migrate discovery into the library too.** The app still ships its own
  `api/UdpDiscovery` (hardware-proven empty-payload broadcast); the library has an equivalent
  `PrinterDiscovery`. Switching is optional and low-priority — if done, **do not change the empty
  broadcast payload**.

## Skills installed (`.claude/skills/`)

- `android-cli` — SDK / emulator / device / build orchestration via the `android` CLI.
- `testing-setup` — unit, Compose UI, and screenshot (Roborazzi) test infrastructure.
- `edge-to-edge` — insets, system-bar legibility, IME handling for Compose.
- `compose-styles` — Jetpack Compose Styles API for the custom design system.
- `navigation-3` — Navigation 3 patterns (relevant as more screens/tabs get ported).
- `adaptive` — adaptive UI for varied window sizes (phone/tablet/foldable/desktop): Compose
  MediaQuery, Grid/FlexBox, multi-pane Navigation 3 scenes.
