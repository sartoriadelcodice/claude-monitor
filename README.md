# Claude Monitor

Dashboard per vedere a colpo d'occhio lo stato di tutte le sessioni [Claude Code](https://claude.com/claude-code)
attive su più macchine. Evidenzia quelle ferme in attesa (permesso richiesto, input richiesto, risposta
terminata), suona un avviso e manda una notifica push. Pensata per essere installata come PWA su iPad.

Ogni macchina invia gli eventi delle proprie sessioni tramite gli hook di Claude Code; la dashboard li
riceve, ne ricava lo stato e lo mostra in tempo reale. Si ospita in proprio con docker compose.

## Funzioni

- Card per ogni sessione con stato, cartella, nome della sessione, macchina, modello e tempo nello stato.
- Contatore delle sessioni in attesa, sempre mostrate per prime; raggruppamento per cartella, device o entrambi.
- Avviso sonoro (diverso per "richiede attenzione" e "tocca a te") e notifiche push.
- Le sessioni che hanno finito di rispondere ma hanno ancora task in background sono distinte da quelle
  che aspettano te: nessun avviso finché non tocca davvero a te.
- Sessioni chiuse archiviate in automatico e consultabili nello storico.
- Più utenti, ognuno con le proprie sessioni e le proprie chiavi hook.
- PWA installabile che si aggiorna da sola quando viene pubblicata una nuova versione.
- Tema scuro o chiaro, a scelta per ogni dispositivo.

## Stati di una sessione

| Stato               | Sulla card            | Significato                                                              | Avviso        |
| ------------------- | --------------------- | ------------------------------------------------------------------------ | ------------- |
| `NEEDS_ATTENTION`   | Richiede attenzione   | Claude aspetta un permesso o una risposta a una domanda                  | Suono e push  |
| `WAITING_INPUT`     | Tocca a te            | Risposta terminata, nessun lavoro in corso                               | Suono e push  |
| `WORKING`           | Al lavoro             | Claude sta lavorando                                                     | No            |
| `BACKGROUND`        | In background         | Risposta terminata, ma con task in background o risvegli pianificati: ripartirà da sola | No |
| `IDLE`              | In attesa del prompt  | Sessione aperta, nessun prompt ancora inviato                            | No            |
| `STALE`             | Ferma                 | Nessun evento da più di `STALE_AFTER` (30 minuti se non impostato)       | No            |
| `ENDED`             | Terminata             | Sessione chiusa: esce dalla vista principale e resta nello storico       | No            |

La corrispondenza tra eventi degli hook e stati è descritta in [hooks/README.md](hooks/README.md).

## Architettura

- **Hook** (`hooks/`): uno script per macchina, bash oppure PowerShell, che inoltra gli eventi di Claude Code.
  Con `jq` (o su Windows) toglie prima dell'invio i contenuti della conversazione: prompt, risposte di Claude,
  input e output dei tool, comandi dei task in background. Il backend li scarta comunque prima di salvare.
- **Backend** (`backend/`): Java 21, Spring Boot, PostgreSQL. Espone REST, aggiornamenti in tempo reale (SSE)
  e Web Push.
- **Frontend** (`frontend/`): Angular e PrimeNG, PWA. Il suo nginx è l'unico punto di ingresso e fa da proxy
  verso il backend.

## Requisiti

