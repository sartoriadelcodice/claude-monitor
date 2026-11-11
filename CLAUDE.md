# Claude Monitor

Dashboard che mostra lo stato di tutte le sessioni Claude Code attive su più macchine ed evidenzia
quelle che richiedono attenzione. Usata soprattutto come PWA su iPad, con avviso sonoro e notifiche push.

## Architettura

1. **Hook di Claude Code** (`hooks/`): su ogni macchina uno script invia gli eventi al backend.
2. **Backend** (`backend/`): Java 21, Spring Boot 3.5, PostgreSQL, Flyway. Espone REST, SSE e Web Push.
3. **Frontend** (`frontend/`): Angular 22, PrimeNG 22, PWA con service worker di Angular.
4. **Deploy** (`docker-compose.yml`): postgres, backend, frontend servito da nginx. Il frontend è l'unico
   punto di ingresso e fa da proxy verso `/api`. Profilo `tunnel` con cloudflared per i test locali:
   il tunnel è gestito dalla dashboard Cloudflare e riceve solo `CLAUDE_MONITOR_TUNNEL_TOKEN`.

## Comandi

### Backend

```bash
cd backend
mvn test                     # tutti i test
mvn test -Dtest=SessionStateMapperTest
mvn spring-boot:run          # richiede PostgreSQL su localhost:5432 e le variabili del file .env
```

### Frontend

Angular 22 richiede Node 22.22.3, 24.15 o 26 e successivi. Sul Mac di sviluppo il Node predefinito è più
vecchio: anteporre `PATH=/opt/homebrew/opt/node@26/bin:$PATH` ai comandi `npm` e `npx`.

```bash
cd frontend
npm ci
npm start                    # http://localhost:4200, proxy di /api verso localhost:8080
npm run build                # build di produzione in dist/frontend/browser, con identificativo di build
```

Il service worker è attivo solo nella build di produzione: PWA, push e cache dei suoni non si provano con `ng serve`.

### Stack completo

```bash
cp .env.example .env         # poi imposta i valori
docker compose up -d --build                    # dashboard su http://localhost:8088
docker compose --profile tunnel up -d           # aggiunge il tunnel Cloudflare (protocollo http2)
```

## Modello di stato

La mappatura evento → stato sta tutta in `SessionStateMapper` (logica pura, coperta da test).
Stati: `IDLE`, `WORKING`, `NEEDS_ATTENTION`, `WAITING_INPUT`, `BACKGROUND`, `ENDED`, `STALE`.

- `Notification` porta a `NEEDS_ATTENTION` solo per `permission_prompt` ed `elicitation_dialog`.
- `PostToolUse` riporta a `WORKING` una sessione `NEEDS_ATTENTION`, `IDLE` o `STALE`; non tocca `WAITING_INPUT`
  perché gli hook sono asincroni e possono arrivare fuori ordine.
- `Stop` porta a `BACKGROUND` invece che a `WAITING_INPUT` se nel payload `background_tasks` o `session_crons`
  non sono vuoti: la sessione ripartirà da sola, quindi niente suono né push. Lo `Stop` successivo, senza
  lavoro in corso, la porta a `WAITING_INPUT`.
- `PostToolUse` riporta a `WORKING` anche una sessione `BACKGROUND`, ma solo se non arriva da un subagent
  (`agent_id` assente): i tool dei subagent in background non indicano un risveglio.
- `STALE` è assegnato da `StaleSessionJob` dopo `STALE_AFTER` senza eventi.
- Le sessioni `ENDED` vengono archiviate subito: escono dalla vista principale e restano nello storico.
  Un nuovo evento che cambia lo stato (ad esempio una ripresa) le riporta in vista.
- Nome del progetto e cartella della sessione si fissano alla creazione e a ogni `SessionStart` con `source`
  `startup` o `resume`: negli altri eventi `cwd` segue i `cd` di Claude e non va usato per rinominare la card.
- `PostModelSwitch` non cambia lo stato: aggiorna solo il modello mostrato sulla card.
- Il modello della card arriva da `SessionStart` (non sempre presente), da `PostModelSwitch` e, quando manca,
  dal transcript: lo script hook lo aggiunge come `model` su `Stop` e `PostToolUse`.

## Struttura del backend

Package base `it.sartoriadelcodice.claudemonitor`, organizzato per funzionalità:
`config`, `auth`, `user`, `hookkey`, `event`, `session`, `stream`, `push`, `focus`.

