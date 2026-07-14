# Ameme Android MVP

Native Android 14+ client with an encrypted Local Event Node, user-initiated acquisition adapters, a paired Agent capture channel, and an explicit-consent daily summary. Text, Photo Picker, accepted ACTION_SEND content, scoped Calendar Provider import, explicit system voice results, Today restore, paged date/keyword recall, local deletion, DayLedger revision and summaries use a durable SQLCipher repository. Compose routes repository and platform-source I/O through an injected dispatcher; cancellation during open cannot orphan an already-created repository.

## Scope

- `M-ONB / M-TOD / M-SEA / M-CAL / M-CAP / M-EVT / M-SET / M-DEL` navigation and states.
- A single Today floating record button opening a native Material bottom sheet.
- Active text, selected-photo references, accepted shares, explicit voice references, and scoped calendar plans are committed to the encrypted local node before they appear in Today. Picker/recorder cancellation, blank text, invalid shares or voice results, and failed commits create no event.
- Photo uses Android Photo Picker without media permission. The encrypted SourceLocator stores only a `content://` URI, MIME metadata, and explicit `PersistedRead` or `SessionRead` lifecycle; it does not copy the photo.
- Deleting a persisted locator is crash-safe and two-phase: the Event tombstone transaction changes the locator to `RELEASE_PENDING`, ordinary source reads can no longer use it, and a space-scoped coordinator releases the OS grant outside the database transaction. The coordinator runs after repository open and after UI deletion; false/exception remains pending, restart retries, and success or a verified already-absent grant moves to `RELEASED`. Session-only locators terminate directly. Agent/API repository deletion gets the same pending state even when no UI is involved.
- ACTION_SEND accepts only one `text/plain`, `image/*`, or `application/pdf` item. Explicit shared text is stored as `UserAsserted` evidence and can enter a daily summary; image/PDF content remains `Processing` until an actual processor exists. Content shares require a read grant and `content://`; sender titles, unsupported MIME, blank/oversized text, multiple items, and non-content schemes are rejected. ACTION_SEND locators are deliberately recorded as session-only.
- Voice is initiated only by `MediaStore.Audio.Media.RECORD_SOUND_ACTION` or the system `OpenDocument` picker. Ameme requests no microphone permission, accepts only a returned readable `content://` audio URI, stores no fabricated transcript/user words, and creates no event on cancel, empty URI, missing read grant, duplicate callback, or launcher failure. Repository work runs off the main thread and uses a single-flight result gate.
- Calendar import starts only from `记录 -> 导入日历`. The app asks for `READ_CALENDAR` at that moment, lists visible calendars, and requires the user to select calendar IDs plus a 1/7/31-day range before confirmation. Provider queries bind the selected IDs, exact window, and a 200-item limit; an in-process physical-row budget stops providers that ignore the query limit even when rows have blank names or titles. The adapter rejects background/non-user calls, wrong spaces, scope escapes, overflow, permission denial, and cancellation. It never requests `WRITE_CALENDAR`, schedules background scans, or upgrades a calendar plan into a happened/confirmed fact.
- A Calendar import batch is committed to Event + `ProviderRead` SourceLocator rows in one SQLCipher transaction through the shared I/O executor. Cancellation remains available through provider read/validation; commit then reaches an explicit completed or failed terminal state and UI ownership is released on every result. Active instances are idempotent by space + Calendar locator + exact instance start, including within one batch and after process reconstruction. Android all-day rows preserve the UTC calendar date, store no clock time, and remain visibly labelled as all-day plans. User deletion tombstones the Event and directly terminates `ProviderRead`; persisted picker grants continue through the existing crash-safe release state machine.
- Today reconstructs the active projection from disk; Search uses date + keyword keyset pagination. FTS5 is a derived, rebuildable index when runtime creation succeeds and otherwise falls back to parameterized LIKE. Both paths implement the tested common query contract: up to 16 whitespace-separated terms, every term must match somewhere in the Event fields, with identical space/date/state/order/delete filters. This does not claim every FTS tokenizer edge case equals substring matching.
- Delete appends a tombstone revision and updates the current projection transactionally. This slice proves durable invisibility after reconstruction, not physical purge or peer deletion proof.
- `event_revisions` is append-only by database `BEFORE UPDATE/DELETE` abort triggers; `events_current` is the current projection. Repository construction requires an explicit `space_id`, both tables use `(space_id,event_id)` identity, and reads/writes/deletes are space-scoped.
- Schema v6 adds bounded Event policy fields, `day_ledgers`, `day_summaries`, and `agent_idempotency`. A summary is generated only after the user confirms sending the day's eligible structured projection; photos, audio files, locators, search history and Restricted events are excluded. Results bind the DayLedger revision, become stale after later Event changes, survive process restart, and are removed with the affected day when its Event set is deleted.
- The inference client accepts HTTPS in all builds. Debug additionally allows only loopback and the Android emulator host alias `10.0.2.2`; Release ships with no inference base URL and does not enable cleartext. Provider failure never rolls back captured events and never falls back to a fabricated template.
- Kotlin contract bundle DTO, schema-subset validator, and round-trip tests against `packages/contracts` remain intact.
- Location, health, account, analytics, Raw Vault, LAN discovery/background service, and bulk/background system-source integrations are not implemented.
- Production starts with an empty repository and never seeds `FakeMemoryRepository`; synthetic seeds are available only through an explicit test/demo flag. Today shows deterministic counts, not a fabricated fixed summary.
- `MemoryRepositoryAgentLocalNodeEndpoint` now implements the process-local `create_event` application slice over the real Android repository. It strictly consumes the shared `ameme.agent-local-node.v1` canonical JSON payload/result profile from `packages/contracts/schemas/ameme-agent-local-node.schema.json` and the Agent golden vector, binds request identity to a separately verified session, allows a Grant scope to be a superset of the payload-derived one-space/one-type request scope, verifies the canonical payload digest, and maps responses to the frozen `ok|error` result/error contract.
- This slice is capture-only. Sensitivity and data class must be present in the separately verified session (the current synthetic test Grant allows only `personal` + `structured`), and capture accepts the safe evidence/fact pairs `observed/confirmed`, `user_asserted/user_asserted`, and `inferred/low_confidence_candidate`. After authentication, all other v1 operations return non-retryable `OPERATION_UNSUPPORTED`; an unauthenticated caller cannot probe channel capability that way. Android repository capture creates only initial revision `1`; append revision and undo are not implemented by this endpoint.
- The production App can create/revoke one active 30-day pairing. The shared secret is generated once, shown once, then stored only under Android Keystore AES-GCM wrapping; the TLS identity is non-exportable and the exported pairing JSON contains only references, device/session binding, endpoint and certificate pin. The App process owns a TLS 1.3 listener with pin + mutual HMAC handshake and strict sequence/nonce frames.
- Paired `create_event` uses the SQLCipher `agent_idempotency` registry in the same database transaction as Event capture. Same caller/grant/purpose/space/type/operation/slot with the same digest replays the original result; a different digest conflicts across process restart. The pairing-scoped Host is the current MVP root of trust; it is not yet backed by a shared account Grant registry.
- A gated androidTest-only Codex demo seeder can consume a bounded, locally generated Skill/MCP seed and commit it through the same `MemoryRepositoryAgentLocalNodeEndpoint` into the production SQLCipher repository. It runs only with the explicit `amemeCodexDemoSeed=true` instrumentation argument, deletes the app-private one-time seed before returning, logs counts only, and exposes no production APK import endpoint. The companion PowerShell runner rejects seeds inside the Git workspace.
- `scripts/dev/agent/smoke_paired_android.py` proves the real Host MCP → TLS channel → Android application endpoint → SQLCipher → Today path without using ADB to inject an Event, including restart persistence and pairing revocation. This is API 36 AVD evidence. NSD/LAN discovery, physical-device reachability, background execution, shared account authorization, append/undo/recall and unattended Host integration remain outside this slice; Android never starts or falls back to the Python Oracle host.

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