- Docker con docker compose.
- Un indirizzo HTTPS per usare PWA e notifiche push (vedi [HTTPS](#https)).
- Sulle macchine da monitorare: Claude Code e `curl`; `jq` è consigliato.
- Una chiave di licenza PrimeUI, gratuita con la Community License (vedi sotto).

## Avvio

1. Crea il file di configurazione:

   ```bash
   cp .env.example .env
   ```

   Imposta `DB_PASSWORD`, `ADMIN_PASSWORD` e `JWT_SECRET`
   (per i valori casuali: `openssl rand -hex 32`).
   `ADMIN_USERNAME` e `ADMIN_PASSWORD` servono solo al primo avvio, per creare l'amministratore iniziale.

   PrimeNG 22 richiede una chiave di licenza PrimeUI: si ottiene da <https://primeui.dev/licenses>
   (la Community License è gratuita) e va in `PRIMENG_LICENSE`. Viene inserita nel bundle del frontend
   durante la build; senza chiave la dashboard funziona ma mostra un avviso in basso a destra.

2. Genera le chiavi VAPID per le notifiche push e copiale in `.env`:

   ```bash
   scripts/generate-vapid-keys.sh
   ```

3. Avvia lo stack:

   ```bash
   docker compose up -d --build
   ```

   La dashboard risponde su `http://localhost:8088` (porta configurabile con `FRONTEND_PORT`).

4. Accedi, crea una chiave hook (pulsante con la chiave, in alto) e installa gli hook su ogni macchina
   da monitorare: vedi [hooks/README.md](hooks/README.md).

## Utenti e chiavi hook

- Ogni utente vede solo le proprie sessioni e riceve solo le proprie notifiche.
- Una sessione appartiene all'utente che ha creato la chiave hook usata dalla macchina.
- Le chiavi hook si creano e si revocano dalla dashboard. Nel database resta solo il loro hash SHA-256:
  la chiave in chiaro si vede una volta sola, alla creazione.
- Gli utenti li crea un amministratore dalla dashboard; non c'è registrazione libera.
  Un utente disattivato perde subito l'accesso e le sue chiavi smettono di funzionare.

## HTTPS

PWA, service worker e notifiche push richiedono HTTPS.

- **Produzione**: metti il servizio `frontend` dietro il tuo reverse proxy HTTPS.
  Il proxy deve lasciar passare lo stream SSE di `/api/stream` senza buffering e con timeout lungo.
- **Test locali**: il profilo `tunnel` avvia cloudflared con protocollo `http2`.
  Il tunnel è gestito dalla dashboard Cloudflare (Zero Trust → Networks → Tunnels):
  lì si aggiunge l'hostname pubblico con servizio `http://frontend:80`.
  Copia il token del tunnel in `CLAUDE_MONITOR_TUNNEL_TOKEN` nel file `.env`, poi:

  ```bash
  docker compose --profile tunnel up -d
  ```

## Installazione su iPad

1. Apri la dashboard in Safari tramite l'indirizzo HTTPS.
2. Condividi → «Aggiungi alla schermata Home».
3. Apri l'app dalla Home ed esegui l'accesso.
4. Tocca «Attiva suoni» e «Attiva notifiche».

Le notifiche push su iPadOS funzionano solo dalla PWA installata (iPadOS 16.4 o successivo).

## Manuale d'uso

### La schermata principale

In alto a sinistra c'è il contatore delle sessioni che ti aspettano («Richiede attenzione» e «Tocca a te»):
diventa rosso quando ce n'è almeno una. A destra ci sono, nell'ordine:

| Comando                      | A cosa serve                                                                 |
| ---------------------------- | ---------------------------------------------------------------------------- |
| «In tempo reale»             | Stato del collegamento con il server; «Riconnessione…» quando è caduto       |
| Elenco delle macchine        | Mostra solo le sessioni di una macchina (compare se le macchine sono più di una) |
| Altoparlante                 | Muta o riattiva i suoni su questo dispositivo                                |
| Sole o luna                  | Passa dal tema scuro a quello chiaro e viceversa                             |
| Chiave                       | Chiavi hook: collega una nuova macchina o revoca una chiave                  |
| Persone                      | Gestione degli utenti (solo per gli amministratori)                          |
| Ingranaggio                  | Impostazioni                                                                 |

Sotto, «Raggruppa per» divide le card per cartella, per device o per entrambi. I gruppi con sessioni che ti
aspettano vengono per primi.

### Le card

Ogni card è una sessione di Claude Code e mostra lo stato, la cartella da cui è stata avviata, il nome della
sessione (se ne ha uno), la macchina, il modello in uso e da quanto tempo è in quello stato. Quando Claude
chiede un permesso o fa una domanda, sulla card compare anche il testo della richiesta.

Le card sono ordinate per urgenza: prima «Richiede attenzione», poi «Tocca a te», poi le altre. Il significato
degli stati è nella tabella [Stati di una sessione](#stati-di-una-sessione).

Una card che ti aspetta e che non hai ancora visto è evidenziata con il colore del suo stato.

- **Primo tocco su una card evidenziata**: la segna come vista. Si spegne l'evidenziazione e, se è attiva,
  la ripetizione del suono. Il dettaglio non si apre.
- **Tocco su una card non evidenziata**: apre il dettaglio.

Se la sessione cambia di nuovo stato, la card torna evidenziata.

### Il dettaglio di una sessione

Mostra lo stato, il percorso completo della cartella, l'ultimo messaggio di Claude Code e la cronologia degli
eventi ricevuti, dal più recente, con lo stato in cui hanno portato la sessione.

«Archivia» toglie la sessione dalla vista principale; resta nello storico, da cui si può riportare con
«Ripristina». Le sessioni chiuse vengono archiviate da sole. Una sessione archiviata che riprende a lavorare
torna nella vista principale.

### Collegare una macchina

1. Tocca il pulsante con la chiave e crea una chiave hook, dandole il nome della macchina.
2. La chiave compare in chiaro una volta sola: copiala subito oppure usa l'installazione automatica.
3. **Installazione automatica**: scegli il sistema (macOS e Linux oppure Windows), scarica lo script ed
   eseguilo sulla macchina da collegare. Installa l'hook e aggiorna le impostazioni globali di Claude Code,
   dopo averne salvato una copia di sicurezza. Poi elimina lo script, perché contiene la chiave.
4. Apri una nuova sessione di Claude Code su quella macchina: la card compare nella dashboard.

Quando Claude Monitor viene aggiornato, lo script dell'hook può cambiare. In fondo alla finestra «Chiavi hook»
c'è il comando per riscaricarlo su una macchina già collegata, senza creare una chiave nuova.

Conviene una chiave per macchina: se serve se ne revoca una sola. L'elenco mostra per ogni chiave la data di
creazione e l'ultimo uso. L'installazione manuale è descritta in [hooks/README.md](hooks/README.md).

### Suoni

Al primo accesso tocca «Attiva suoni». I suoni sono due: uno più insistente per «Richiede attenzione» e uno
più discreto per «Tocca a te». Partono solo quando una sessione entra in quello stato.

Nelle impostazioni puoi regolare il volume, provare i due suoni e attivare la ripetizione del suono di
«Richiede attenzione»: si ripete all'intervallo scelto finché la card non viene vista.

Su iPad il browser riattiva l'audio solo dopo un tocco: a ogni apertura dell'app basta toccare un punto
qualsiasi dello schermo. La riga «Stato audio» nelle impostazioni dice se l'audio è attivo.

### Notifiche push

Tocca «Attiva notifiche» e concedi il permesso: ricevi una notifica quando una sessione entra in «Richiede
attenzione» o «Tocca a te», anche con l'app chiusa. Toccando la notifica si apre la dashboard.

Le notifiche si attivano per dispositivo: dalle impostazioni puoi spegnerle su un dispositivo e lasciarle
attive sugli altri. Su iPad e iPhone funzionano solo dall'app aggiunta alla schermata Home.

### Impostazioni

| Sezione         | Cosa contiene                                                                              |
| --------------- | ------------------------------------------------------------------------------------------ |
| Suoni           | Attivazione, volume, prova dei suoni, stato dell'audio, ripetizione e suo intervallo        |
| Notifiche push  | Attivazione su questo dispositivo                                                          |
| Vista           | Storico delle sessioni terminate e archiviate; ingrandimento temporaneo della card che passa a uno stato che ti aspetta, e sua durata |
| Versione        | Versione e build installate, controllo degli aggiornamenti                                 |
| Account         | Cambio della password e uscita                                                             |

Tema, suoni, raggruppamento e le altre preferenze sono salvate sul dispositivo: ogni dispositivo ha le sue.

L'app si aggiorna da sola: controlla ogni cinque minuti e quando torna in primo piano, e se trova una nuova
versione la installa e si ricarica.

### Utenti

Solo gli amministratori vedono il pulsante degli utenti. Da lì si creano nuovi utenti (password di almeno
10 caratteri), si reimposta una password, si cambia il ruolo tra utente e amministratore e si disattiva o
riattiva un utente. Ogni utente crea poi le proprie chiavi hook e vede soltanto le proprie sessioni.

## Sicurezza

- Il servizio va esposto solo in HTTPS, dietro un reverse proxy o un tunnel: la porta del frontend parla HTTP.
- Le password sono salvate con bcrypt, le chiavi hook solo come hash SHA-256. Una chiave hook può soltanto
  inviare eventi: non legge nulla.
- I tentativi di login sono limitati da nginx (10 al minuto, poi risposta 429). Dietro un proxy il limite
  vale per tutti i client insieme.
- Il login resta valido per `JWT_TTL` (30 giorni se non impostato). Disattivare un utente ha effetto subito;
  il cambio password non chiude le sessioni già aperte sugli altri dispositivi.
- La cronologia degli eventi non ha una scadenza: cresce finché le sessioni restano nel database.

Per segnalare un problema di sicurezza scrivi a <info@sartoriadelcodice.it> invece di aprire una issue pubblica.

## API

| Metodo e percorso                    | Descrizione                                             | Autenticazione |
| ------------------------------------ | ------------------------------------------------------- | -------------- |
| `POST /api/events`                   | Riceve gli eventi degli hook                            | Chiave hook    |
| `POST /api/auth/login`               | Login, restituisce il JWT                               | Nessuna        |
| `GET /api/sessions`                  | Elenco sessioni (`status`, `machine`, `includeHistory`) | JWT            |
| `GET /api/sessions/{id}/events`      | Cronologia eventi della sessione                        | JWT            |
| `PATCH /api/sessions/{id}`           | Segna come vista o archivia (`seen`, `archived`)        | JWT            |
| `GET /api/stream`                    | Aggiornamenti in tempo reale (SSE)                      | JWT            |
| `GET /api/push/public-key`           | Chiave pubblica VAPID                                   | JWT            |
| `POST` e `DELETE /api/push/subscriptions` | Registra o rimuove un dispositivo                  | JWT            |
| `GET` e `POST /api/hook-keys`        | Elenca o crea le proprie chiavi hook                    | JWT            |
| `DELETE /api/hook-keys/{id}`         | Revoca una propria chiave hook                          | JWT            |
| `GET /api/me`, `POST /api/me/password` | Dati dell'utente collegato, cambio password           | JWT            |
| `GET` e `POST /api/users`, `PATCH /api/users/{id}` | Gestione utenti                           | JWT (admin)    |

## Sviluppo

```bash
cd backend && mvn test               # test del backend
cd backend && mvn spring-boot:run    # richiede PostgreSQL su localhost:5432 e le variabili del file .env

cd frontend && npm ci
npm start                            # http://localhost:4200, proxy di /api verso localhost:8080
npm run build                        # build di produzione
```

Il frontend richiede Node 22.22.3, 24.15 oppure 26 e successivi. `npm start` e `npm run build` leggono
`PRIMENG_LICENSE` dall'ambiente o dal file `.env` nella radice.

Struttura del codice, convenzioni e vincoli da rispettare su iPadOS sono in [CLAUDE.md](CLAUDE.md).

## Licenza

Il codice è distribuito con licenza [MIT](LICENSE).
PrimeNG ha una licenza propria: ogni installazione usa la propria chiave PrimeUI.
