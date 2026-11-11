-- Modello in uso nella sessione (da SessionStart e PostModelSwitch)
ALTER TABLE sessions ADD COLUMN model VARCHAR(100);

-- Le sessioni chiuse vengono archiviate in automatico: si allineano quelle già terminate
UPDATE sessions SET archived = TRUE WHERE status = 'ENDED';
