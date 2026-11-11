#!/bin/bash
# Agente di Claude Monitor per macOS.
# Resta collegato al backend e, quando dalla dashboard si chiede il focus di una sessione, porta in primo
# piano la finestra che la ospita. La finestra è quella annotata da claude-monitor-hook.sh in
# ~/.claude/claude-monitor/windows/<session_id>: dal backend arriva solo l'identificativo della sessione,
# quindi il server non può far aprire nient'altro.
#
#
# Controllo remoto (Remote Control di Claude Code): se la sessione di un'altra macchina è seguibile in remoto,
# il backend chiede a tutti gli agenti da quanti secondi non si usano tastiera e mouse ("probe") e sceglie il
# Mac usato per ultimo. Se non è quello della sessione, il suo agente riceve l'identificativo del controllo
# remoto (session_...) e apre la sessione nell'app Claude con il link claude://code/<identificativo>.
# L'agente risponde alle richieste di inattività solo con un numero di secondi, e solo quando il backend lo chiede.
#
# Limiti voluti: l'agente non esegue comandi ricevuti dalla rete, non invia tasti e non usa i permessi di
# Accessibilità. Sa solo selezionare una scheda di Terminale o iTerm, portare avanti un'applicazione e aprire
# nell'app Claude un link che compone da sé con un identificativo controllato.
# Va attivato a mano su ogni Mac ("install"): l'installazione dell'hook non lo avvia.
#
# Protezioni contro un server difettoso o compromesso:
#   - si collega solo in https (http è ammesso soltanto verso questa stessa macchina);
#   - ogni richiesta anomala vale dei punti: identificativo malformato (blocco immediato), sessione che
#     questo Mac non conosce, sessione remota senza l'app Claude, richieste troppo ravvicinate. Raggiunta la
#     soglia in dieci minuti, o superato il numero massimo di focus o di richieste di inattività in un'ora,
#     l'agente si blocca: si scollega, avvisa con una notifica e non esegue più nulla finché sul Mac non si
#     lancia "unlock". Il blocco non si toglie dalla rete.
#   - l'interruttore nelle impostazioni della dashboard spegne il focus per tutte le macchine dell'utente:
#     il backend scollega l'agente e ne rifiuta i nuovi collegamenti.
#
# Uso:
#   claude-monitor-agent.sh install     registra l'agente in launchd (parte subito e a ogni accesso)
#   claude-monitor-agent.sh uninstall   lo ferma e lo rimuove
#   claude-monitor-agent.sh run         resta in ascolto (è il comando eseguito da launchd)
#   claude-monitor-agent.sh focus ID    porta in primo piano la finestra di una sessione, per prova
#   claude-monitor-agent.sh remote ID   apre nell'app Claude una sessione in controllo remoto (session_...), per prova
#   claude-monitor-agent.sh idle        secondi dall'ultimo uso di tastiera e mouse, come li riceve il backend
#   claude-monitor-agent.sh status      dice se l'agente è registrato e se è bloccato
#   claude-monitor-agent.sh unlock      toglie il blocco scattato per richieste anomale
#
# URL, chiave hook e nome della macchina sono quelli dell'hook: variabili d'ambiente CLAUDE_MONITOR_URL,
# CLAUDE_MONITOR_TOKEN e CLAUDE_MONITOR_MACHINE oppure, se mancano, la sezione "env" di ~/.claude/settings.json.

label="it.sartoriadelcodice.claude-monitor-agent"
windows_dir="$HOME/.claude/claude-monitor/windows"
settings_file="$HOME/.claude/settings.json"
plist_file="$HOME/Library/LaunchAgents/$label.plist"
log_file="$HOME/Library/Logs/claude-monitor-agent.log"
# Presente quando l'agente si è bloccato per richieste anomale: si toglie solo da questo Mac
lock_file="$HOME/.claude/claude-monitor/agent.locked"
# App Claude per macOS: apre le sessioni in controllo remoto con i link claude://
claude_bundle="com.anthropic.claudefordesktop"

