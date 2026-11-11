-- Identificativo della sessione in controllo remoto (Remote Control di Claude Code), come session_...:
-- permette di aprirla nell'app Claude di un'altra macchina. Lo aggiorna ogni evento; NULL se non è attivo.
ALTER TABLE sessions ADD COLUMN remote_session_id VARCHAR(100);
