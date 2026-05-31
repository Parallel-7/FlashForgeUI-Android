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

## Source-of-truth docs

- **`GEMINI.md`** — original assistant guide + porting roadmap. Useful for intent/roadmap;
  its "Implemented" column overstates current reality.

For the wire protocol itself (HTTP REST on **8898**, TCP G-code on **8899**, MJPEG camera on
**8080**, UDP discovery, per-request `serialNumber` + `checkCode` auth), the reference repos in
`C:\Users\coper\Documents\GitHub\1flashforge_printers\` (`ff-5mp-api-ts`, `FlashForgeUI-Electron`)
are the ground truth — read the matching code there.

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

## Architecture (as actually built)

```
me.ghost.ffui
├── api/                      Networking & protocol
│   ├── UdpDiscovery          UDP broadcast scan (WifiManager.MulticastLock). WORKS — do NOT
│   │                         change the (empty) broadcast payload; it works on real hardware.
│   ├── FlashForgeHttpApi     OkHttp + kotlinx.serialization; POST /detail, /product,
│   │                         /control (light, temp, job pause/resume/cancel, clearPlatform)
│   ├── FlashForgeTcpClient   Raw Socket on 8899; M601 lock, synchronous sendCommandWithResponse
│   │                         (CompletableDeferred + Mutex), KeepAliveMode (MODERN/LEGACY_POLL/NONE),
│   │                         auto-reconnect w/ exponential backoff, M661 file list, M662 thumbnail.
│   ├── PrinterModel          PrinterModel enum + pid-based detection + M115 Machine Type fallback;
│   │                         PrinterCapabilities.
│   └── FlashForgeModels      @Serializable shapes; /detail carries matlStationInfo INLINE.
├── backend/                  Per-model strategy (mirrors FFUI-Electron backends)
│   ├── PrinterBackend        abstract: capabilities via /product, shared job/LED control
│   ├── DualApiBackend        modern base — polls HTTP /detail
│   ├── Adventurer5M / 5MPro / AD5X / GenericLegacyBackend (covers A3/A4/legacy)
│   │   GenericLegacyBackend: TCP polling via M105+M119+M27, M25/M24/M26 job control, M23+M24 start
│   └── PrinterBackendFactory create(model, …)
├── data/                     Room persistence
│   ├── AppDatabase (v4) / PrinterDao / PrinterEntity / PrinterRepository
│   └── ActivePrinterSession  (lives INSIDE PrinterRepository.kt, not its own file)
│                             Owns a PrinterBackend + http/tcp pair; identify via HTTP /detail
│                             (modern) or TCP ~M115 (legacy fallback); adaptive poll loop with
│                             ConnectionState (Connecting/Connected/Offline/AuthFailed)
│                             and adaptive cadence (1.5s printing, 2.5s paused, 3s offline,
│                             5s idle, 10s error, 15s auth-failed).
└── ui/
    ├── MainViewModel         AndroidViewModel; `sessions: StateFlow<Map<String, ActivePrinterSession>>`
    │                         + `activeSerial`; `activeSession` is the derived convenience flow.
    ├── FlasherApp            Scaffold + bottom NavigationBar, 4 typed routes
    │                         (DashboardRoute / ControlsRoute / PrintersRoute / SettingsRoute)
    ├── JobState.kt           jobStateOf(status) — shared job-state machine for dashboard + controls
    ├── components/           MpvPlayer.kt — MpvController + MpvVideoSurface (libmpv; see camera note)
    ├── controls/             ControlsScreen + JobControlRow (the Controls tab)
    ├── dashboard/ discovery/ settings/   screens
    └── theme/                Color, Theme, Type
