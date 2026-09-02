CREATE TABLE IF NOT EXISTS members (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    phone TEXT NOT NULL,
    name TEXT NOT NULL,
    avatar_id TEXT,
    birthday TEXT,
    gender TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN')),
    deleted_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_members_active_phone
    ON members(phone) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS wristbands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_uid TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'IN_STOCK' CHECK (status IN ('IN_STOCK', 'CHARGED', 'READY', 'ACTIVE', 'EXPIRED')),
    duration_minutes INTEGER,
    charged_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wristband_charge_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    wristband_id INTEGER NOT NULL REFERENCES wristbands(id),
    wristband_uid TEXT NOT NULL,
    duration_minutes INTEGER NOT NULL,
    unit_price_cents INTEGER NOT NULL,
    amount_cents INTEGER NOT NULL,
    charged_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_wristband_charges_charged_at
    ON wristband_charge_records(charged_at);

CREATE TABLE IF NOT EXISTS wristband_bindings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    wristband_id INTEGER NOT NULL REFERENCES wristbands(id),
    member_id INTEGER NOT NULL REFERENCES members(id),
    status TEXT NOT NULL CHECK (status IN ('READY', 'ACTIVE', 'EXPIRED', 'RETURNED')),
    duration_minutes INTEGER NOT NULL,
    bound_at TEXT NOT NULL,
    started_at TEXT,
    ended_at TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_open_wristband_binding
    ON wristband_bindings(wristband_id) WHERE status IN ('READY', 'ACTIVE');

CREATE INDEX IF NOT EXISTS ix_bindings_member ON wristband_bindings(member_id, status);

CREATE TABLE IF NOT EXISTS game_play_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL REFERENCES members(id),
    binding_id INTEGER NOT NULL REFERENCES wristband_bindings(id),
    wristband_uid TEXT NOT NULL,
    device_id TEXT NOT NULL,
    room_id TEXT,
    external_session_id TEXT NOT NULL,
    participant_index INTEGER NOT NULL DEFAULT 0,
    game_id TEXT NOT NULL,
    game_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'ABORTED')),
    started_at TEXT NOT NULL,
    ended_at TEXT,
    success INTEGER,
    termination_reason TEXT,
    raw_score INTEGER,
    points_awarded INTEGER NOT NULL DEFAULT 0,
    scoring_policy TEXT,
    result_json TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_running_play_binding
    ON game_play_records(binding_id) WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS ix_game_plays_member_started
    ON game_play_records(member_id, started_at DESC);

CREATE TABLE IF NOT EXISTS room_settings (
    room_ip TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS operator_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    account_type TEXT NOT NULL CHECK (account_type IN ('FACTORY_ADMIN', 'OPERATOR')),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_by_operator_id INTEGER REFERENCES operator_accounts(id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS operator_action_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operator_id INTEGER NOT NULL REFERENCES operator_accounts(id),
    operator_username TEXT NOT NULL,
    operator_display_name TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT,
    summary_json TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_operator_action_logs_operator_created
    ON operator_action_logs(operator_id, created_at DESC);

CREATE TABLE IF NOT EXISTS database_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    instance_id TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    last_business_modified_at TEXT NOT NULL,
    imported_from_revision INTEGER,
    imported_at TEXT
);

INSERT OR IGNORE INTO database_state(
    id, instance_id, revision, last_business_modified_at,
    imported_from_revision, imported_at)
VALUES (
    1, lower(hex(randomblob(16))), 0,
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), NULL, NULL
);
