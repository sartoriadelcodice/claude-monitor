-- Sessioni Claude Code: una riga per session_id ricevuto dagli hook
CREATE TABLE sessions (
    id                        VARCHAR(100) PRIMARY KEY,
    machine                   VARCHAR(255) NOT NULL,
    project                   VARCHAR(255) NOT NULL,
    cwd                       TEXT,
    title                     VARCHAR(500),
    status                    VARCHAR(30)  NOT NULL,
    status_since              TIMESTAMPTZ  NOT NULL,
    last_event_at             TIMESTAMPTZ  NOT NULL,
    last_notification_message TEXT,
    last_notification_type    VARCHAR(100),
    seen_at                   TIMESTAMPTZ,
    archived                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_sessions_status ON sessions (status);
CREATE INDEX idx_sessions_machine ON sessions (machine);
CREATE INDEX idx_sessions_last_event_at ON sessions (last_event_at);

-- Cronologia degli eventi ricevuti per ogni sessione
CREATE TABLE session_events (
    id                BIGSERIAL PRIMARY KEY,
    session_id        VARCHAR(100) NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    event_name        VARCHAR(100) NOT NULL,
    notification_type VARCHAR(100),
    message           TEXT,
    status_after      VARCHAR(30)  NOT NULL,
    payload           TEXT,
    received_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_session_events_session ON session_events (session_id, received_at);

-- Subscription Web Push dei dispositivi (iPad)
CREATE TABLE push_subscriptions (
    id         BIGSERIAL PRIMARY KEY,
    endpoint   TEXT         NOT NULL UNIQUE,
    p256dh     VARCHAR(255) NOT NULL,
    auth       VARCHAR(255) NOT NULL,
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL
);
