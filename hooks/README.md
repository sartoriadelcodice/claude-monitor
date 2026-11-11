# Hook di Claude Code per Claude Monitor

Lo script invia al backend gli eventi di ogni sessione Claude Code della macchina.
Va installato su ogni macchina da monitorare.

## Requisiti

- macOS o Linux: `bash` e `curl`. `jq` è consigliato; senza `jq` lo script funziona lo stesso
  ma invia il payload completo, compresi prompt e input dei tool.
- Windows: PowerShell 5.1 o successivo.

## Installazione automatica

Dalla dashboard, subito dopo aver creato una chiave hook, si scarica uno script che fa tutto da solo.
La piattaforma è proposta in base al browser e si può cambiare.

- macOS e Linux: `bash claude-monitor-install.sh` (richiede `python3`).
- Windows: `powershell -ExecutionPolicy Bypass -File .\claude-monitor-install.ps1`.

Lo script copia l'hook in `~/.claude/hooks/`, salva una copia di `~/.claude/settings.json` come
`settings.json.backup-data-ora` e poi aggiunge variabili e hook lasciando intatto il resto.
Si può rieseguire: le voci di Claude Monitor già presenti vengono sostituite. Se `settings.json` non è
un JSON valido lo script si ferma senza modificarlo. Contiene la chiave in chiaro: dopo l'uso va eliminato.

I modelli sono `install.sh` e `install.ps1` in questa cartella: la dashboard vi inserisce URL, chiave e
script dell'hook. Non vanno eseguiti così come sono.

## Installazione manuale su macOS e Linux

1. Copia lo script e rendilo eseguibile:

   ```bash
   mkdir -p ~/.claude/hooks
   cp hooks/claude-monitor-hook.sh ~/.claude/hooks/
   chmod +x ~/.claude/hooks/claude-monitor-hook.sh
   ```

2. Apri `~/.claude/settings.json` e unisci il contenuto di `settings.example.json`:
   la sezione `env` con URL e token, e la sezione `hooks`.
   Il nome della sessione compare nella dashboard solo se ne hai dato uno con `--name` o `/rename`:
   Claude Code non passa agli hook i nomi generati in automatico.
   Se hai già altri hook sugli stessi eventi, aggiungi il blocco all'array esistente invece di sostituirlo.

3. Imposta i valori:
   - `CLAUDE_MONITOR_URL`: URL pubblico della dashboard, senza `/api` finale.
   - `CLAUDE_MONITOR_TOKEN`: una chiave hook creata dalla dashboard (pulsante con la chiave, in alto).
     La chiave si vede solo al momento della creazione. Conviene crearne una per macchina:
     le sessioni compaiono all'utente che ha creato la chiave, e ogni chiave si revoca da sola.
   - `CLAUDE_MONITOR_MACHINE` (facoltativa): nome da mostrare al posto dell'hostname.

4. Apri una nuova sessione Claude Code: la card compare nella dashboard.

## Installazione manuale su Windows

1. Copia `claude-monitor-hook.ps1` in `%USERPROFILE%\.claude\hooks\`.
2. In `%USERPROFILE%\.claude\settings.json` usa la stessa struttura di `settings.example.json`,
   sostituendo ogni comando con:

   ```
   powershell -NoProfile -ExecutionPolicy Bypass -File "%USERPROFILE%\.claude\hooks\claude-monitor-hook.ps1"
   ```

## Aggiornamento

La dashboard pubblica gli script aggiornati sotto `/hooks`. Per aggiornare una macchina già collegata basta
riscaricare lo script; chiave e `settings.json` non cambiano. Il comando pronto, con l'indirizzo già compilato,
è in fondo alla finestra «Chiavi hook» della dashboard. Su Linux e macOS:

```bash
curl -fsS -o ~/.claude/hooks/claude-monitor-hook.sh 'https://monitor.example.com/hooks/claude-monitor-hook.sh' \
  && chmod +x ~/.claude/hooks/claude-monitor-hook.sh
```

## Prova manuale

```bash
export CLAUDE_MONITOR_URL=http://localhost:8088
export CLAUDE_MONITOR_TOKEN=cmk_la-tua-chiave-hook
echo '{"session_id":"prova-1","hook_event_name":"Notification","cwd":"/tmp/prova","notification_type":"permission_prompt","message":"Claude needs your permission"}' \
  | hooks/claude-monitor-hook.sh
```

## Eventi e stati

| Evento             | Stato risultante                                                        |
| ------------------ | ----------------------------------------------------------------------- |
| `SessionStart`     | `IDLE` (non cambia se `source` è `compact`)                             |
| `UserPromptSubmit` | `WORKING`                                                               |
| `Notification`     | `NEEDS_ATTENTION` per `permission_prompt` ed `elicitation_dialog`; `WAITING_INPUT` per `idle_prompt` se la sessione era `WORKING`; gli altri tipi restano solo in cronologia |
| `PostToolUse`      | `WORKING` se la sessione era `NEEDS_ATTENTION`, `IDLE` o `STALE`, oppure `BACKGROUND` e il tool non è di un subagent |
| `Stop`             | `BACKGROUND` se `background_tasks` o `session_crons` non sono vuoti, altrimenti `WAITING_INPUT` |
| `PostModelSwitch`  | Nessun cambio di stato: aggiorna il modello mostrato sulla card         |
| `SessionEnd`       | `ENDED`, e la sessione viene archiviata                                 |

## Comportamento

- Dati inviati: identificativo e nome della sessione, cartella, macchina, tipo di evento, testo delle
  notifiche di Claude Code, modello. Con `jq` installato (e sempre su Windows) lo script toglie prima
  dell'invio prompt, risposte di Claude, input e output dei tool, comandi dei task in background e prompt
  dei risvegli pianificati. Senza `jq` il payload parte intero ed è il backend a scartare quei campi prima
  di salvarlo: per questo `jq` è consigliato.

- Il modello arriva con `SessionStart` solo in alcuni casi (mai dopo `/clear`). Quando manca, su `Stop` e
  `PostToolUse` lo script lo legge dall'ultima risposta nel transcript e lo aggiunge al payload come `model`.

- Gli hook sono dichiarati `async`: girano in background e non rallentano Claude Code.
  `SessionEnd` è sincrono con timeout di 5 secondi, perché alla chiusura un processo in background potrebbe non finire.
- Lo script ha un timeout di rete di 3 secondi ed esce sempre con codice 0:
  se il backend non risponde, Claude Code non mostra errori.
- Senza `CLAUDE_MONITOR_URL` o `CLAUDE_MONITOR_TOKEN` lo script non fa nulla.
- Con una chiave revocata o sconosciuta il backend risponde 401 e lo script lo ignora:
  la macchina smette semplicemente di comparire nella dashboard.
