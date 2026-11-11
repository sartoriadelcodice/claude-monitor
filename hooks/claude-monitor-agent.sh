#!/bin/bash
# Claude Monitor agent for macOS.
# Stays connected to the backend and, when the dashboard asks for the focus of a session, brings the window
# hosting it to the front. The window is the one recorded by claude-monitor-hook.sh in
# ~/.claude/claude-monitor/windows/<session_id>: the backend only sends the session id,
# so the server cannot make it open anything else.
#
#
# Remote Control (Claude Code Remote Control): if the session of another machine can be followed remotely,
# the backend asks all agents how many seconds keyboard and mouse have been idle ("probe") and picks the
# Mac used most recently. If it is not the session's Mac, its agent receives the Remote Control id
# (session_...) and opens the session in the Claude app with the link claude://code/<id>.
# The agent answers idle-time requests only with a number of seconds, and only when the backend asks.
#
# Intended limits: the agent does not execute commands received from the network, does not send keystrokes and
# does not use Accessibility permissions. It can only select a Terminal or iTerm tab, bring an application to
# the front and open in the Claude app a link it builds itself from a checked id.
# It must be enabled by hand on each Mac ("install"): installing the hook does not start it.
#
# Protections against a faulty or compromised server:
#   - it connects only over https (http is allowed only towards this same machine);
#   - every anomalous request is worth some points: malformed id (immediate lock), session that
#     this Mac does not know, remote session without the Claude app, requests too close together. When the
#     threshold is reached within ten minutes, or the maximum number of focus or idle-time requests in an hour
#     is exceeded, the agent locks itself: it disconnects, shows a notification and does nothing more until
#     "unlock" is run on the Mac. The lock cannot be removed from the network.
#   - the switch in the dashboard settings turns off focus for all the user's machines:
#     the backend disconnects the agent and refuses its new connections.
#
# Usage:
#   claude-monitor-agent.sh install     registers the agent in launchd (starts now and at every login)
#   claude-monitor-agent.sh uninstall   stops and removes it
#   claude-monitor-agent.sh run         keeps listening (the command run by launchd)
#   claude-monitor-agent.sh focus ID    brings the window of a session to the front, for testing
#   claude-monitor-agent.sh remote ID   opens a Remote Control session (session_...) in the Claude app, for testing
#   claude-monitor-agent.sh idle        seconds since keyboard and mouse were last used, as the backend receives them
#   claude-monitor-agent.sh status      tells whether the agent is registered and whether it is locked
#   claude-monitor-agent.sh unlock      removes the lock triggered by anomalous requests
#
# URL, hook key and machine name are the same as the hook's: environment variables CLAUDE_MONITOR_URL,
# CLAUDE_MONITOR_TOKEN and CLAUDE_MONITOR_MACHINE or, if missing, the "env" section of ~/.claude/settings.json.

label="it.sartoriadelcodice.claude-monitor-agent"
windows_dir="$HOME/.claude/claude-monitor/windows"
settings_file="$HOME/.claude/settings.json"
plist_file="$HOME/Library/LaunchAgents/$label.plist"
log_file="$HOME/Library/Logs/claude-monitor-agent.log"
# Present when the agent has locked itself because of anomalous requests: it can only be removed from this Mac
lock_file="$HOME/.claude/claude-monitor/agent.locked"
# Claude app for macOS: opens Remote Control sessions with claude:// links
claude_bundle="com.anthropic.claudefordesktop"

# Protection thresholds
suspect_limit=10       # suspicion points that trigger the lock
suspect_window=600     # seconds within which the points add up
hourly_limit=60        # focus actions executed in an hour beyond which the agent locks itself
probe_hourly_limit=120 # idle-time requests in an hour beyond which the agent locks itself
min_interval=2         # minimum seconds between two focus actions
suspects=()            # recent suspicious requests, as "time:points"
executed=()            # times of the focus actions executed in the last hour
probed=()              # times of the idle-time requests in the last hour

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

