# Ameme Android MVP

Native Android 14+ product skeleton for the approved Ameme mobile semantics. It uses Kotlin, Jetpack Compose, Material 3, synthetic data, and mock capture interactions.

## Scope

- `M-ONB / M-TOD / M-SEA / M-CAL / M-CAP / M-EVT / M-SET / M-DEL` navigation and states.
- A single Today floating record button opening a native Material bottom sheet.
- Synthetic `empty / sparse / loading / partial / offline / recoverable error / permission limited` experiences.
- Kotlin contract bundle DTO, schema-subset validator, and round-trip tests against `packages/contracts`.
- No real acquisition, account, storage, network, analytics, health, location, photo, calendar, or microphone integration.

The manifest intentionally declares no sensitive permissions. All people, events, IDs, locations, and content shown by the app are synthetic.

## Toolchain

- JDK 17
- Android SDK Platform 36 / Build Tools 36.0.0
- Gradle Wrapper 8.13
- Android Gradle Plugin 8.13.2
- Kotlin / Compose compiler plugin 2.3.21
- Compose BOM 2026.06.00
- `minSdk 34`, `targetSdk 36`

## Build and test

From `apps/android`:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

With an Android 14+ emulator or device connected:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

Instrumented UI tests cannot run without a booted emulator/device. Contract tests expect the Android project to remain at `apps/android` so they can read the frozen repository source at `packages/contracts`.

## Data and permission boundary

This slice is a UI/contract-runtime skeleton, not Local Event Core. `FakeMemoryRepository` is process-local and non-durable. Capture actions create synthetic in-memory records and never open system pickers or request permissions. Do not add real adapters before storage, policy, permission, deletion, and privacy gates are implemented together.
