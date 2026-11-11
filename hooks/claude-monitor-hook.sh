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
  body="$(printf '%s' "$payload" | jq -c --arg machine "$machine" --arg model "$model" '
    . + {
      machine: $machine,
      project: ((.cwd // "") | split("/") | map(select(length > 0)) | last // "sconosciuto")
    }
    | if $model != "" and (.model | not) then . + {model: $model} else . end
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