# Environment variable or the value with the same name in the "env" section of the Claude Code settings
setting() {
  local current
  eval "current=\"\${$1:-}\""
  if [ -n "$current" ]; then
    printf '%s' "$current"
  else
    plutil -extract "env.$1" raw -o - "$settings_file" 2>/dev/null
  fi
}

# Terminal tab with the given tty: selects it and brings its window to the front
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

# Locks the agent: from now on it does nothing until "unlock" is run on the Mac.
# The reason is always a fixed text of this script, never data received from the network.
lock_agent() {
  (umask 077 && mkdir -p "${lock_file%/*}" && printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >"$lock_file")
  log "AGENT LOCKED: $1. To re-enable it: bash $0 unlock"
  osascript -e 'display notification "Anomalous requests from the server: window focus has been suspended on this Mac." with title "Claude Monitor"' >/dev/null 2>&1
}

# Records a suspicious request (points, reason) and locks the agent if the threshold is exceeded within the time window
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
  log "suspicious request: $2 ($total of $suspect_limit points)"
  if [ "$total" -ge "$suspect_limit" ]; then
    lock_agent "too many anomalous requests ($2)"
  fi
}

# Counts the focus actions executed in the last hour: a volume nobody produces by hand triggers the lock
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
    lock_agent "more than $hourly_limit focus actions in an hour"
  fi
}

# Counts the idle-time requests of the last hour. The backend sends one per tap on "Open window":
# beyond the threshold someone is watching how the Mac is used, and the agent locks itself.
# Returns 1 if the request arrives less than a second after the previous one or if the lock was triggered.
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
    lock_agent "more than $probe_hourly_limit idle-time requests in an hour"
    return 1
  fi
  if [ $((now - last)) -lt 1 ]; then
    suspect 1 "idle-time requests too close together"
    return 1
  fi
}

# Seconds since the keyboard, mouse or trackpad of this Mac were last used
idle_seconds() {
  ioreg -c IOHIDSystem -d 4 -r -k HIDIdleTime 2>/dev/null |
    awk '/"HIDIdleTime"/ { printf "%d\n", $NF / 1000000000; exit }'
}

# Claude app installed: only then can the agent open Remote Control sessions
claude_app_installed() {
  local app
  for app in /Applications/Claude.app "$HOME/Applications/Claude.app"; do
    if [ "$(plutil -extract CFBundleIdentifier raw -o - "$app/Contents/Info.plist" 2>/dev/null)" = "$claude_bundle" ]; then
      return 0
    fi
  done
  return 1
}

# Answers a backend request with the idle seconds of this Mac: it sends nothing else.
# Returns 2 if the request id is malformed.
answer_probe() {
  local probe="$1" url="$2" token="$3" machine="$4" idle
  # The backend generates 32 hex digits: nothing else comes from a healthy server
  case "$probe" in '' | *[!0-9a-f]*) return 2 ;; esac
  [ "${#probe}" -eq 32 ] || return 2
  idle="$(idle_seconds)"
  case "$idle" in '' | *[!0-9]*)
    log "idle time not available"
    return 0
    ;;
  esac
  # The key goes through stdin and not the command line, where other processes would see it
  curl --config - --silent --max-time 5 --request POST \
    --data-urlencode "probe=$probe" --data-urlencode "machine=$machine" --data-urlencode "idle=$idle" \
    "${url%/}/api/agent/activity" >/dev/null 2>&1 <<EOF
header = "Authorization: Bearer $token"
EOF
  return 0
}

# Opens in the Claude app the Remote Control session of another machine. The agent builds the link itself:
# the backend only sends the id, which is checked before use.
# Returns 2 if the id is malformed and 3 if the Claude app is not on this Mac.
open_remote() {
  local id="$1"
  # Format of Claude Code ids: session_ or cse_ followed by letters, digits, "_" and "-".
  # An invalid value is not written to the log, so that no text chosen by the sender ends up there.
  case "$id" in session_?* | cse_?*) ;; *)
    log "malformed remote session id (${#id} characters)"
    return 2
    ;;
  esac
  case "${id#*_}" in *[!A-Za-z0-9_-]*)
    log "malformed remote session id (${#id} characters)"
    return 2
    ;;
  esac
  if [ "${#id}" -gt 100 ]; then
    log "remote session id too long (${#id} characters)"
    return 2
  fi
  if ! claude_app_installed; then
    log "remote session $id: Claude app not installed on this Mac"
    return 3
  fi
  log "remote session $id: Claude app"
  open -b "$claude_bundle" "claude://code/$id"
}

