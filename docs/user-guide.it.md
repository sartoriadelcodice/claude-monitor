# Guida all'uso

[🇬🇧 English](user-guide.md) · [README](../README.it.md)

- [La schermata principale](#la-schermata-principale)
- [Le card](#le-card)
- [Il dettaglio di una sessione](#il-dettaglio-di-una-sessione)
- [Collegare una macchina](#collegare-una-macchina)
- [Suoni](#suoni)
- [Notifiche push](#notifiche-push)
- [Focus delle finestre (macOS)](#focus-delle-finestre-macos)
- [Impostazioni](#impostazioni)
- [Utenti](#utenti)

## La schermata principale

In alto a sinistra c'è il contatore delle sessioni che ti aspettano («Richiede attenzione» e «Tocca a te»):
diventa rosso quando ce n'è almeno una. A destra ci sono, nell'ordine:

| Comando                | A cosa serve                                                                      |
| ---------------------- | --------------------------------------------------------------------------------- |
| «In tempo reale»       | Stato del collegamento con il server; «Riconnessione…» quando è caduto            |
| Elenco delle macchine  | Mostra solo le sessioni di una macchina (compare se le macchine sono più di una)  |
| Altoparlante           | Muta o riattiva i suoni su questo dispositivo                                     |
| Sole o luna            | Passa dal tema scuro a quello chiaro e viceversa                                  |
| Chiave                 | Chiavi hook: collega una nuova macchina o revoca una chiave                       |
| Persone                | Gestione degli utenti (solo per gli amministratori)                               |
| Ingranaggio            | Impostazioni                                                                      |

Sotto, «Raggruppa per» divide le card per cartella, per device o per entrambi. I gruppi con sessioni che ti
aspettano vengono per primi.

## Le card

Ogni card è una sessione di Claude Code e mostra lo stato, la cartella da cui è stata avviata, il nome della
sessione (se ne ha uno), la macchina, il modello in uso e da quanto tempo è in quello stato. Quando Claude
chiede un permesso o fa una domanda, sulla card compare anche il testo della richiesta.

Le card sono ordinate per urgenza: prima «Richiede attenzione», poi «Tocca a te», poi le altre. Il significato
degli stati è nel [README](../README.it.md#stati-di-una-sessione).

Una card che ti aspetta e che non hai ancora visto è evidenziata con il colore del suo stato.

- **Primo tocco su una card evidenziata**: la segna come vista. Si spegne l'evidenziazione e, se è attiva,
  la ripetizione del suono. Il dettaglio non si apre.
- **Tocco su una card non evidenziata**: apre il dettaglio.

Se la sessione cambia di nuovo stato, la card torna evidenziata.

## Il dettaglio di una sessione

Mostra lo stato, il percorso completo della cartella, l'ultimo messaggio di Claude Code e la cronologia degli
eventi ricevuti, dal più recente, con lo stato in cui hanno portato la sessione.

«Archivia» toglie la sessione dalla vista principale; resta nello storico, da cui si può riportare con
«Ripristina». Le sessioni chiuse vengono archiviate da sole. Una sessione archiviata che riprende a lavorare
torna nella vista principale.

## Collegare una macchina

1. Tocca il pulsante con la chiave e crea una chiave hook, dandole il nome della macchina.
2. La chiave compare in chiaro una volta sola: copiala subito oppure usa l'installazione automatica.
3. **Installazione automatica**: scegli il sistema (macOS e Linux oppure Windows), scarica lo script ed
   eseguilo sulla macchina da collegare. Installa l'hook e aggiorna le impostazioni globali di Claude Code,
   dopo averne salvato una copia di sicurezza. Poi elimina lo script, perché contiene la chiave.
4. Apri una nuova sessione di Claude Code su quella macchina: la card compare nella dashboard.

Quando Claude Monitor viene aggiornato, lo script dell'hook può cambiare. In fondo alla finestra «Chiavi hook»
c'è il comando per riscaricarlo su una macchina già collegata, senza creare una chiave nuova.

Conviene una chiave per macchina: se serve se ne revoca una sola. L'elenco mostra per ogni chiave la data di
creazione e l'ultimo uso. L'installazione manuale è descritta (in inglese) in [hooks/README.md](../hooks/README.md).

## Suoni

Al primo accesso tocca «Attiva suoni». I suoni sono due: uno più insistente per «Richiede attenzione» e uno
più discreto per «Tocca a te». Partono solo quando una sessione entra in quello stato.

Nelle impostazioni puoi regolare il volume, provare i due suoni e attivare la ripetizione del suono di
«Richiede attenzione»: si ripete all'intervallo scelto finché la card non viene vista.

Su iPad il browser riattiva l'audio solo dopo un tocco: a ogni apertura dell'app basta toccare un punto
qualsiasi dello schermo. La riga «Stato audio» nelle impostazioni dice se l'audio è attivo.

## Notifiche push

Tocca «Attiva notifiche» e concedi il permesso: ricevi una notifica quando una sessione entra in «Richiede
attenzione» o «Tocca a te», anche con l'app chiusa. Toccando la notifica si apre la dashboard.

Le notifiche si attivano per dispositivo: dalle impostazioni puoi spegnerle su un dispositivo e lasciarle
attive sugli altri. Sono scritte nella lingua scelta su ciascun dispositivo. Su iPad e iPhone funzionano solo
dall'app aggiunta alla schermata Home.

## Focus delle finestre (macOS)

Facoltativo. Nella finestra «Chiavi hook», la sezione «Focus delle finestre» ha un comando che avvia un piccolo
agente su un Mac. Le card delle sessioni di quel Mac ricevono la barra «Apri finestra», che porta in primo piano la
finestra del terminale di Claude Code.

Con il controllo remoto di Claude Code la sessione si apre sul Mac che stai usando: la sua finestra se sei a quel
Mac, altrimenti l'app Claude del Mac in uso («Apri in Claude»). Dopo il tocco la barra dice dove si è aperta.

L'agente riceve solo l'identificativo della sessione: non esegue comandi e non invia tasti. Un interruttore nelle
impostazioni spegne il focus su tutte le tue macchine insieme. Dettagli e protezioni sono (in inglese) in
[hooks/README.md](../hooks/README.md#window-focus-macos-optional).

## Impostazioni

| Sezione              | Cosa contiene                                                                            |
| -------------------- | ---------------------------------------------------------------------------------------- |
| Lingua               | Lingua dell'interfaccia: automatica (dal browser), English o Italiano                    |
| Suoni                | Attivazione, volume, prova dei suoni, stato dell'audio, ripetizione e suo intervallo     |
| Notifiche push       | Attivazione su questo dispositivo                                                        |
| Vista                | Storico delle sessioni terminate e archiviate; ingrandimento temporaneo della card che passa a uno stato che ti aspetta, e sua durata |
| Focus delle finestre | Consente alla dashboard di portare in primo piano le finestre di Claude Code, su tutte le tue macchine |
| Versione             | Versione e build installate, controllo degli aggiornamenti                               |
| Account              | Cambio della password e uscita                                                           |

Tema, lingua, suoni, raggruppamento e le altre preferenze sono salvate sul dispositivo: ogni dispositivo ha le sue.

L'app si aggiorna da sola: controlla ogni cinque minuti e quando torna in primo piano, e se trova una nuova
versione la installa e si ricarica.

## Utenti

Solo gli amministratori vedono il pulsante degli utenti. Da lì si creano nuovi utenti (password di almeno
10 caratteri), si reimposta una password, si cambia il ruolo tra utente e amministratore e si disattiva o
riattiva un utente. Ogni utente crea poi le proprie chiavi hook e vede soltanto le proprie sessioni.
