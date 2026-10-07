# Developing FlashForgeUI

This guide is for developers. If you want to install and use the app, read the
[README](README.md).

## What this is

A native Android app (Kotlin + Jetpack Compose, single `:app` module, package
`me.ghost.ffui`) that monitors and controls FlashForge 3D printers over the local
network. All printer protocol logic lives in a separate library,
[`ff-5mp-api-kt`](https://github.com/GhostTypes/ff-5mp-api-kt) (`me.ghost:ff-5mp-api-kt`),
not in this repo.

Working conventions, architecture notes, and the current verification state of each
feature live in [`CLAUDE.md`](CLAUDE.md). Read it before you change code.

## Prerequisites

- **JDK 25** (Temurin works). The Gradle/AGP toolchain runs on it.
- **Android SDK** with platform `android-36.1` and current build tools.
- `local.properties` at the repo root with `sdk.dir=<path to your Android SDK>`.
- A debug keystore at the repo root (`debug.keystore`). If you do not have one:

  ```
  keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  ```

- The `ff-5mp-api-kt` artifact in your local Maven repository. Clone the library
  repo, then run `./gradlew :ffapi:publishToMavenLocal` there. The app currently
  pins version **0.5.0**.

The Gradle wrapper is pinned to Gradle 9.3.1 (required by AGP 9.1.1). You do not
need a local Gradle install.

## Build and test

```
./gradlew assembleDebug        # builds four per-ABI debug APKs under app/build/outputs/apk/debug/
./gradlew test                 # unit + Robolectric + Roborazzi screenshot tests
./gradlew installDebug         # installs the matching ABI on a connected device
./gradlew recordRoborazziDebug # re-records screenshot baselines after intentional UI changes
```

ABI splits are on and there is no universal APK. Emulators are `x86_64`; most
phones are `arm64-v8a`.

## Release signing

Release builds sign with credentials from environment variables:
`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`. Without `KEYSTORE_PATH`, a
release build still packages but stays unsigned (`app-*-release-unsigned.apk`). R8 minification is currently off by choice; revisit
before any public release.

## Tests

The unit suite covers the poll-cadence and print-event logic, discovery packet
parsing (real binary fixtures), Spoolman model parsing, NFC payload round-trips,
job-state mapping, temperature clamp validation, and Robolectric Compose
behavior tests (stop confirmation, chamber-cell gating, settings screen).
Roborazzi pins the dashboard screenshot.

## Notices

This project uses `libmpv` for the camera stream. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
