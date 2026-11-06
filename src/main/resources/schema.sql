-- Esquema versionado a mano. Se ejecuta en cada arranque; por eso todo es IF NOT EXISTS.
-- Cambiar una tabla ya creada requiere un paso explícito (no hay Flyway).
-- Fechas: texto ISO-8601 en UTC (SQLite no tiene tipo fecha nativo).
-- Se crean desde la Fase 1 todas las tablas del diseño para no alterar el esquema más adelante.

CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    -- COLLATE NOCASE: "A@x.com" y "a@x.com" son la misma cuenta (UNIQUE sin distinguir mayúsculas).
    email           TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    password_hash   TEXT    NOT NULL,
    role            TEXT    NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    enabled         INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    email_verified  INTEGER NOT NULL DEFAULT 0 CHECK (email_verified IN (0, 1)),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until    TEXT,
    lock_level      INTEGER NOT NULL DEFAULT 0,
    -- Se incrementa para invalidar al instante todos los access token ya emitidos (cierre forzado).
    token_version   INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Todos los tokens derivados por rotación de un mismo login comparten family_id.
    family_id  TEXT    NOT NULL,
    -- Solo se guarda el hash (SHA-256) del token opaco, nunca el token.
    token_hash TEXT    NOT NULL UNIQUE,
    expires_at TEXT    NOT NULL,
    rotated_at TEXT,
    revoked_at TEXT,
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user   ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE IF NOT EXISTS one_time_tokens (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    TEXT    NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash TEXT    NOT NULL UNIQUE,
    expires_at TEXT    NOT NULL,
    used_at    TEXT,
    created_at TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS notes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- Propietario: toda consulta filtra por user_id para impedir acceso a notas ajenas.
    user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      TEXT    NOT NULL,
    body       TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes (user_id);

CREATE TABLE IF NOT EXISTS audit_log (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      TEXT    NOT NULL,
    event   TEXT    NOT NULL,
    -- Sin FK a propósito: el rastro de auditoría debe sobrevivir al borrado del usuario.
    user_id INTEGER,
    ip      TEXT,
    -- Nunca contraseñas, tokens ni hashes.
    detail  TEXT
);

-- Segundo factor TOTP (RFC 6238). Se añaden tablas nuevas con IF NOT EXISTS: las existentes no se modifican.
CREATE TABLE IF NOT EXISTS user_totp (
    user_id        INTEGER PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- Secreto en Base32 CIFRADO con AES-GCM (clave TOTP_ENCRYPTION_KEY). Hay que poder descifrarlo para verificar
    -- códigos, por eso no vale un hash; si se filtra solo la BD, sin la clave no sirve.
    secret_enc     TEXT    NOT NULL,
    -- 0 = pendiente de confirmar con un primer código; 1 = activo
    enabled        INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    -- Último intervalo de 30 s aceptado: impide reutilizar un código (repetición) dentro de su ventana.
    last_used_step INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS totp_recovery_codes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SHA-256 del código (80 bits de entropía: no es adivinable ni siquiera con la BD filtrada). Un solo uso.
    code_hash  TEXT    NOT NULL,
    used_at    TEXT,
    created_at TEXT    NOT NULL,
    UNIQUE (user_id, code_hash)
);
