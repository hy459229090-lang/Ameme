# Ameme Android MVP

Native Android 14+ client skeleton with the first encrypted Local Event Node slice. The UI remains synthetic for system acquisition, while user capture, Today restore, date/keyword recall, and local deletion now use a durable SQLCipher repository.

## Scope

- `M-ONB / M-TOD / M-SEA / M-CAL / M-CAP / M-EVT / M-SET / M-DEL` navigation and states.
- A single Today floating record button opening a native Material bottom sheet.
- Active text captures and explicitly labelled mock references are committed to the encrypted local node before they appear in Today. A failed commit stays visible as an error and does not add the event to UI state.
- Today reconstructs the active projection from disk; Search reads the repository by date and keyword.
- Delete appends a tombstone revision and updates the current projection transactionally. This slice proves durable invisibility after reconstruction, not physical purge or peer deletion proof.
- `event_revisions` is append-only by database `BEFORE UPDATE/DELETE` abort triggers; `events_current` is the current projection. Repository construction requires an explicit `space_id`, both tables use `(space_id,event_id)` identity, and reads/writes/deletes are space-scoped.
- Kotlin contract bundle DTO, schema-subset validator, and round-trip tests against `packages/contracts` remain intact.
- Photo, voice, import, location, health, account, network, analytics, Raw Vault, and real system-source integrations are not implemented.
- Production starts with an empty repository and never seeds `FakeMemoryRepository`; synthetic seeds are available only through an explicit test/demo flag. Today shows deterministic counts, not a fabricated fixed summary.

The manifest declares no sensitive permissions. All bundled people, events, IDs, locations, and content are synthetic.

## Storage and key boundary

- Official `net.zetetic:sqlcipher-android:4.15.0@aar` plus `androidx.sqlite:sqlite:2.6.2` is pinned in the app module. See the [official Android repository](https://github.com/sqlcipher/sqlcipher-android), [Maven Central artifact](https://central.sonatype.com/artifact/net.zetetic/sqlcipher-android/4.15.0), and [4.15.0 release notes](https://www.zetetic.net/blog/2026/04/28/sqlcipher-4.15.0-release/).
- Production uses a random 256-bit database key. Android Keystore holds a non-exportable AES-256-GCM wrapping key; app-private preferences contain only the versioned wrapped database-key blob.
- `DatabaseKeyProvider` is injectable. Instrumented tests use a synthetic provider; release code has no hardcoded database password and no plaintext fallback.
- If a wrapped blob exists but its Keystore alias is unavailable, or an encrypted database exists without its wrapped key, opening fails closed.
- Wrapped-key persistence happens before database creation. If first database creation fails, the same wrapped key is reused on retry instead of silently rotating away from the future database identity.
- SQLCipher 4.15.0 retains the supplied key-array reference in its database configuration for pooled/WAL connections. The repository therefore keeps that one array only for the open database lifetime and clears it after SQLCipher closes; open/migration failures clear it immediately. Tests cover both close and rejected-key clearing.
- SQLCipher library logging is routed to `NoopTarget`; application code does not log event content, URLs, or key material.
- Android cloud backup and device transfer exclude root, files, databases, SharedPreferences, external storage, and all device-protected counterparts. `allowBackup=false` remains an additional guard; the compiled XML policy is covered by an instrumented test. See [Android Auto Backup](https://developer.android.com/identity/data/autobackup).

## Toolchain

- JDK 17
- Android SDK Platform 36 / Build Tools 36.0.0
- Gradle Wrapper 8.13
- Android Gradle Plugin 8.13.2
- Kotlin / Compose compiler plugin 2.3.21
- Compose BOM 2026.06.00
- `minSdk 34`, `targetSdk 36`

## Build and test

From `apps/android` with `JAVA_HOME` pointing to JDK 17 and `ANDROID_HOME` to the Android SDK:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

With an API 34+ emulator or device connected:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

The storage instrumented suite verifies on an API 36 x86_64 AVD:

- WAL mode;
- wrong-key rejection and non-plaintext SQLite file header;
- close/repository reconstruction restore, date + keyword reads, and durable tombstone visibility;
- explicit non-destructive v1-to-v2 revision backfill followed by v2-to-v3 `space_legacy` isolation migration;
- cross-space same-event-ID read/delete isolation and database-enforced revision immutability;
- blank text rejection without fabricated user words; empty non-text entries remain explicitly labelled mock references;
- compiled cloud-backup/device-transfer exclusions for every application data domain;
- successful and failed-open key-array clearing;
- Android Keystore wrapped-key reuse after an initial database-creation failure.

This is emulator evidence only. It does not prove physical-device compatibility, 16 KB page-size readiness, OS process-death recovery, 10k/100k capacity, startup or paging performance, backup/restore, physical deletion, FTS5, Raw Vault, or peer sync. Those remain separate Spike/Gate evidence.

Contract tests expect the Android project to remain at `apps/android` so they can read the frozen repository source at `packages/contracts`.
