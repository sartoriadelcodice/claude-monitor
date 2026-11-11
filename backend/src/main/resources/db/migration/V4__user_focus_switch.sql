-- Focus delle finestre: interruttore dell'utente, vale per tutte le sue macchine e sessioni.
-- Spento, il backend rifiuta le richieste di focus e non accetta gli agenti delle sue macchine.
ALTER TABLE users ADD COLUMN focus_enabled BOOLEAN NOT NULL DEFAULT TRUE;
