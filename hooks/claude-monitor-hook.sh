#!/usr/bin/env bash
# Claude Code hook for Claude Monitor.
# Reads the event JSON from stdin, adds machine and project and sends it to the backend.
# It must never block or break Claude Code: short timeout and it always exits with 0.
#
# Environment variables:
#   CLAUDE_MONITOR_URL      backend base URL, for example https://monitor.example.com
#   CLAUDE_MONITOR_TOKEN    Bearer token shared with the backend (HOOK_TOKEN)
#   CLAUDE_MONITOR_MACHINE  (optional) machine name, instead of the hostname

url="${CLAUDE_MONITOR_URL:-}"
token="${CLAUDE_MONITOR_TOKEN:-}"
payload="$(cat 2>/dev/null)"

# Without configuration or without payload there is nothing to send
if [ -z "$url" ] || [ -z "$token" ] || [ -z "$payload" ]; then
  exit 0
fi

machine="${CLAUDE_MONITOR_MACHINE:-$(hostname -s 2>/dev/null || hostname 2>/dev/null || echo unknown)}"

# First string value of a payload field, without jq
field() {
  printf '%s' "$payload" | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

# Value of a string field of the payload, with jq when available
value() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$payload" | jq -r --arg name "$1" '.[$name] // empty' 2>/dev/null
  else
    field "$1"
  fi
}

# Window of the session (macOS only): claude-monitor-agent.sh uses it to bring the window to the front when
# the dashboard asks. Application, terminal and folder stay in a local file: they are never sent.
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
  # On startup and on resume the window may be a different one; on other events it is written only if missing
  if [ "$event" != "SessionStart" ] && [ -f "$file" ]; then
    return
  fi
  # The terminal is the one of the first ancestor that has one, that is Claude Code
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
  # Folder and file readable only by the user: the agent decides from here which window to bring forward
  (umask 077 && mkdir -p "${file%/*}") 2>/dev/null || return
  umask 077
  printf '%s\n%s\n%s\n' "${__CFBundleIdentifier:-}" "$found" "${CLAUDE_PROJECT_DIR:-$(value cwd)}" \
    >"$file" 2>/dev/null
}

if [ "$(uname -s 2>/dev/null)" = "Darwin" ]; then
  remember_window
fi

# Claude Code Remote Control: the id of the remote session (session_...) is in
# ~/.claude/sessions/<pid>.json, one file per Claude Code process. With it the dashboard opens the
# session in the Claude app of the Mac in use. Read on every event: when Remote Control is off, the field is missing.
remote_session_id() {
  local id dir candidate newest="" remote
  id="$(value session_id)"
  case "$id" in '' | *[!A-Za-z0-9_-]*) return ;; esac
  dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/sessions"
  [ -d "$dir" ] || return
  # The session file; if there is more than one (process not closed cleanly) the most recent one wins
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
  # It ends up in a link opened by another Mac: only the Claude Code format, never any other text
  case "$remote" in session_?* | cse_?*) ;; *) return ;; esac
  case "${remote#*_}" in *[!A-Za-z0-9_-]*) return ;; esac
  [ "${#remote}" -le 100 ] && printf '%s' "$remote"
}

remote="$(remote_session_id)"

# The model comes with SessionStart only in some cases (never after /clear): when it is missing it is read
# from the last response in the transcript. Only on Stop and PostToolUse, when the transcript is surely
# up to date: on other events it could report the model from before a switch.
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
  # The project is the last part of cwd. The conversation content is removed, since the dashboard
  # does not need it: prompt, Claude's response, tool input and output, background task commands.
  body="$(printf '%s' "$payload" | jq -c --arg machine "$machine" --arg model "$model" --arg remote "$remote" '
    . + {
      machine: $machine,
      project: ((.cwd // "") | split("/") | map(select(length > 0)) | last // "unknown")
    }
    | if $model != "" and (.model | not) then . + {model: $model} else . end
    | if $remote != "" then . + {remote_session_id: $remote} else . end
    | del(.prompt, .tool_input, .tool_response, .permission_suggestions, .last_assistant_message)
    | if (.background_tasks | type) == "array" then .background_tasks |= map({id, type, status}) else . end
    | if (.session_crons | type) == "array" then .session_crons |= map({id, schedule, recurring}) else . end
  ' 2>/dev/null)"
else
  # Without jq: the fields are inserted at the start of the JSON object
  cwd="$(field cwd)"
  project="$(basename "${cwd:-unknown}" | tr -d '"\\')"
  safe_machine="$(printf '%s' "$machine" | tr -d '"\\')"
  extra=""
  if [ -z "$(field model)" ]; then
    model="$(transcript_model "$(field hook_event_name)" "$(field transcript_path)" | tr -d '"\\/')"
    [ -n "$model" ] && extra="\"model\":\"${model}\","
  fi
  # Already checked in remote_session_id: only letters, digits, "_" and "-"
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
