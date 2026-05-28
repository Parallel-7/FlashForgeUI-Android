# CLAUDE.md — FlashForgeUI Android

Guidance for Claude Code working in this repo. This is an **early, rough prototype**: of the
porting roadmap, only **UDP auto-discovery** and **initial connection / status polling** are
actually working. Treat the feature matrix in `GEMINI.md` as aspirational, not done — verify
against the code before assuming a feature works.

## What this app is

Native Android (Kotlin + Jetpack Compose) port of the desktop FlashForgeUI Electron app, for
LAN monitoring & control of FlashForge Adventurer 5M / 5M Pro / AD5X printers. Single Gradle
module (`:app`), package `com.example` (note: `applicationId` is the auto-generated
`com.aistudio.flasher.kjhasd`).

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
com.example
├── api/                      Networking & protocol
│   ├── UdpDiscovery          UDP broadcast scan (WifiManager.MulticastLock). WORKS — do NOT
│   │                         change the (empty) broadcast payload; it works on real hardware.
│   ├── FlashForgeHttpApi     OkHttp + kotlinx.serialization; POST /detail, /product,
│   │                         /control (light, temp, job pause/resume/cancel, clearPlatform)
│   ├── FlashForgeTcpClient   Raw Socket on 8899; M601 lock, light ~M27 keep-alive heartbeat,
│   │                         ledOn/ledOff (~M146), homeAxes (~G28). Control-only for modern.
│   ├── PrinterModel          PrinterModel enum + pid-based detection; PrinterCapabilities.
│   └── FlashForgeModels      @Serializable shapes; /detail carries matlStationInfo INLINE.
├── backend/                  Per-model strategy (mirrors FFUI-Electron backends)
│   ├── PrinterBackend        abstract: capabilities via /product, shared job/LED control
│   ├── DualApiBackend        modern base — polls HTTP /detail
│   ├── Adventurer5M / 5MPro / AD5X / GenericLegacyBackend
│   └── PrinterBackendFactory create(model, …)
├── data/                     Room persistence
│   ├── AppDatabase (v4) / PrinterDao / PrinterEntity / PrinterRepository
│   └── ActivePrinterSession  (lives INSIDE PrinterRepository.kt, not its own file)
│                             Owns a PrinterBackend + http/tcp pair; HTTP /detail poll loop
│                             with ConnectionState (Connecting/Connected/Offline/AuthFailed)
│                             and adaptive cadence (1.5s printing, 2.5s paused, 3s offline,
│                             5s idle, 10s error, 15s auth-failed).
└── ui/
    ├── MainViewModel         AndroidViewModel; `activeSession: StateFlow<ActivePrinterSession?>`
    ├── FlasherApp            Scaffold + bottom NavigationBar, 3 typed routes
    │                         (DashboardRoute / PrintersRoute / SettingsRoute)
    ├── components/           MpvPlayer.kt — MpvController + MpvVideoSurface (libmpv; see camera note)
    ├── dashboard/ discovery/ settings/   screens
    └── theme/                Color, Theme, Type
```

- **Single active printer (for now).** `MainViewModel.activeSession` is a `StateFlow` holding one
  `ActivePrinterSession`; connecting a new printer stops the previous one. Concurrent multi-printer
  (top tabs + swipe) is Phase 2 — the StateFlow shape is the seam to extend.
- **Model is detected by `pid`** (35=5M, 36=5M Pro, 38=AD5X) on first `/detail`, not by name.
  `PrinterBackendFactory` picks the backend; `/product` flags + per-printer `customLedEnabled`
  resolve `PrinterCapabilities`. UI controls are capability-gated (hide unsupported).
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
- **Room migrations:** add one to `AppDatabase` whenever `PrinterEntity` changes; all DB
  access goes through `PrinterRepository` asynchronously.
- Follow `GEMINI.md`'s rules: KDoc new API/services, no TODO-stub placeholders, idiomatic
  Kotlin (`val`, strict nullability, `@Serializable`).

## Known rough edges (verify, don't trust)

- **Phase 1 is verified against a live AD5X** (firmware 3.1.0): `/detail` poll loop, `pid`
  detection, `/product` capability gating, and the inline IFS card all work. Two gotchas were
  fixed in the process: (1) **cleartext HTTP must be permitted** — see
  `res/xml/network_security_config.xml` (printers are plain HTTP/TCP, no TLS); removing it silently
  breaks all polling. (2) **firmware serializes numbers inconsistently** (decimals vs ints), so
  every numeric `/detail` field is typed `Float?` (only `pid` is `Int?`) — keep new numeric fields
  `Float?`. The 5M / 5M Pro paths are still unverified (no hardware on hand).
- **TCP is control-only and unverified.** `FlashForgeTcpClient` writes correctly (`"$cmd\r\n"`),
  acquires `~M601 S1`, releases `~M602`, and now runs only a light `~M27` heartbeat. `ledOn/ledOff`
  (`~M146`) and `homeAxes` (`~G28`) exist but aren't wired to UI yet. No reconnect/backoff on drop.
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
- Filtration *controls*, the full IFS spool card, file lists/printing, multi-printer,
  per-printer settings screen, Spoolman, notifications, and manual motion are **not started**
  (Phases 2–5). The dashboard currently shows filtration/IFS state read-only, capability-gated.

## Skills installed (`.claude/skills/`)

- `android-cli` — SDK / emulator / device / build orchestration via the `android` CLI.
- `testing-setup` — unit, Compose UI, and screenshot (Roborazzi) test infrastructure.
- `edge-to-edge` — insets, system-bar legibility, IME handling for Compose.
- `compose-styles` — Jetpack Compose Styles API for the custom design system.
- `navigation-3` — Navigation 3 patterns (relevant as more screens/tabs get ported).