The explicit performance test is skipped during ordinary connected regression. Its content-free JSON covers 10k/100k capacity, cold database open, FTS and forced-LIKE query paths, date/keyset pagination, single-capture commit, coarse process memory snapshots, disk use, and a reproducible 10k-row v3-to-current migration fixture. Performance budget failures remain in the JSON as fail evidence; missing datasets, privacy markers, migration evidence, or semantic equivalence fail the runner. The validated API 36 x86_64 AVD baseline is committed at [`tests/results/performance/android-db01-api36.json`](../../tests/results/performance/android-db01-api36.json): at 100k events, FTS keyword-page p95 is 177.72 ms against the 700 ms DB-query target, single-capture commit p95 is 215.67 ms against 300 ms, the encrypted database is 95,227,904 bytes, and FTS/forced-LIKE result IDs are equal for both tested query shapes. These are database/AVD measurements, not UI or physical-device claims.

The storage instrumented suite verifies on an API 36 x86_64 AVD:

- WAL mode;
- wrong-key rejection and non-plaintext SQLite file header;
- close/repository reconstruction restore, date + keyword reads, and durable tombstone visibility;
- explicit non-destructive v1-to-v2 revision backfill, v2-to-v3 `space_legacy` isolation, v3-to-v4 SourceLocator migration, v4-to-v5 source-instance identity migration, and v5-to-v6 Event policy/DayLedger/Summary migration;
- cross-space same-event-ID read/delete isolation and database-enforced revision immutability;
- atomic SourceLocator + Event commit and explicit session-read lifecycle;
- crash-safe `RELEASE_PENDING` persistence across repository reconstruction, release false/exception retention, restart retry, terminal release, Agent/direct delete behavior, ordinary-read hiding, and cross-space cleanup isolation;
- API 36 SQLCipher FTS5 virtual-table creation, tested multi-term AND-contract equivalence with forced LIKE, keyset date pagination without duplicates, and delete filtering;
- ACTION_SEND MIME/URI/read-grant validation, injected-title isolation, blank/multiple-item rejection, and session-only fallback;
- Photo Picker cancellation/no-event behavior and source-lifecycle propagation;
- user-initiated finite Calendar import scope, calendar ID/space/window negative cases, and `Planned` semantics;
- atomic Calendar batch rollback, commit completion/failure, idempotent active instances, all-day date semantics, permission denial/cancel behavior, provider query/physical-row limits, and `ProviderRead` deletion without persisted-grant cleanup;
- explicit voice cancel/read-grant/content-URI/single-flight behavior without fabricated transcript or event;
- presence of calendar read permission and absence of media, microphone, calendar write, and location permissions;
- compiled cloud-backup/device-transfer exclusions for every application data domain;
- successful and failed-open key-array clearing;
- Android Keystore wrapped-key reuse after an initial database-creation failure.
- Agent Local Node golden canonical JSON payload/result bytes and digests, strict duplicate-key/NUL/lone-surrogate rejection, Grant-superset/request-subset authorization, request scope mismatch, digest tampering, stable unsupported-operation and authorization errors, replay/conflict behavior, response binding, redaction, and explicit request/result payload clearing.
- A targeted SQLCipher Agent endpoint test commits `create_event`, closes the repository, reopens it with the same synthetic key and space, and verifies the Event remains readable from encrypted storage.
- DayLedger revision, insufficient/processing/ready/stale transitions, compare-and-set summary completion, summary removal after deletion, structured-only gateway projection, Android `GMT` timezone handling, and durable Agent idempotency across repository reconstruction.
- Android Keystore pairing creation/load/revoke, one-time secret wrapping, TLS certificate pin stability, and the explicitly gated live local-gateway test.

The merged ordinary device baseline on 2026-07-14 discovered 52 API 36 AVD tests: 45 passed and 7 explicitly gated tests were skipped. The gated local-gateway test separately passed 1/1, and the paired Agent end-to-end smoke passed with durable capture, Today visibility, restart persistence and no ADB Event injection. Calendar/voice integration, provider denial/cancellation, all-day identity, repository lifecycle, the 501-event batch fast path, pairing lifecycle and summary persistence are covered on that AVD. Physical-device checks remain required for OEM calendar providers, recurring/all-day/timezone behavior, system recorder availability/result grants, process recreation, permission revocation, 16 KB page-size readiness, UI startup/paging, backup/restore, physical deletion, Raw Vault, peer sync and Agent LAN/background behavior. FTS5 is verified only for the tested SQLCipher runtime; other runtime/device combinations may use the tested LIKE fallback.

Contract tests expect the Android project to remain at `apps/android` so they can read the frozen repository source at `packages/contracts`.