- Le modifiche alle sessioni pubblicano un `SessionChangedEvent`; `SessionChangeListener` lo inoltra
  dopo il commit a SSE (`SessionBroadcaster`) e Web Push (`PushNotifier`).
- Autenticazione sempre con header `Authorization: Bearer`: chiave hook personale per `POST /api/events`
  e per `/api/agent/**` (ruolo `HOOK`), JWT per tutto il resto (ruoli `USER` e `ADMIN`). `AuthFilter` rilegge l'utente dal
  database a ogni richiesta, così disattivazione e cambio ruolo hanno effetto subito.
- Il payload degli hook si salva senza i contenuti della conversazione (`HookEvent.withoutPrivateContent`);
  un campo nuovo con testo di prompt, risposte o comandi va aggiunto lì e negli script di `hooks/`.
- L'endpoint di una subscription push è un indirizzo a cui il backend invia richieste: passa sempre da
  `PushEndpointValidator` (solo https verso indirizzi pubblici).
- Le chiavi hook (`hookkey`) sono casuali con prefisso `cmk_`; nel database c'è solo l'hash SHA-256.
- Focus delle finestre (package `focus`): la dashboard chiama `POST /api/sessions/{id}/focus` e
  `FocusAgentRegistry` inoltra l'identificativo della sessione all'agente della macchina, collegato a
  `GET /api/agent/stream` con la chiave hook. Verso l'agente deve viaggiare solo l'identificativo: applicazione,
  terminale e cartella restano nel file locale scritto dall'hook. L'agente (`hooks/claude-monitor-agent.sh`,
  solo macOS, attivato a mano) non deve mai eseguire comandi ricevuti dalla rete né inviare tasti.
- Protezioni del focus, da non allentare: l'agente conta le richieste anomale e si blocca da solo
  (`agent.locked`, si toglie solo sul Mac con `unlock`, mai dalla rete); l'interruttore dell'utente
  (`users.focus_enabled`, `FocusService`) scollega gli agenti e fa rispondere 403 a richieste e stream.
- Multiutente: sessioni, cronologia, stream SSE e subscription push appartengono a un utente.
  Ogni nuova query o endpoint su questi dati deve filtrare per `userId` preso da `AuthUser`.
- `AdminBootstrap` crea l'amministratore iniziale da `ADMIN_USERNAME` e `ADMIN_PASSWORD` solo se non
  esistono utenti, e gli assegna i dati senza proprietario e l'eventuale `HOOK_TOKEN`.
- Lo schema del database cambia solo con nuove migrazioni Flyway in `src/main/resources/db/migration`;
  Hibernate è in modalità `validate`.

## Struttura del frontend

- `core/`: servizi (`SessionStore`, `AudioService`, `PushService`, `SettingsService`, `AuthService`, `AdminApi`).
- `features/`: `login`, `dashboard`, `session-detail`, `settings`, `hook-keys`, `users`, `account`.
- Componenti standalone, stato con signal, template con `@if` e `@for`.
- Lo stream SSE usa `fetch` con header `Authorization` e `ngsw-bypass`, non `EventSource`.
- I suoni partono solo sulle transizioni di stato rilevate in `SessionStore.trackTransition`.
- Il primo tocco su una card evidenziata la segna solo come vista (ferma evidenziazione e ripetizione del
  suono); il dettaglio si apre toccando una card già vista (`Dashboard.open`).
- Sulle card delle macchine con l'agente collegato (`SessionStore.focusMachines`) c'è il pulsante del focus,
  accanto alla card e non dentro; `Dashboard.focus` porta avanti la finestra e segna la card come vista.
- Tema chiaro e scuro: i colori passano tutti dalle variabili `--cm-*` di `styles.scss`, mai valori fissi nei
  componenti. La classe `app-dark` su `<html>` attiva il tema scuro anche per PrimeNG; la imposta
  `SettingsService` e, prima dell'avvio, lo script in `index.html` (stessa chiave `cm.settings`).
- Dopo la prima attivazione i suoni restano attivi: `AudioService` sblocca l'audio da solo al primo tocco
  di ogni apertura (limite del browser), senza richiederlo all'utente.
- `UpdateService` controlla le nuove versioni ogni 5 minuti e al ritorno in primo piano; quando il service
  worker ne ha scaricata una, la attiva e ricarica la pagina.
