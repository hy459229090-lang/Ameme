# Ameme Android MVP

Native Android 14+ client skeleton with an encrypted Local Event Node and user-initiated acquisition adapters. Text, Photo Picker, accepted ACTION_SEND content, scoped Calendar Provider import, explicit system voice results, Today restore, paged date/keyword recall, and local deletion use a durable SQLCipher repository. Compose routes repository and platform-source I/O through an injected dispatcher; cancellation during open cannot orphan an already-created repository.

## Scope

- `M-ONB / M-TOD / M-SEA / M-CAL / M-CAP / M-EVT / M-SET / M-DEL` navigation and states.
- A single Today floating record button opening a native Material bottom sheet.
- Active text, selected-photo references, and accepted shares are committed to the encrypted local node before they appear in Today. Picker cancellation, blank text, invalid shares, unsupported voice, and failed commits create no event.
- Photo uses Android Photo Picker without media permission. The encrypted SourceLocator stores only a `content://` URI, MIME metadata, and explicit `PersistedRead` or `SessionRead` lifecycle; it does not copy the photo.
- Deleting a persisted locator is crash-safe and two-phase: the Event tombstone transaction changes the locator to `RELEASE_PENDING`, ordinary source reads can no longer use it, and a space-scoped coordinator releases the OS grant outside the database transaction. The coordinator runs after repository open and after UI deletion; false/exception remains pending, restart retries, and success or a verified already-absent grant moves to `RELEASED`. Session-only locators terminate directly. Agent/API repository deletion gets the same pending state even when no UI is involved.
- ACTION_SEND accepts only one `text/plain`, `image/*`, or `application/pdf` item. Content shares require a read grant and `content://`; sender titles, unsupported MIME, blank/oversized text, multiple items, and non-content schemes are rejected. ACTION_SEND locators are deliberately recorded as session-only.
- Voice is initiated only by `MediaStore.Audio.Media.RECORD_SOUND_ACTION` or the system `OpenDocument` picker. Ameme requests no microphone permission, accepts only a returned readable `content://` audio URI, stores no fabricated transcript/user words, and creates no event on cancel, empty URI, missing read grant, duplicate callback, or launcher failure. Repository work runs off the main thread and uses a single-flight result gate.
- Calendar import starts only from `记录 -> 导入日历`. The app asks for `READ_CALENDAR` at that moment, lists visible calendars, and requires the user to select calendar IDs plus a 1/7/31-day range before confirmation. Provider queries bind the selected IDs, exact window, and a 200-item limit; the adapter rejects background/non-user calls, wrong spaces, scope escapes, overflow, permission denial, and cancellation. It never requests `WRITE_CALENDAR`, schedules background scans, or upgrades a calendar plan into a happened/confirmed fact.
- A Calendar import batch is committed to Event + `ProviderRead` SourceLocator rows in one SQLCipher transaction. Cancellation remains available through provider read/validation; after the atomic commit starts it is no longer presented as cancelled, and repository closure waits for the owned I/O job. User deletion tombstones the Event and directly terminates `ProviderRead`; persisted picker grants continue through the existing crash-safe release state machine.
- Today reconstructs the active projection from disk; Search uses date + keyword keyset pagination. FTS5 is a derived, rebuildable index when runtime creation succeeds and otherwise falls back to parameterized LIKE. Both paths implement the tested common query contract: up to 16 whitespace-separated terms, every term must match somewhere in the Event fields, with identical space/date/state/order/delete filters. This does not claim every FTS tokenizer edge case equals substring matching.
- Delete appends a tombstone revision and updates the current projection transactionally. This slice proves durable invisibility after reconstruction, not physical purge or peer deletion proof.
- `event_revisions` is append-only by database `BEFORE UPDATE/DELETE` abort triggers; `events_current` is the current projection. Repository construction requires an explicit `space_id`, both tables use `(space_id,event_id)` identity, and reads/writes/deletes are space-scoped.
- Kotlin contract bundle DTO, schema-subset validator, and round-trip tests against `packages/contracts` remain intact.
- Location, health, account, network, analytics, Raw Vault, and bulk/background system-source integrations are not implemented.
- Production starts with an empty repository and never seeds `FakeMemoryRepository`; synthetic seeds are available only through an explicit test/demo flag. Today shows deterministic counts, not a fabricated fixed summary.
- `AgentLocalNodeTransport` now freezes the application-layer port needed by a future authenticated Agent-to-Android path: exact space/type scope, content-free retry/audit metadata, domain-separated idempotency slot, payload digest, and redacted opaque payload. It intentionally has no implementation and defines no LAN wire format. Real Codex/Agent access to the Android SQLCipher Local Node remains blocked on approved device discovery, authentication, encryption, replay protection, background lifecycle, and transport evidence; Android never starts or falls back to the Python Oracle host.

