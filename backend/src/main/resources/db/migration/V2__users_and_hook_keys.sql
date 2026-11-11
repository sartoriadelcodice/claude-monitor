-- Utenti della dashboard
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Chiavi hook create dagli utenti: si salva solo l'hash SHA-256, mai la chiave in chiaro
CREATE TABLE hook_keys (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    key_hash     CHAR(64)     NOT NULL UNIQUE,
    key_prefix   VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_hook_keys_user ON hook_keys (user_id);

-- Proprietario di sessioni e subscription push.
-- Le righe già esistenti restano senza proprietario finché l'applicazione, all'avvio,
-- non le assegna all'amministratore iniziale.
ALTER TABLE sessions ADD COLUMN user_id BIGINT REFERENCES users (id) ON DELETE CASCADE;
CREATE INDEX idx_sessions_user ON sessions (user_id);

ALTER TABLE push_subscriptions ADD COLUMN user_id BIGINT REFERENCES users (id) ON DELETE CASCADE;
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);
