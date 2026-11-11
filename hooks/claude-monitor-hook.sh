#!/usr/bin/env bash
# Hook di Claude Code per Claude Monitor.
# Legge il JSON dell'evento da stdin, aggiunge macchina e progetto e lo invia al backend.
# Non deve mai bloccare né far fallire Claude Code: timeout breve ed esce sempre con 0.
#
# Variabili d'ambiente:
#   CLAUDE_MONITOR_URL      URL base del backend, ad esempio https://monitor.example.com
#   CLAUDE_MONITOR_TOKEN    token Bearer condiviso con il backend (HOOK_TOKEN)
#   CLAUDE_MONITOR_MACHINE  (facoltativa) nome della macchina, al posto dell'hostname

url="${CLAUDE_MONITOR_URL:-}"
token="${CLAUDE_MONITOR_TOKEN:-}"
payload="$(cat 2>/dev/null)"

# Senza configurazione o senza payload non c'è nulla da inviare
if [ -z "$url" ] || [ -z "$token" ] || [ -z "$payload" ]; then
  exit 0
fi

machine="${CLAUDE_MONITOR_MACHINE:-$(hostname -s 2>/dev/null || hostname 2>/dev/null || echo sconosciuta)}"

# Primo valore stringa di un campo del payload, senza jq
field() {
  printf '%s' "$payload" | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

# Valore di un campo stringa del payload, con jq quando c'è
value() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$payload" | jq -r --arg name "$1" '.[$name] // empty' 2>/dev/null
  else
    field "$1"
  fi
}

# Finestra della sessione (solo macOS): serve a claude-monitor-agent.sh per portarla in primo piano quando
# la dashboard lo chiede. Applicazione, terminale e cartella restano in un file locale: non vengono inviati.
remember_window() {
  local id event file pid parent tty found
  id="$(value session_id)"
  case "$id" in '' | *[!A-Za-z0-9_-]*) return ;; esac
  event="$(value hook_event_name)"
  file="$HOME/.claude/claude-monitor/windows/$id"
  if [ "$event" = "SessionEnd" ]; then
    rm -f "$file"
    return
  fi
  # All'avvio e alla ripresa la finestra può essere un'altra; negli altri eventi si scrive solo se manca
  if [ "$event" != "SessionStart" ] && [ -f "$file" ]; then
    return
  fi
  # Il terminale è quello del primo antenato che ne ha uno, cioè Claude Code
  pid=$$
  found=""
  for _ in 1 2 3 4 5 6 7 8; do
    read -r parent tty <<EOF
$(ps -o ppid=,tty= -p "$pid" 2>/dev/null)
EOF
    case "$tty" in ttys[0-9]*)
      found="/dev/$tty"
      break
      ;;
    esac
    case "$parent" in '' | 0 | 1) break ;; esac
    pid="$parent"
  done
  # Cartella e file leggibili solo dall'utente: è da qui che l'agente decide quale finestra portare avanti
  (umask 077 && mkdir -p "${file%/*}") 2>/dev/null || return
  umask 077
  printf '%s\n%s\n%s\n' "${__CFBundleIdentifier:-}" "$found" "${CLAUDE_PROJECT_DIR:-$(value cwd)}" \
    >"$file" 2>/dev/null
}

if [ "$(uname -s 2>/dev/null)" = "Darwin" ]; then
  remember_window
fi