# Soglie delle protezioni
suspect_limit=10       # punti di sospetto che fanno scattare il blocco
suspect_window=600     # secondi entro cui i punti si sommano
hourly_limit=60        # focus eseguiti in un'ora oltre i quali l'agente si blocca
probe_hourly_limit=120 # richieste di inattività in un'ora oltre le quali l'agente si blocca
min_interval=2         # secondi minimi tra due focus
suspects=()            # richieste sospette recenti, come "istante:punti"
executed=()            # istanti dei focus eseguiti nell'ultima ora
probed=()              # istanti delle richieste di inattività dell'ultima ora

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

# Variabile d'ambiente oppure valore omonimo nella sezione "env" delle impostazioni di Claude Code
setting() {
  local current
  eval "current=\"\${$1:-}\""
  if [ -n "$current" ]; then
    printf '%s' "$current"
  else
    plutil -extract "env.$1" raw -o - "$settings_file" 2>/dev/null
  fi
}

# Scheda di Terminale con il terminale indicato: la seleziona e porta avanti la sua finestra
focus_terminal() {
  osascript - "$1" 2>/dev/null <<'APPLESCRIPT'
on run argv
  set target to item 1 of argv
  tell application id "com.apple.Terminal"
    repeat with w in windows
      repeat with t in tabs of w
        try
          if tty of t is target then
            if miniaturized of w then set miniaturized of w to false
            set selected of t to true
            set frontmost of w to true
            activate
            return "ok"
          end if
        end try
      end repeat
    end repeat
  end tell
  return "missing"
end run
APPLESCRIPT
}

focus_iterm() {
  osascript - "$1" 2>/dev/null <<'APPLESCRIPT'
on run argv
  set target to item 1 of argv
  tell application id "com.googlecode.iterm2"
    repeat with w in windows
      repeat with t in tabs of w
        repeat with s in sessions of t
          try
            if tty of s is target then
              select w
              select t
              select s
              activate
              return "ok"
            end if
          end try
        end repeat
      end repeat
    end repeat
  end tell
  return "missing"
end run
APPLESCRIPT
}

# Blocca l'agente: da qui in poi non esegue nulla finché sul Mac non si lancia "unlock".
# Il motivo è sempre un testo fisso di questo script, mai un dato arrivato dalla rete.
lock_agent() {
  (umask 077 && mkdir -p "${lock_file%/*}" && printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >"$lock_file")
  log "AGENTE BLOCCATO: $1. Per riattivarlo: bash $0 unlock"
  osascript -e 'display notification "Richieste anomale dal server: il focus delle finestre è stato sospeso su questo Mac." with title "Claude Monitor"' >/dev/null 2>&1
}