- Dopo la creazione di una chiave hook la finestra offre lo script di installazione: `HookInstaller` lo
  compone nel browser dai modelli `hooks/install.sh` e `hooks/install.ps1` e dagli script dell'hook.
  La build li pubblica sotto `/hooks` tramite il collegamento simbolico `frontend/hook-scripts`; per questo
  il contesto di build Docker del frontend è la radice del repository.
- Le icone e i suoni in `frontend/public` sono generati da `scripts/generate-assets.py`
  (`python3 scripts/generate-assets.py frontend/public`, richiede Pillow): non modificarli a mano.
- PrimeNG 22 richiede una chiave di licenza PrimeUI. Non sta nel repository: è la variabile `PRIMENG_LICENSE`
  del file `.env`, che `frontend/scripts/defines.mjs` inserisce nel bundle come `PRIMENG_LICENSE_KEY`
  (`npm start` e `npm run build`; in Docker arriva come argomento di build). Secondo i termini può stare
  nel bundle, ma non va pubblicata perché altri la usino. `npx ng serve` diretto parte senza chiave.

## iPadOS: vincoli da rispettare

La dashboard è usata soprattutto come PWA su iPad, dove Safari ha comportamenti che Chrome non mostra.
Le regole qui sotto nascono da problemi reali: non vanno "semplificate" senza provare su un iPad.

- **Audio**: lo sblocco avviene solo dentro un evento `click` (o `keydown`). `pointerdown` e `touchend`
  non valgono come gesto; `resume()` non va mai chiamato all'avvio o fuori da un gesto.
- **Audio**: non creare e chiudere contesti `AudioContext` a ripetizione. Può lasciare l'audio dell'iPad
  bloccato con `InvalidStateError: Failed to start the audio device` finché non si riavvia il dispositivo.
  Se compare quell'errore, prima di toccare il codice si riavvia l'iPad.
- **Audio**: se Web Audio viene rifiutato, `AudioService` ripiega su elementi `<audio>`, che su iPadOS
  ignorano il volume impostato. La riga "Stato audio" nelle impostazioni mostra quale via è attiva.
- **Tocchi**: il documento non scorre mai (`html` e `body` con `overflow: hidden`); scorre solo
  `app-dashboard`. Le finestre modali usano `appendTo="body"`. Se la pagina scorre sotto una finestra
  modale, su iPadOS i tocchi finiscono spostati di una riga.
- **Click ovunque**: sui dispositivi touch `body` ha `cursor: pointer`, altrimenti iPadOS non genera
  `click` sulle zone non interattive e il primo tocco non sblocca l'audio.
- **Prove**: dal Mac non si può ispezionare la PWA sull'iPad (serve la Modalità sviluppatore).
  Per diagnosticare, far inviare all'app una traccia temporanea al server e leggerla dai log di nginx.

## Ambiente attuale

- Lo stack gira in locale sul Mac con docker compose, esposto dal tunnel Cloudflare del profilo `tunnel`.
  Non c'è ancora un deploy su server né una pipeline CI.
- Repository: `github.com/sartoriadelcodice/claude-monitor`, pubblico: niente segreti, chiavi di licenza
  o dati di macchine e clienti nei file e nei messaggi di commit.
- Il token del tunnel si chiama `CLAUDE_MONITOR_TUNNEL_TOKEN` e non `TUNNEL_TOKEN`, perché una variabile
  con quel nome già presente nella shell avrebbe la precedenza sul file `.env`.
- Gli hook sono installati in `~/.claude/settings.json` delle macchine monitorate; l'esempio aggiornato
  è `hooks/settings.example.json`.

## Versioni

- La versione è quella di `frontend/package.json`, allineata a `backend/pom.xml`: va incrementata a ogni rilascio.
- `frontend/scripts/build.mjs` aggiunge l'identificativo di build (data e ora UTC). Entrambi compaiono
  nelle impostazioni della dashboard.
- Per pubblicare una modifica del frontend basta ricostruire l'immagine: i dispositivi si aggiornano da soli.

## Convenzioni

- Commenti e documentazione in italiano; codice, nomi e messaggi di commit in inglese.
- Ogni modifica alla mappatura degli stati va accompagnata dal test in `SessionStateMapperTest`.
- Nessun segreto nel repository: `.env` è escluso da git.
- Messaggi di commit: solo titolo e descrizione. Niente righe `Co-Authored-By`, niente link a sessioni di
  Claude Code e nessun riferimento a repository privati o ad altri servizi di hosting.
- Autore dei commit: `Sartoria del Codice <info@sartoriadelcodice.it>` (già impostato nella configurazione
  git locale del repository).