# Controllo remoto (Remote Control) di Claude Code: l'identificativo della sessione remota (session_...) sta in
# ~/.claude/sessions/<pid>.json, un file per ogni processo di Claude Code. Con quello la dashboard apre la
# sessione nell'app Claude del Mac in uso. Si legge a ogni evento: spento il controllo remoto, il campo manca.
remote_session_id() {
  local id dir candidate newest="" remote
  id="$(value session_id)"
  case "$id" in '' | *[!A-Za-z0-9_-]*) return ;; esac
  dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/sessions"
  [ -d "$dir" ] || return
  # Il file della sessione; se ce n'è più d'uno (processo chiuso male) vale il più recente
  while IFS= read -r candidate; do
    if [ -z "$newest" ] || [ "$candidate" -nt "$newest" ]; then
      newest="$candidate"
    fi
  done < <(grep -lE "\"sessionId\"[[:space:]]*:[[:space:]]*\"$id\"" "$dir"/*.json 2>/dev/null)
  [ -n "$newest" ] || return
  if command -v jq >/dev/null 2>&1; then
    remote="$(jq -r '.bridgeSessionId // empty' "$newest" 2>/dev/null)"
  else
    remote="$(sed -n 's/.*"bridgeSessionId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$newest" | head -n 1)"
  fi
  # Finisce in un link aperto da un altro Mac: solo la forma di Claude Code, mai altro testo
  case "$remote" in session_?* | cse_?*) ;; *) return ;; esac
  case "${remote#*_}" in *[!A-Za-z0-9_-]*) return ;; esac
  [ "${#remote}" -le 100 ] && printf '%s' "$remote"
}

remote="$(remote_session_id)"

# Il modello arriva con SessionStart solo in alcuni casi (mai dopo /clear): quando manca si legge
# dall'ultima risposta nel transcript. Solo su Stop e PostToolUse, quando il transcript è di sicuro
# aggiornato: negli altri eventi potrebbe riportare il modello precedente a un cambio.
transcript_model() {
  case "$1" in
    Stop|PostToolUse) ;;
    *) return ;;
  esac
  [ -f "$2" ] || return
  tail -c 262144 "$2" 2>/dev/null | grep -o '"model":"claude-[^"]*"' | tail -n 1 | cut -d '"' -f 4
}

if command -v jq >/dev/null 2>&1; then
  model="$(transcript_model "$(printf '%s' "$payload" | jq -r '.hook_event_name // empty' 2>/dev/null)" \
    "$(printf '%s' "$payload" | jq -r '.transcript_path // empty' 2>/dev/null)")"
  # Il progetto è l'ultima parte di cwd. Si tolgono i contenuti della conversazione, che alla dashboard
  # non servono: prompt, risposta di Claude, input e output dei tool, comandi dei task in background.
  body="$(printf '%s' "$payload" | jq -c --arg machine "$machine" --arg model "$model" --arg remote "$remote" '
    . + {
      machine: $machine,
      project: ((.cwd // "") | split("/") | map(select(length > 0)) | last // "sconosciuto")
    }
    | if $model != "" and (.model | not) then . + {model: $model} else . end
    | if $remote != "" then . + {remote_session_id: $remote} else . end
    | del(.prompt, .tool_input, .tool_response, .permission_suggestions, .last_assistant_message)
    | if (.background_tasks | type) == "array" then .background_tasks |= map({id, type, status}) else . end
    | if (.session_crons | type) == "array" then .session_crons |= map({id, schedule, recurring}) else . end
  ' 2>/dev/null)"
else
  # Senza jq: si inseriscono i campi in testa all'oggetto JSON
  cwd="$(field cwd)"
  project="$(basename "${cwd:-sconosciuto}" | tr -d '"\\')"
  safe_machine="$(printf '%s' "$machine" | tr -d '"\\')"
  extra=""
  if [ -z "$(field model)" ]; then
    model="$(transcript_model "$(field hook_event_name)" "$(field transcript_path)" | tr -d '"\\/')"
    [ -n "$model" ] && extra="\"model\":\"${model}\","
  fi
  # Già controllato in remote_session_id: solo lettere, cifre, "_" e "-"
  [ -n "$remote" ] && extra="${extra}\"remote_session_id\":\"${remote}\","
  body="$(printf '%s' "$payload" | sed "1s/{/{\"machine\":\"${safe_machine}\",\"project\":\"${project//\//\\/}\",${extra}/")"
fi

if [ -z "$body" ]; then
  exit 0
fi

printf '%s' "$body" | curl --silent --show-error \
  --connect-timeout 2 --max-time 3 \
  --request POST "${url%/}/api/events" \
  --header "Authorization: Bearer ${token}" \
  --header "Content-Type: application/json" \
  --data-binary @- >/dev/null 2>&1

exit 0