# Registra una richiesta sospetta (punti, motivo) e blocca l'agente se nella finestra di tempo si supera la soglia
suspect() {
  local now cutoff entry total=0 kept=()
  now="$(date +%s)"
  cutoff=$((now - suspect_window))
  suspects+=("$now:$1")
  for entry in "${suspects[@]}"; do
    if [ "${entry%%:*}" -ge "$cutoff" ]; then
      kept+=("$entry")
      total=$((total + ${entry##*:}))
    fi
  done
  suspects=("${kept[@]}")
  log "richiesta sospetta: $2 (punti $total su $suspect_limit)"
  if [ "$total" -ge "$suspect_limit" ]; then
    lock_agent "troppe richieste anomale ($2)"
  fi
}

# Conta i focus eseguiti nell'ultima ora: un volume che nessuno produce a mano fa scattare il blocco
count_executed() {
  local now cutoff entry kept=()
  now="$(date +%s)"
  cutoff=$((now - 3600))
  executed+=("$now")
  for entry in "${executed[@]}"; do
    if [ "$entry" -ge "$cutoff" ]; then
      kept+=("$entry")
    fi
  done
  executed=("${kept[@]}")
  if [ "${#executed[@]}" -gt "$hourly_limit" ]; then
    lock_agent "più di $hourly_limit focus in un'ora"
  fi
}

# Conta le richieste di inattività dell'ultima ora. Il backend ne manda una per tocco su "Apri finestra":
# oltre la soglia qualcuno sta sorvegliando l'uso del Mac, e l'agente si blocca.
# Esce con 1 se la richiesta arriva a meno di un secondo dalla precedente o se è scattato il blocco.
count_probe() {
  local now cutoff entry kept=() last=0
  now="$(date +%s)"
  cutoff=$((now - 3600))
  [ "${#probed[@]}" -gt 0 ] && last="${probed[${#probed[@]}-1]}"
  probed+=("$now")
  for entry in "${probed[@]}"; do
    if [ "$entry" -ge "$cutoff" ]; then
      kept+=("$entry")
    fi
  done
  probed=("${kept[@]}")
  if [ "${#probed[@]}" -gt "$probe_hourly_limit" ]; then
    lock_agent "più di $probe_hourly_limit richieste di inattività in un'ora"
    return 1
  fi
  if [ $((now - last)) -lt 1 ]; then
    suspect 1 "richieste di inattività troppo ravvicinate"
    return 1
  fi
}

# Secondi trascorsi dall'ultimo uso di tastiera, mouse o trackpad di questo Mac
idle_seconds() {
  ioreg -c IOHIDSystem -d 4 -r -k HIDIdleTime 2>/dev/null |
    awk '/"HIDIdleTime"/ { printf "%d\n", $NF / 1000000000; exit }'
}

# App Claude installata: solo allora l'agente sa aprire le sessioni in controllo remoto
claude_app_installed() {
  local app
  for app in /Applications/Claude.app "$HOME/Applications/Claude.app"; do
    if [ "$(plutil -extract CFBundleIdentifier raw -o - "$app/Contents/Info.plist" 2>/dev/null)" = "$claude_bundle" ]; then
      return 0
    fi
  done
  return 1
}

# Risponde a una richiesta del backend con i secondi di inattività di questo Mac: non invia altro.
# Esce con 2 se l'identificativo della richiesta è malformato.
answer_probe() {
  local probe="$1" url="$2" token="$3" machine="$4" idle
  # Il backend genera 32 cifre esadecimali: altro non arriva da un server sano
  case "$probe" in '' | *[!0-9a-f]*) return 2 ;; esac
  [ "${#probe}" -eq 32 ] || return 2
  idle="$(idle_seconds)"
  case "$idle" in '' | *[!0-9]*)
    log "tempo di inattività non disponibile"
    return 0
    ;;
  esac
  # La chiave passa da stdin e non dalla riga di comando, dove la vedrebbero gli altri processi
  curl --config - --silent --max-time 5 --request POST \
    --data-urlencode "probe=$probe" --data-urlencode "machine=$machine" --data-urlencode "idle=$idle" \
    "${url%/}/api/agent/activity" >/dev/null 2>&1 <<EOF
header = "Authorization: Bearer $token"
EOF
  return 0
}

# Apre nell'app Claude la sessione in controllo remoto di un'altra macchina. Il link lo compone l'agente:
# dal backend arriva solo l'identificativo, controllato prima dell'uso.
# Esce con 2 se l'identificativo è malformato e con 3 se su questo Mac non c'è l'app Claude.
open_remote() {
  local id="$1"
  # Forma degli identificativi di Claude Code: session_ o cse_ seguito da lettere, cifre, "_" e "-".
  # Il valore non si scrive nel registro se non è valido, per non farci finire testo scelto da chi lo ha inviato.
  case "$id" in session_?* | cse_?*) ;; *)
    log "identificativo di sessione remota malformato (${#id} caratteri)"
    return 2
    ;;
  esac
  case "${id#*_}" in *[!A-Za-z0-9_-]*)
    log "identificativo di sessione remota malformato (${#id} caratteri)"
    return 2
    ;;
  esac
  if [ "${#id}" -gt 100 ]; then
    log "identificativo di sessione remota troppo lungo (${#id} caratteri)"
    return 2
  fi
  if ! claude_app_installed; then
    log "sessione remota $id: app Claude non installata su questo Mac"
    return 3
  fi
  log "sessione remota $id: app Claude"
  open -b "$claude_bundle" "claude://code/$id"
}

