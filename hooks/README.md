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

## Focus delle finestre (macOS, facoltativo)

Con l'agente attivo, sulle card delle sessioni di quel Mac compare un pulsante che porta in primo piano la
finestra di Claude Code. L'installazione dell'hook non lo avvia: va attivato a mano su ogni Mac. Il comando
pronto è nella finestra «Chiavi hook» della dashboard:

```bash
curl -fsS -o ~/.claude/hooks/claude-monitor-agent.sh 'https://monitor.example.com/hooks/claude-monitor-agent.sh' \
  && bash ~/.claude/hooks/claude-monitor-agent.sh install
```

Come funziona:

- Su macOS l'hook annota in `~/.claude/claude-monitor/windows/<session_id>` l'applicazione che ospita la
  sessione, il suo terminale e la cartella del progetto. Il file resta sulla macchina e non viene inviato;
  si scrive a `SessionStart` (o al primo evento se manca) e si elimina a `SessionEnd`.
- `claude-monitor-agent.sh install` registra un LaunchAgent (`it.sartoriadelcodice.claude-monitor-agent`)
  che resta collegato a `GET /api/agent/stream` con la chiave hook. URL, chiave e nome della macchina li
  legge dalla sezione `env` di `~/.claude/settings.json`: nel file di launchd non ci sono segreti.
- Dal backend arriva solo l'identificativo della sessione. L'agente lo controlla, legge il file locale e:
  - Terminale e iTerm: seleziona la scheda con quel terminale e porta avanti la finestra (AppleScript);
  - VS Code, VSCodium e Cursor: apre la cartella del progetto, che porta avanti la finestra che l'ha già aperta
    (se nessuna finestra ha quella cartella, ne apre una nuova);
  - altre applicazioni, o scheda non trovata (ad esempio dentro tmux): porta avanti l'applicazione.
- L'agente non esegue comandi ricevuti dalla rete e non invia tasti. Al primo utilizzo macOS chiede di
  consentire il controllo di Terminale: va accettato.
- Registro: `~/Library/Logs/claude-monitor-agent.log`. Prova locale, senza passare dal backend:
  `bash ~/.claude/hooks/claude-monitor-agent.sh focus <session_id>`.
- Rimozione: `bash ~/.claude/hooks/claude-monitor-agent.sh uninstall`.

### Sessioni in controllo remoto

Con il controllo remoto di Claude Code attivo (`/remote-control`, o `remoteControlAtStartup` nelle impostazioni)
una sessione che gira su un Mac si può seguire dall'app Claude di un altro. «Apri finestra» la apre sul Mac
che stai usando:

- L'hook legge l'identificativo del controllo remoto (`bridgeSessionId`, come `session_...`) dal file del
  processo in `~/.claude/sessions/<pid>.json` e lo invia a ogni evento come `remote_session_id`. Se il
  controllo remoto viene spento il campo manca e la dashboard lo dimentica. Vale anche su Linux e Windows.
- Al collegamento l'agente dichiara se sul Mac c'è l'app Claude (`com.anthropic.claudefordesktop` in
  `/Applications` o `~/Applications`).
- Al tocco su «Apri finestra» il backend chiede agli agenti coinvolti (il Mac della sessione e quelli con l'app
  Claude) da quanti secondi non si usano tastiera e mouse, aspetta le risposte per un secondo e mezzo e sceglie
  il Mac usato per ultimo. Se è quello della sessione porta avanti la finestra come sempre; altrimenti l'agente
  di quel Mac apre la sessione nell'app Claude con `claude://code/<identificativo>`. A parità vince il Mac
  della sessione. Il pulsante dice dove l'ha aperta.
- Se la macchina della sessione non ha l'agente (ad esempio Linux o Windows) il pulsante si chiama
  «Apri in Claude» e la sessione si apre nell'app Claude di un Mac con l'agente.
- L'agente comunica il tempo di inattività solo quando il backend lo chiede, cioè a ogni tocco su
  «Apri finestra», e non manda altro. Prove locali: `bash ~/.claude/hooks/claude-monitor-agent.sh idle` e
  `bash ~/.claude/hooks/claude-monitor-agent.sh remote <session_...>`.
- Dopo l'aggiornamento va reinstallato l'agente su ogni Mac (stesso comando dell'installazione): un agente
  di una versione precedente non risponde alle richieste e non apre l'app Claude.

### Protezioni

- **Solo https**: l'agente non si collega a un indirizzo `http`, tranne verso la macchina stessa.
- **Richieste anomale**: ognuna vale dei punti, sommati su dieci minuti. Un identificativo malformato
  (di sessione, di controllo remoto o di richiesta di inattività) vale 10 punti; una sessione che il Mac non
  conosce, una sessione remota su un Mac senza l'app Claude, un focus a meno di due secondi dal precedente o
  una richiesta di inattività nello stesso secondo della precedente valgono 1 punto. A 10 punti, oltre 60
  focus eseguiti in un'ora o oltre 120 richieste di inattività in un'ora, l'agente si blocca.
- **Link composti in locale**: per il controllo remoto dal backend arriva solo l'identificativo
  (`session_` o `cse_` seguito da lettere, cifre, `_` e `-`); il link `claude://code/...` lo compone l'agente
  e lo apre solo con l'app Claude.
- **Blocco**: l'agente chiude il collegamento, mostra una notifica, scrive il motivo in
  `~/.claude/claude-monitor/agent.locked` e non si ricollega. Dalla dashboard il pulsante del focus sparisce.
  Il blocco non si toglie dalla rete: dopo aver letto il registro si lancia sul Mac
  `bash ~/.claude/hooks/claude-monitor-agent.sh unlock`. Lo stato si vede con `status`.
- **Interruttore nella dashboard**: in Impostazioni, «Focus delle finestre» spegne la funzione per tutte le
  macchine e le sessioni dell'utente. Il backend scollega gli agenti e ne rifiuta i nuovi collegamenti;
  l'agente riprova ogni minuto e riparte da solo quando l'interruttore torna acceso.

Le soglie sono in testa allo script (`suspect_limit`, `suspect_window`, `hourly_limit`,
`probe_hourly_limit`, `min_interval`).

Le sessioni già aperte diventano raggiungibili dal primo evento successivo all'aggiornamento dell'hook.
Su Linux e Windows l'agente non c'è: le loro sessioni si aprono solo nell'app Claude di un Mac, se hanno il
controllo remoto attivo.

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
  notifiche di Claude Code, modello, identificativo del controllo remoto se attivo. Con `jq` installato (e sempre su Windows) lo script toglie prima
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
