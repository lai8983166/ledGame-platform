CREATE TABLE IF NOT EXISTS members (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    phone TEXT NOT NULL,
    name TEXT NOT NULL,
    avatar_id TEXT,
    birthday TEXT,
    gender TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN')),
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