# Porta in primo piano la finestra della sessione. I valori letti dal file sono ricontrollati prima dell'uso.
# Esce con 2 se l'identificativo è malformato e con 3 se questo Mac non conosce la sessione.
focus_session() {
  local id="$1" file app tty folder
  # Gli identificativi di Claude Code sono brevi e senza caratteri speciali: altro non arriva da un server sano.
  # Il valore non si scrive nel registro, per non farci finire testo scelto da chi lo ha inviato.
  case "$id" in '' | *[!A-Za-z0-9_-]*)
    log "identificativo di sessione malformato (${#id} caratteri)"
    return 2
    ;;
  esac
  if [ "${#id}" -gt 100 ]; then
    log "identificativo di sessione troppo lungo (${#id} caratteri)"
    return 2
  fi
  file="$windows_dir/$id"
  # Solo un file vero e di questo utente: niente collegamenti simbolici verso altri file
  if [ ! -f "$file" ] || [ -L "$file" ] || [ ! -O "$file" ]; then
    log "sessione $id: finestra sconosciuta su questo Mac"
    return 3
  fi
  {
    IFS= read -r app
    IFS= read -r tty
    IFS= read -r folder
  } <"$file"
  case "$app" in *[!A-Za-z0-9.-]* | -*) app="" ;; esac
  # La cartella finisce tra gli argomenti di "open": solo percorsi assoluti, mai qualcosa che sembri un'opzione
  case "$folder" in /*) ;; *) folder="" ;; esac
  case "$tty" in
    /dev/ttys[0-9]*) case "${tty#/dev/ttys}" in *[!0-9]*) tty="" ;; esac ;;
    *) tty="" ;;
  esac

  if [ -n "$tty" ]; then
    case "$app" in
      com.apple.Terminal)
        if [ "$(focus_terminal "$tty")" = "ok" ]; then
          log "sessione $id: scheda di Terminale $tty"
          return 0
        fi
        ;;
      com.googlecode.iterm2)
        if [ "$(focus_iterm "$tty")" = "ok" ]; then
          log "sessione $id: scheda di iTerm $tty"
          return 0
        fi
        ;;
    esac
  fi
  if [ -z "$app" ]; then
    log "sessione $id: applicazione sconosciuta"
    return 3
  fi
  case "$app" in
    com.microsoft.VSCode* | com.vscodium* | com.todesktop.230313mzl4w4u92)
      # VS Code e derivati: aprire la cartella porta avanti la finestra che l'ha già aperta
      if [ -n "$folder" ] && [ -d "$folder" ]; then
        log "sessione $id: finestra di $app con la cartella del progetto"
        open -b "$app" "$folder"
        return
      fi
      ;;
  esac
  # Scheda non trovata o applicazione senza supporto specifico: si porta avanti l'applicazione
  log "sessione $id: applicazione $app"
  open -b "$app"
}

install_agent() {
  if [ "$(uname -s)" != "Darwin" ]; then
    echo "L'agente per il focus delle finestre serve solo su macOS: niente da installare."
    return 0
  fi
  local script domain escaped
  script="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  domain="gui/$(id -u)"
  escaped() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
  }
  mkdir -p "${plist_file%/*}" "${log_file%/*}"
  cat >"$plist_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$label</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$(escaped "$script")</string>
    <string>run</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>$(escaped "$log_file")</string>
  <key>StandardErrorPath</key>
  <string>$(escaped "$log_file")</string>
</dict>
</plist>
EOF
  # Se era già registrato si ferma e si aspetta che launchd lo abbia tolto davvero
  if launchctl bootout "$domain/$label" 2>/dev/null; then
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      launchctl print "$domain/$label" >/dev/null 2>&1 || break
      sleep 0.5
    done
  fi
  if launchctl bootstrap "$domain" "$plist_file"; then
    echo "Agente per il focus delle finestre attivo (registro: $log_file)."
    echo "Al primo utilizzo macOS chiede di consentire il controllo di Terminale: va accettato."
  else
    echo "Errore: launchd non ha avviato l'agente ($plist_file)." >&2
    return 1
  fi
}

uninstall_agent() {
  launchctl bootout "gui/$(id -u)/$label" 2>/dev/null
  rm -f "$plist_file"
  echo "Agente rimosso."
}

agent_status() {
  if launchctl print "gui/$(id -u)/$label" >/dev/null 2>&1; then
    echo "Agente registrato in launchd."
  else
    echo "Agente non registrato in launchd."
  fi
  if [ -e "$lock_file" ]; then
    echo "BLOCCATO per richieste anomale: $(cat "$lock_file" 2>/dev/null)"
    echo "Controlla $log_file e, se è tutto a posto, riattivalo con: bash $0 unlock"
  else
    echo "Nessun blocco attivo."
  fi
}

unlock_agent() {
  if [ -e "$lock_file" ]; then
    rm -f "$lock_file"
    echo "Blocco tolto: l'agente si ricollega entro mezzo minuto."
  else
    echo "Nessun blocco attivo."
  fi
}

run_agent() {
  local fifo_dir fifo curl_pid="" url token machine line event data connected delay=2 now last_focus=0
  local locked_logged=0 refused_logged=0 claude_app
  fifo_dir="$(mktemp -d "${TMPDIR:-/tmp}/claude-monitor-agent.XXXXXX")" || exit 1
  fifo="$fifo_dir/stream"
  mkfifo "$fifo" || exit 1
  trap '[ -n "$curl_pid" ] && kill "$curl_pid" 2>/dev/null; rm -rf "$fifo_dir"' EXIT
  trap 'exit 0' TERM INT HUP

  while true; do
    # Bloccato: nessun collegamento finché il file non viene tolto con "unlock"
    if [ -e "$lock_file" ]; then
      if [ "$locked_logged" = 0 ]; then
        locked_logged=1
        log "agente bloccato: nessun collegamento finché non si lancia \"bash $0 unlock\""
      fi
      sleep 30
      continue
    fi
    if [ "$locked_logged" = 1 ]; then
      locked_logged=0
      suspects=()
      executed=()
      log "blocco tolto: l'agente riprende"
    fi
    url="$(setting CLAUDE_MONITOR_URL)"
    token="$(setting CLAUDE_MONITOR_TOKEN)"
    machine="$(setting CLAUDE_MONITOR_MACHINE)"
    machine="${machine:-$(hostname -s 2>/dev/null || hostname 2>/dev/null || echo sconosciuta)}"
    # Le impostazioni si rileggono a ogni tentativo: una chiave nuova vale senza riavviare l'agente
    case "$url$token" in *'"'* | *'\'*) url="" ;; esac
    if [ -z "$url" ] || [ -z "$token" ]; then
      log "CLAUDE_MONITOR_URL o CLAUDE_MONITOR_TOKEN mancanti in $settings_file: nuovo tentativo tra un minuto"
      sleep 60
      continue
    fi
    # Senza https chiunque sulla rete potrebbe fingersi il server e leggere la chiave
    case "$url" in
      https://* | http://127.0.0.1 | http://127.0.0.1[:/]* | http://localhost | http://localhost[:/]*) ;;
      *)
        log "indirizzo rifiutato: l'agente si collega solo in https. Nuovo tentativo tra un minuto"
        sleep 60
        continue
        ;;
    esac
    # Le sessioni chiuse male lasciano il file della finestra: si eliminano quelli vecchi
    find "$windows_dir" -type f -mtime +30 -delete 2>/dev/null
    # Con l'app Claude il backend può mandare qui le sessioni in controllo remoto delle altre macchine
    if claude_app_installed; then
      claude_app=true
    else
      claude_app=false
    fi

    # La chiave passa da stdin e non dalla riga di comando, dove la vedrebbero gli altri processi
    curl --config - --silent --fail --no-buffer --connect-timeout 10 \
      --get --data-urlencode "machine=$machine" --data-urlencode "claudeApp=$claude_app" \
      "${url%/}/api/agent/stream" >"$fifo" 2>/dev/null <<EOF &
header = "Authorization: Bearer $token"
header = "Accept: text/event-stream"
EOF
    curl_pid=$!

    event=""
    connected=0
    # Il backend manda un segnale ogni 20 secondi: un minuto di silenzio vuol dire connessione morta
    while IFS= read -r -t 60 line; do
      line="${line%$'\r'}"
      if [ "$connected" = 0 ]; then
        connected=1
        delay=2
        refused_logged=0
        log "collegato a $url come $machine (app Claude: $claude_app)"
      fi
      case "$line" in
        event:*)
          event="${line#event:}"
          event="${event# }"
          ;;
        data:*)
          data="${line#data:}"
          data="${data# }"
          if [ "$event" = "focus" ] || [ "$event" = "remote" ]; then
            now="$(date +%s)"
            if [ $((now - last_focus)) -lt "$min_interval" ]; then
              # Un server sano non manda richieste a raffica: la dashboard ne invia una per tocco
              suspect 1 "richieste troppo ravvicinate"
            else
              last_focus="$now"
              if [ "$event" = "focus" ]; then
                focus_session "$data"
              else
                open_remote "$data"
              fi
              case "$?" in
                0) count_executed ;;
                2) suspect "$suspect_limit" "identificativo malformato" ;;
                3) suspect 1 "sessione sconosciuta su questo Mac o app Claude assente" ;;
              esac
            fi
          elif [ "$event" = "probe" ]; then
            if count_probe; then
              answer_probe "$data" "$url" "$token" "$machine" ||
                suspect "$suspect_limit" "identificativo di richiesta malformato"
            fi
          elif [ "$event" = "disabled" ]; then
            log "focus disattivato dalle impostazioni della dashboard"
            delay=60
          fi
          # Scattato il blocco si chiude subito il collegamento
          if [ -e "$lock_file" ]; then
            break
          fi
          ;;
        '') event="" ;;
      esac
    done <"$fifo"
    kill "$curl_pid" 2>/dev/null
    wait "$curl_pid" 2>/dev/null
    curl_pid=""
    if [ "$connected" = 1 ]; then
      log "connessione chiusa"
    elif [ "$refused_logged" = 0 ] && [ ! -e "$lock_file" ]; then
      refused_logged=1
      log "collegamento non riuscito: server non raggiungibile, chiave non valida o focus disattivato"
    fi
    sleep "$delay"
    delay=$((delay * 2 > 60 ? 60 : delay * 2))
  done
}

case "${1:-run}" in
  install) install_agent ;;
  uninstall) uninstall_agent ;;
  run) run_agent ;;
  focus) focus_session "${2:-}" ;;
  remote) open_remote "${2:-}" ;;
  idle) idle_seconds ;;
  status) agent_status ;;
  unlock) unlock_agent ;;
  *)
    echo "Uso: $0 install | uninstall | run | focus <session_id> | remote <session_...> | idle | status | unlock" >&2
    exit 2
    ;;
esac
