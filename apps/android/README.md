# Ameme Android MVP

Native Android 14+ client skeleton with an encrypted Local Event Node and the first user-initiated acquisition adapters. Text, Photo Picker, accepted ACTION_SEND content, Today restore, paged date/keyword recall, and local deletion use a durable SQLCipher repository.

## Scope

- `M-ONB / M-TOD / M-SEA / M-CAL / M-CAP / M-EVT / M-SET / M-DEL` navigation and states.
- A single Today floating record button opening a native Material bottom sheet.
- Active text, selected-photo references, and accepted shares are committed to the encrypted local node before they appear in Today. Picker cancellation, blank text, invalid shares, unsupported voice, and failed commits create no event.
- Photo uses Android Photo Picker without media permission. The encrypted SourceLocator stores only a `content://` URI, MIME metadata, and explicit `PersistedRead` or `SessionRead` lifecycle; it does not copy the photo. Persisted grants are released on failed commit and attempted again after user deletion.
- ACTION_SEND accepts only one `text/plain`, `image/*`, or `application/pdf` item. Content shares require a read grant and `content://`; sender titles, unsupported MIME, blank/oversized text, multiple items, and non-content schemes are rejected. ACTION_SEND locators are deliberately recorded as session-only.
- Voice is an explicit replaceable contract in `Unsupported` state. The UI says it is unavailable, requests no microphone permission, and never reports or persists a mock success.
- Calendar has an injectable scoped adapter only: it requires an explicit user action, repository space, non-empty calendar IDs, and a finite window of at most 31 days. Imported synthetic fixtures are `Planned`, never happened/confirmed. No production Calendar Provider connection or calendar permission exists yet.
- Today reconstructs the active projection from disk; Search uses date + keyword keyset pagination. FTS5 is a derived, rebuildable index when runtime creation succeeds and otherwise falls back to parameterized LIKE with the same space/date/state/order filters.
- Delete appends a tombstone revision and updates the current projection transactionally. This slice proves durable invisibility after reconstruction, not physical purge or peer deletion proof.
- `event_revisions` is append-only by database `BEFORE UPDATE/DELETE` abort triggers; `events_current` is the current projection. Repository construction requires an explicit `space_id`, both tables use `(space_id,event_id)` identity, and reads/writes/deletes are space-scoped.
- Kotlin contract bundle DTO, schema-subset validator, and round-trip tests against `packages/contracts` remain intact.
- Voice recording, Calendar Provider, location, health, account, network, analytics, Raw Vault, and bulk/background system-source integrations are not implemented.
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
- explicit non-destructive v1-to-v2 revision backfill, v2-to-v3 `space_legacy` isolation, and v3-to-v4 SourceLocator migration;
- cross-space same-event-ID read/delete isolation and database-enforced revision immutability;
- atomic SourceLocator + Event commit and explicit session-read lifecycle;
- API 36 SQLCipher FTS5 virtual-table creation, FTS/forced-LIKE result equivalence, keyset date pagination without duplicates, and delete filtering;
- ACTION_SEND MIME/URI/read-grant validation, injected-title isolation, blank/multiple-item rejection, and session-only fallback;
- Photo Picker cancellation/no-event behavior and source-lifecycle propagation;
- user-initiated finite Calendar import scope, calendar ID/space/window negative cases, and `Planned` semantics;
- blank text and unsupported voice rejection without fabricated events;
- absence of media, microphone, calendar, and location manifest permissions;
- compiled cloud-backup/device-transfer exclusions for every application data domain;
- successful and failed-open key-array clearing;
- Android Keystore wrapped-key reuse after an initial database-creation failure.

This is API 36 AVD evidence only. It does not prove physical-device compatibility, 16 KB page-size readiness, OS process-death recovery, 10k/100k capacity, startup or paging performance, backup/restore, physical deletion, Calendar Provider/voice behavior, Raw Vault, or peer sync. FTS5 is verified only for the tested SQLCipher runtime; other runtime/device combinations may use the tested LIKE fallback.

Contract tests expect the Android project to remain at `apps/android` so they can read the frozen repository source at `packages/contracts`.
