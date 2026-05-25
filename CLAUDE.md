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

## Source-of-truth docs (read these before protocol work)

- **`BASE_BLUEPRINT.md`** — the authoritative FlashForge LAN protocol reference: HTTP REST
  (port **8898**), TCP G-code (port **8899**), MJPEG camera (port **8080**), UDP discovery,
  auth (per-request `serialNumber` + `checkCode`), endpoints, G-code commands, and ~15
  documented protocol quirks. When implementing any printer feature, start here.
- **`GEMINI.md`** — original assistant guide + porting roadmap. Useful for intent/roadmap;
  its "Implemented" column overstates current reality.

## Build, test, run

Builds via the **Gradle wrapper** pinned to **Gradle 9.3.1** (required by AGP 9.1.1; older
Gradle is rejected). Runs on **JDK 25** (Temurin). Windows shell is PowerShell — use
`.\gradlew.bat`; the Bash tool can use `./gradlew`.

```
.\gradlew.bat assembleDebug        # build debug APK -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat test                 # unit + Robolectric + Roborazzi screenshot tests
.\gradlew.bat installDebug         # install on connected device/emulator
.\gradlew.bat recordRoborazziDebug # (re)record screenshot baselines
```

- `minSdk 24`, `targetSdk 36`, `compileSdk 36` (uses `android-36.1`). App code is Java 11
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
│   ├── UdpDiscovery          UDP broadcast scan (WifiManager.MulticastLock). WORKS.
│   ├── FlashForgeHttpApi     OkHttp + kotlinx.serialization; POST /detail, /matlStation,
│   │                         /control (light, temp, job pause/resume/cancel, clearPlatform)
│   ├── FlashForgeTcpClient   Raw Socket on 8899; M601 lock, M27/M105/M119 keep-alive loop,
│   │                         line parser for temps + MachineStatus  (⚠ see Known issues)
│   └── FlashForgeModels      @Serializable request/response shapes
├── data/                     Room persistence
│   ├── AppDatabase / PrinterDao / PrinterEntity / PrinterRepository
│   └── ActivePrinterSession  (lives INSIDE PrinterRepository.kt, not its own file)
│                             Owns one http+tcp client pair; adaptive HTTP poll loop
│                             (1.5s printing/busy, 2.5s paused, 10s error, 5s idle).
└── ui/
    ├── MainViewModel         AndroidViewModel; holds the single `activeSession`
    ├── FlasherApp            Scaffold + bottom NavigationBar, 3 typed routes
    │                         (DashboardRoute / PrintersRoute / SettingsRoute)
    ├── dashboard/ discovery/ settings/   screens
    └── theme/                Color, Theme, Type
```

- **Single active printer.** `MainViewModel.activeSession` holds exactly one
  `ActivePrinterSession`; connecting a new printer stops the previous one. Multi-printer is
  not yet supported despite Room storing many.
- **Two transports, one coordinator.** `ActivePrinterSession` runs the HTTP `/detail` poll;
  `FlashForgeTcpClient` runs its own socket read + keep-alive loops. State is exposed as
  `StateFlow` (`status`, `matlStation`, `telemetry`, `isConnected`).
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

- **TCP commands are effectively broken.** `FlashForgeTcpClient.sendCommand` does
  `outWriter?.print("\$cmd\\r\\n")` — escaped `$` and literal `\r\n`, so it transmits the
  literal text `$cmd\r\n` instead of the command + CRLF. It should be
  `outWriter?.print("$cmd\r\n")`. Also `~M601 S1` is sent before `_isConnected` is set to
  `true`, and `sendCommand` early-returns while disconnected, so the lock likely never goes
  out. This is the leading reason TCP control/telemetry doesn't really function yet — fix
  before relying on any 8899 path.
- HTTP failures in the poll loop are swallowed (empty `onFailure`); there's no surfaced
  connection/offline state in the UI yet.
- `/matlStation` is queried as a separate endpoint; confirm against `BASE_BLUEPRINT.md`
  (which models IFS via `matlStationInfo` on `/detail`) when touching AD5X material code.
- Camera, Spoolman, notifications, G-code terminal, and manual motion are **not started**.

## Skills installed (`.claude/skills/`)

- `android-cli` — SDK / emulator / device / build orchestration via the `android` CLI.
- `testing-setup` — unit, Compose UI, and screenshot (Roborazzi) test infrastructure.
- `edge-to-edge` — insets, system-bar legibility, IME handling for Compose.
- `compose-styles` — Jetpack Compose Styles API for the custom design system.
- `navigation-3` — Navigation 3 patterns (relevant as more screens/tabs get ported).