```

- **Concurrent multi-printer is live.** `MainViewModel` holds a `sessions: StateFlow<Map<String,
  ActivePrinterSession>>` keyed by serial plus an `activeSerial`; `activeSession` is a derived
  convenience flow for screens that only care about the visible printer. The dashboard renders the
  sessions in a `HorizontalPager` with a `PrinterTabBar` (tabs + swipe, two-way synced to
  `activeSerial`). Connecting an already-open serial just switches tabs — no duplicate connection.
  Connected-serials + active-tab are persisted eagerly (`persistSessionState`) for startup-reconnect.
- **Model is detected by `pid`** (35=5M, 36=5M Pro, 38=AD5X) on first `/detail`, not by name.
  Legacy printers (Adventurer 3/4) are detected via TCP `~M115` `Machine Type:` string when
  HTTP `/detail` fails. `PrinterBackendFactory` picks the backend; `/product` flags + per-printer
  `customLedEnabled` resolve `PrinterCapabilities`. UI controls are capability-gated (hide unsupported).
- **HTTP `/detail` is the single source of truth** for modern printers (status + IFS inline). TCP
  is control-only (custom LEDs `~M146`, homing `~G28`). Only `GenericLegacyBackend` polls over TCP.
- **No DI framework.** Dependencies are constructed manually (`AppDatabase.getDatabase`,
  `PrinterRepository(dao)`, clients `new`'d in the session). Keep it that way unless asked.

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
  every numeric `/detail` field is typed `Float?` (only `pid` is `Int?`) — keep new numeric fields
  `Float?`. The 5M / 5M Pro paths are still unverified (no hardware on hand).
- **TCP client supports synchronous command/response.** `FlashForgeTcpClient` uses a
  `CompletableDeferred`-based protocol: `sendCommandWithResponse(cmd)` writes the command and
  waits for the multi-line `"ok"`-terminated response. A `Mutex` serializes command writes. The
  `KeepAliveMode` enum controls the heartbeat: `MODERN` (light `~M27` every 5s for modern printers),
  `LEGACY_POLL` (no heartbeat — the backend drives polling explicitly), or `NONE`. Auto-reconnect
  with exponential backoff (1s→15s cap) runs when the read loop exits abnormally. The modern
  keep-alive + background telemetry parsing path is verified against a live AD5X; the legacy
  polling path (M105+M119+M27 per tick) is verified against the flashforge-emulator-v2.
- **Legacy TCP backend is emulator-verified, not hardware-verified.** `GenericLegacyBackend`
  polls status via M119 (machine status + LED + current file), M105 (temperatures), and M27
  (progress) per tick. Job control uses M25/M24/M26 (pause/resume/cancel). Start print uses
  M23+M24. Identification falls through to TCP M115 when HTTP `/detail` fails. File listing via
  M661 works (both A4 `::`-delimited and A3 `info_list.size:` formats). File thumbnail via
  M662 works (both A4 raw PNG and A3 `0xa2a22a2a` magic header). LED control: A4/Generic uses
  `~M146 r255...` (RGB), A3 uses `~M146 1/0` (on/off). The emulator models real A3 firmware
  differences (echo:/ack: prefixes, IDLE status, LEDStatus:, PrintFileName:, fire-and-forget
  motion commands, M105 ok-prefix). All verified against flashforge-emulator-v2 headless A3.
  Still not verified against real hardware.
- **Temperature SET is still the old HTTP `temperatureCtl_cmd`** (`FlashForgeHttpApi.controlTemp`)
  and is suspect — the reference TS lib sets temps over TCP G-code (M104/M140) and leaves the HTTP
  path commented out as unverified. Move temp-set to TCP in Phase 3; don't trust the HTTP path.
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
- **Still not started:** Spoolman integration and notifications (Phase 5).

## Skills installed (`.claude/skills/`)

- `android-cli` — SDK / emulator / device / build orchestration via the `android` CLI.
- `testing-setup` — unit, Compose UI, and screenshot (Roborazzi) test infrastructure.
- `edge-to-edge` — insets, system-bar legibility, IME handling for Compose.
- `compose-styles` — Jetpack Compose Styles API for the custom design system.
- `navigation-3` — Navigation 3 patterns (relevant as more screens/tabs get ported).
