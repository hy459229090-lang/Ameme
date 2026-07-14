"""SQLite logical schema for the non-production core oracle."""

SCHEMA_VERSION = 2

SCHEMA_SQL = r"""
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_migrations (
    migration_id INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS id_sequence (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    next_value INTEGER NOT NULL
);
INSERT OR IGNORE INTO id_sequence(singleton, next_value) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key TEXT PRIMARY KEY,
    command TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    result_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS acquisition_contracts (
    contract_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    permission_state TEXT NOT NULL,
    revocation_state TEXT NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS source_objects (
    source_object_id TEXT PRIMARY KEY,
    contract_id TEXT NOT NULL REFERENCES acquisition_contracts(contract_id),
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    source_type TEXT NOT NULL,
    capture_method TEXT NOT NULL,
    acquired_at TEXT NOT NULL,
    occurred_start TEXT,
    local_date TEXT,
    timezone TEXT,
    content_hash TEXT,
    processing_state TEXT NOT NULL,
    sensitivity TEXT NOT NULL,
    locator_state TEXT NOT NULL,
    locator_json TEXT,
    payload_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS source_objects_space_date
    ON source_objects(space_id, local_date, acquired_at);

CREATE TABLE IF NOT EXISTS raw_manifests (
    raw_object_id TEXT PRIMARY KEY,
    source_object_id TEXT NOT NULL REFERENCES source_objects(source_object_id),
    space_id TEXT NOT NULL,
    relative_path TEXT NOT NULL UNIQUE,
    key_id TEXT NOT NULL,
    nonce_b64 TEXT NOT NULL UNIQUE,
    aad_json TEXT NOT NULL,
    plaintext_sha256 TEXT NOT NULL,
    ciphertext_sha256 TEXT NOT NULL,
    plaintext_size INTEGER NOT NULL CHECK (plaintext_size >= 0),
    ciphertext_size INTEGER NOT NULL CHECK (ciphertext_size >= 16),
    mime_type TEXT NOT NULL,
    retention_class TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT,
    deletion_state TEXT NOT NULL,
    selected_for_sync INTEGER NOT NULL CHECK (selected_for_sync IN (0, 1)),
    state TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS raw_manifests_source
    ON raw_manifests(source_object_id, state);
CREATE INDEX IF NOT EXISTS raw_manifests_expiry
    ON raw_manifests(state, retention_class, expires_at);

CREATE TABLE IF NOT EXISTS source_locator_history (
    history_id TEXT PRIMARY KEY,
    source_object_id TEXT NOT NULL REFERENCES source_objects(source_object_id),
    state TEXT NOT NULL,
    reason TEXT NOT NULL,
    locator_json TEXT,
    recorded_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_addenda (
    addendum_id TEXT PRIMARY KEY,
    source_object_id TEXT REFERENCES source_objects(source_object_id),
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    text TEXT NOT NULL,
    submitted_at TEXT NOT NULL,
    event_time_json TEXT,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    assertion_scope TEXT NOT NULL,
    sensitivity TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS observations (
    observation_id TEXT PRIMARY KEY,
    source_object_id TEXT NOT NULL REFERENCES source_objects(source_object_id),
    space_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    value_json TEXT NOT NULL,
    time_range_json TEXT,
    fact_status TEXT NOT NULL,
    confidence REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    parser_version TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS observations_source
    ON observations(source_object_id, created_at);

CREATE TABLE IF NOT EXISTS event_candidates (
    candidate_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    status TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS event_revisions (
    write_order INTEGER PRIMARY KEY AUTOINCREMENT,
    event_revision_id TEXT NOT NULL UNIQUE,
    event_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 1),
    base_revision INTEGER,
    actor TEXT NOT NULL,
    reason TEXT NOT NULL,
    changes_json TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    conflict_json TEXT,
    created_at TEXT NOT NULL,
    UNIQUE(event_id, revision)
);
CREATE INDEX IF NOT EXISTS event_revisions_event
    ON event_revisions(event_id, revision);

CREATE TRIGGER IF NOT EXISTS event_revisions_no_update
BEFORE UPDATE ON event_revisions BEGIN
    SELECT RAISE(ABORT, 'event_revisions are append-only');
END;
CREATE TRIGGER IF NOT EXISTS event_revisions_no_delete
BEFORE DELETE ON event_revisions BEGIN
    SELECT RAISE(ABORT, 'event_revisions are append-only');
END;

CREATE TABLE IF NOT EXISTS events_current (
    event_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    occurred_start TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    fact_status TEXT NOT NULL,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL,
    revision_head_id TEXT NOT NULL,
    snapshot_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS events_current_space_date
    ON events_current(space_id, local_date, occurred_start);

CREATE TABLE IF NOT EXISTS field_evidence (
    event_revision_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    field_name TEXT NOT NULL,
    observation_id TEXT NOT NULL REFERENCES observations(observation_id),
    source_object_id TEXT NOT NULL REFERENCES source_objects(source_object_id),
    confidence REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    status TEXT NOT NULL,
    PRIMARY KEY(event_revision_id, field_name, observation_id)
);
CREATE INDEX IF NOT EXISTS field_evidence_event
    ON field_evidence(event_id, revision, field_name);
CREATE INDEX IF NOT EXISTS field_evidence_source
    ON field_evidence(source_object_id, event_id);

CREATE TABLE IF NOT EXISTS episode_revisions (
    write_order INTEGER PRIMARY KEY AUTOINCREMENT,
    episode_revision_id TEXT NOT NULL UNIQUE,
    episode_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 1),
    base_revision INTEGER,
    actor TEXT NOT NULL,
    reason TEXT NOT NULL,
    changes_json TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(episode_id, revision)
);
CREATE INDEX IF NOT EXISTS episode_revisions_episode
    ON episode_revisions(episode_id, revision);

CREATE TRIGGER IF NOT EXISTS episode_revisions_no_update
BEFORE UPDATE ON episode_revisions BEGIN
    SELECT RAISE(ABORT, 'episode_revisions are append-only');
END;
CREATE TRIGGER IF NOT EXISTS episode_revisions_no_delete
BEFORE DELETE ON episode_revisions BEGIN
    SELECT RAISE(ABORT, 'episode_revisions are append-only');
END;

CREATE TABLE IF NOT EXISTS episodes_current (
    episode_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    occurred_start TEXT NOT NULL,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL,
    revision_head_id TEXT NOT NULL,
    snapshot_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS episodes_current_space_date
    ON episodes_current(space_id, local_date, occurred_start);

CREATE TABLE IF NOT EXISTS ledger_identities (
    day_ledger_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    timezone TEXT NOT NULL,
    UNIQUE(owner_id, space_id, local_date, timezone)
);

CREATE TABLE IF NOT EXISTS day_coverage_inputs (
    day_ledger_id TEXT PRIMARY KEY REFERENCES ledger_identities(day_ledger_id),
    coverage_state TEXT NOT NULL,
    partial_reasons_json TEXT NOT NULL,
    version INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS day_ledgers (
    day_ledger_id TEXT PRIMARY KEY REFERENCES ledger_identities(day_ledger_id),
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    local_date TEXT NOT NULL,
    timezone TEXT NOT NULL,
    revision INTEGER NOT NULL,
    coverage_state TEXT NOT NULL,
    partial_reasons_json TEXT NOT NULL,
    summary_state TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS day_ledger_entries (
    day_ledger_id TEXT NOT NULL REFERENCES day_ledgers(day_ledger_id),
    object_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    sort_key TEXT NOT NULL,
    PRIMARY KEY(day_ledger_id, object_type, object_id)
);

CREATE TABLE IF NOT EXISTS lineage_edges (
    lineage_edge_id TEXT PRIMARY KEY,
    from_type TEXT NOT NULL,
    from_id TEXT NOT NULL,
    to_type TEXT NOT NULL,
    to_id TEXT NOT NULL,
    relationship TEXT NOT NULL,
    processor_version TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(from_type, from_id, to_type, to_id, relationship)
);
CREATE INDEX IF NOT EXISTS lineage_edges_from
    ON lineage_edges(from_type, from_id);

CREATE TABLE IF NOT EXISTS tombstones (
    tombstone_id TEXT PRIMARY KEY,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(target_type, target_id)
);

CREATE TABLE IF NOT EXISTS deletion_jobs (
    deletion_job_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    space_id TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    state TEXT NOT NULL,
    affected_json TEXT NOT NULL,
    proof_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS durable_jobs (
    job_id TEXT PRIMARY KEY,
    queue_type TEXT NOT NULL,
    queue_idempotency_key TEXT NOT NULL UNIQUE,
    payload_hash TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    priority INTEGER NOT NULL CHECK (priority >= 0 AND priority <= 100),
    state TEXT NOT NULL,
    lease_owner TEXT,
    lease_expires_at TEXT,
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts >= 1),
    available_at TEXT NOT NULL,
    last_error_code TEXT,
    result_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS durable_jobs_ready
    ON durable_jobs(state, available_at, priority DESC, created_at);

CREATE VIRTUAL TABLE IF NOT EXISTS recall_fts USING fts5(
    event_id UNINDEXED,
    title,
    description,
    tokenize = 'unicode61'
);
"""