The manifest declares only `READ_CALENDAR` among the acquisition-sensitive permissions. It declares no calendar write, microphone, camera, media-library, location, or health permission. All bundled people, events, IDs, locations, and content are synthetic.

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

Run only one instrumentation/connected-test process per device serial. These tasks reinstall the same application ID; concurrent runs on one serial can kill the other process and invalidate both reports. The DB-01 performance runner acquires a per-serial process mutex and refuses to start when that lock is held:

```powershell
../../scripts/validation/run_android_db01_performance.ps1 -Serial emulator-5554
```

The explicit performance test is skipped during ordinary connected regression. Its content-free JSON covers 10k/100k capacity, cold database open, FTS and forced-LIKE query paths, date/keyset pagination, single-capture commit, coarse process memory snapshots, disk use, and a reproducible 10k-row v3-to-current migration fixture. Performance budget failures remain in the JSON as fail evidence; missing datasets, privacy markers, migration evidence, or semantic equivalence fail the runner. No valid DB-01 performance baseline is committed yet.

The storage instrumented suite verifies on an API 36 x86_64 AVD:

- WAL mode;
- wrong-key rejection and non-plaintext SQLite file header;
- close/repository reconstruction restore, date + keyword reads, and durable tombstone visibility;
- explicit non-destructive v1-to-v2 revision backfill, v2-to-v3 `space_legacy` isolation, and v3-to-v4 SourceLocator migration;
- cross-space same-event-ID read/delete isolation and database-enforced revision immutability;
- atomic SourceLocator + Event commit and explicit session-read lifecycle;
- crash-safe `RELEASE_PENDING` persistence across repository reconstruction, release false/exception retention, restart retry, terminal release, Agent/direct delete behavior, ordinary-read hiding, and cross-space cleanup isolation;
- API 36 SQLCipher FTS5 virtual-table creation, tested multi-term AND-contract equivalence with forced LIKE, keyset date pagination without duplicates, and delete filtering;
- ACTION_SEND MIME/URI/read-grant validation, injected-title isolation, blank/multiple-item rejection, and session-only fallback;
- Photo Picker cancellation/no-event behavior and source-lifecycle propagation;
- user-initiated finite Calendar import scope, calendar ID/space/window negative cases, and `Planned` semantics;
- atomic Calendar batch rollback, permission denial/cancel behavior, provider query scope/limit, and `ProviderRead` deletion without persisted-grant cleanup;
- explicit voice cancel/read-grant/content-URI/single-flight behavior without fabricated transcript or event;
- presence of calendar read permission and absence of media, microphone, calendar write, and location permissions;
- compiled cloud-backup/device-transfer exclusions for every application data domain;
- successful and failed-open key-array clearing;
- Android Keystore wrapped-key reuse after an initial database-creation failure.
- Agent Local Node port exact-scope rejection, strict protocol/request-response binding, opaque payload defensive copy/redaction, and explicit payload clearing.

The existing device baseline is API 36 AVD evidence only. The new Calendar/voice production paths still require a clean, serialized API 36 run after integration plus physical-device checks for OEM calendar providers, recurring/all-day/timezone behavior, system recorder availability/result grants, process recreation, and permission revocation. Nothing here proves 16 KB page-size readiness, 10k/100k capacity, startup/paging performance, backup/restore, physical deletion, Raw Vault, or peer sync. FTS5 is verified only for the tested SQLCipher runtime; other runtime/device combinations may use the tested LIKE fallback.

Contract tests expect the Android project to remain at `apps/android` so they can read the frozen repository source at `packages/contracts`.