# Brings the window of the session to the front. The values read from the file are checked again before use.
# Returns 2 if the id is malformed and 3 if this Mac does not know the session.
focus_session() {
  local id="$1" file app tty folder
  # Claude Code ids are short and have no special characters: nothing else comes from a healthy server.
  # The value is not written to the log, so that no text chosen by the sender ends up there.
  case "$id" in '' | *[!A-Za-z0-9_-]*)
    log "malformed session id (${#id} characters)"
    return 2
    ;;
  esac
  if [ "${#id}" -gt 100 ]; then
    log "session id too long (${#id} characters)"
    return 2
  fi
  file="$windows_dir/$id"
  # Only a regular file owned by this user: no symbolic links to other files
  if [ ! -f "$file" ] || [ -L "$file" ] || [ ! -O "$file" ]; then
    log "session $id: unknown window on this Mac"
    return 3
  fi
  {
    IFS= read -r app
    IFS= read -r tty
    IFS= read -r folder
  } <"$file"
  case "$app" in *[!A-Za-z0-9.-]* | -*) app="" ;; esac
  # The folder ends up among the arguments of "open": only absolute paths, never anything that looks like an option
  case "$folder" in /*) ;; *) folder="" ;; esac
  case "$tty" in
    /dev/ttys[0-9]*) case "${tty#/dev/ttys}" in *[!0-9]*) tty="" ;; esac ;;
    *) tty="" ;;
  esac

  if [ -n "$tty" ]; then
    case "$app" in
      com.apple.Terminal)
        if [ "$(focus_terminal "$tty")" = "ok" ]; then
          log "session $id: Terminal tab $tty"
          return 0
        fi
        ;;
      com.googlecode.iterm2)
        if [ "$(focus_iterm "$tty")" = "ok" ]; then
          log "session $id: iTerm tab $tty"
          return 0
        fi
        ;;
    esac
  fi
  if [ -z "$app" ]; then
    log "session $id: unknown application"
    return 3
  fi
  case "$app" in
    com.microsoft.VSCode* | com.vscodium* | com.todesktop.230313mzl4w4u92)
      # VS Code and derivatives: opening the folder brings forward the window that already has it open
      if [ -n "$folder" ] && [ -d "$folder" ]; then
        log "session $id: $app window with the project folder"
        open -b "$app" "$folder"
        return
      fi
      ;;
  esac
  # Tab not found or application without specific support: the application is brought to the front
  log "session $id: application $app"
  open -b "$app"
}

install_agent() {
  if [ "$(uname -s)" != "Darwin" ]; then
    echo "The window focus agent is only needed on macOS: nothing to install."
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
  # If it was already registered, stop it and wait until launchd has really removed it
  if launchctl bootout "$domain/$label" 2>/dev/null; then
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      launchctl print "$domain/$label" >/dev/null 2>&1 || break
      sleep 0.5
    done
  fi
  if launchctl bootstrap "$domain" "$plist_file"; then
    echo "Window focus agent active (log: $log_file)."
    echo "On first use macOS asks to allow control of Terminal: accept it."
  else
    echo "Error: launchd did not start the agent ($plist_file)." >&2
    return 1
  fi
}

uninstall_agent() {
  launchctl bootout "gui/$(id -u)/$label" 2>/dev/null
  rm -f "$plist_file"
  echo "Agent removed."
}

agent_status() {
  if launchctl print "gui/$(id -u)/$label" >/dev/null 2>&1; then
    echo "Agent registered in launchd."
  else
    echo "Agent not registered in launchd."
  fi
  if [ -e "$lock_file" ]; then
    echo "LOCKED because of anomalous requests: $(cat "$lock_file" 2>/dev/null)"
    echo "Check $log_file and, if everything is fine, re-enable it with: bash $0 unlock"
  else
    echo "No active lock."
  fi
}

unlock_agent() {
  if [ -e "$lock_file" ]; then
    rm -f "$lock_file"
    echo "Lock removed: the agent reconnects within half a minute."
  else
    echo "No active lock."
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
    # Locked: no connection until the file is removed with "unlock"
    if [ -e "$lock_file" ]; then
      if [ "$locked_logged" = 0 ]; then
        locked_logged=1
        log "agent locked: no connection until \"bash $0 unlock\" is run"
      fi
      sleep 30
      continue
    fi
    if [ "$locked_logged" = 1 ]; then
      locked_logged=0
      suspects=()
      executed=()
      log "lock removed: the agent resumes"
    fi
    url="$(setting CLAUDE_MONITOR_URL)"
    token="$(setting CLAUDE_MONITOR_TOKEN)"
    machine="$(setting CLAUDE_MONITOR_MACHINE)"
    machine="${machine:-$(hostname -s 2>/dev/null || hostname 2>/dev/null || echo unknown)}"
    # The settings are read again on every attempt: a new key takes effect without restarting the agent
    case "$url$token" in *'"'* | *'\'*) url="" ;; esac
    if [ -z "$url" ] || [ -z "$token" ]; then
      log "CLAUDE_MONITOR_URL or CLAUDE_MONITOR_TOKEN missing in $settings_file: retrying in a minute"
      sleep 60
      continue
    fi
    # Without https anyone on the network could pretend to be the server and read the key
    case "$url" in
      https://* | http://127.0.0.1 | http://127.0.0.1[:/]* | http://localhost | http://localhost[:/]*) ;;
      *)
        log "address refused: the agent only connects over https. Retrying in a minute"
        sleep 60
        continue
        ;;
    esac
    # Sessions that were not closed cleanly leave their window file behind: the old ones are deleted
    find "$windows_dir" -type f -mtime +30 -delete 2>/dev/null
    # With the Claude app the backend can send here the Remote Control sessions of the other machines
    if claude_app_installed; then
      claude_app=true
    else
      claude_app=false
    fi

    # The key goes through stdin and not the command line, where other processes would see it
    curl --config - --silent --fail --no-buffer --connect-timeout 10 \
      --get --data-urlencode "machine=$machine" --data-urlencode "claudeApp=$claude_app" \
      "${url%/}/api/agent/stream" >"$fifo" 2>/dev/null <<EOF &
header = "Authorization: Bearer $token"
header = "Accept: text/event-stream"
EOF
    curl_pid=$!

    event=""
    connected=0
    # The backend sends a heartbeat every 20 seconds: a minute of silence means the connection is dead
    while IFS= read -r -t 60 line; do
      line="${line%$'\r'}"
      if [ "$connected" = 0 ]; then
        connected=1
        delay=2
        refused_logged=0
        log "connected to $url as $machine (Claude app: $claude_app)"
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
              # A healthy server does not send bursts of requests: the dashboard sends one per tap
              suspect 1 "requests too close together"
            else
              last_focus="$now"
              if [ "$event" = "focus" ]; then
                focus_session "$data"
              else
                open_remote "$data"
              fi
              case "$?" in
                0) count_executed ;;
                2) suspect "$suspect_limit" "malformed id" ;;
                3) suspect 1 "session unknown on this Mac or Claude app missing" ;;
              esac
            fi
          elif [ "$event" = "probe" ]; then
            if count_probe; then
              answer_probe "$data" "$url" "$token" "$machine" ||
                suspect "$suspect_limit" "malformed request id"
            fi
          elif [ "$event" = "disabled" ]; then
            log "focus disabled from the dashboard settings"
            delay=60
          fi
          # Once the lock has been triggered the connection is closed right away
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
      log "connection closed"
    elif [ "$refused_logged" = 0 ] && [ ! -e "$lock_file" ]; then
      refused_logged=1
      log "connection failed: server unreachable, invalid key or focus disabled"
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
    echo "Usage: $0 install | uninstall | run | focus <session_id> | remote <session_...> | idle | status | unlock" >&2
    exit 2
    ;;
esac
