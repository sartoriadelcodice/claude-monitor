#!/usr/bin/env bash
# Sends fake Claude Code hook events to a Claude Monitor instance, to try the dashboard, develop the
# frontend or take screenshots without real sessions. All projects, machines and titles are made up.
#
# Usage: scripts/demo-events.sh <dashboard-url> <hook-key> [seed|live|end]
#   seed  creates sessions in every state (default)
#   live  loops through state changes, to watch alerts, sounds and highlights (Ctrl+C to stop)
#   end   ends all the demo sessions, which are then archived
#
# The hook key is created from the dashboard (key button at the top). Requires curl.

set -euo pipefail

if [[ $# -lt 2 ]]; then
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

URL="${1%/}"
KEY="$2"
SCENARIO="${3:-seed}"

# id|machine|project|title|model|remote control id
SESSIONS=(
  "demo-01|studio-mac|aurora-api|Add rate limiting to the public API|claude-opus-5-5|session_demo01"
  "demo-02|studio-mac|checkout-web|Fix flaky checkout e2e tests|claude-sonnet-5-5|"
  "demo-03|build-server|data-pipeline|Backfill March events|claude-sonnet-5-5|"
  "demo-04|macbook-air|mobile-app|Dark mode for the settings screen|claude-opus-5-5|session_demo04"
  "demo-05|build-server|infra-terraform|Upgrade the Kubernetes module|claude-opus-5-5|"
  "demo-06|macbook-air|docs-site||claude-haiku-5-5|"
  "demo-07|win-laptop|design-system|Color tokens for the new palette|claude-sonnet-5-5|"
  "demo-08|macbook-air|aurora-api|Review the orders table migration|claude-opus-5-5|"
)

field() {
  local id="$1" index="$2" row
  for row in "${SESSIONS[@]}"; do
    if [[ "${row%%|*}" == "$id" ]]; then
      cut -d'|' -f"$index" <<<"$row"
      return
    fi
  done
}

# send <session id> <event name> [extra JSON fields, starting with a comma]
send() {
  local id="$1" event="$2" extra="${3:-}"
  local machine project title model remote body
  machine="$(field "$id" 2)"
  project="$(field "$id" 3)"
  title="$(field "$id" 4)"
  model="$(field "$id" 5)"
  remote="$(field "$id" 6)"
  body="{\"session_id\":\"$id\",\"hook_event_name\":\"$event\",\"machine\":\"$machine\",\"project\":\"$project\""
  body+=",\"cwd\":\"/home/dev/work/$project\",\"model\":\"$model\""
  [[ -n "$title" ]] && body+=",\"session_title\":\"$title\""
  [[ -n "$remote" ]] && body+=",\"remote_session_id\":\"$remote\""
  body+="$extra}"
  curl -fsS -o /dev/null -X POST "$URL/api/events" \
    -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' --data "$body"
}

start() { send "$1" SessionStart ',"source":"startup"'; }
working() { send "$1" UserPromptSubmit; send "$1" PostToolUse ',"tool_name":"Edit"'; }
permission() { send "$1" Notification ",\"notification_type\":\"permission_prompt\",\"message\":\"$2\""; }
question() { send "$1" Notification ",\"notification_type\":\"elicitation_dialog\",\"message\":\"$2\""; }
your_turn() { send "$1" Stop; }
background() { send "$1" Stop ',"background_tasks":[{"id":"bg-1","type":"shell","status":"running"}]'; }

seed() {
  local row
  for row in "${SESSIONS[@]}"; do
    start "${row%%|*}"
  done
  working demo-01
  working demo-05
  working demo-07
  working demo-03 && background demo-03
  working demo-04 && your_turn demo-04
  working demo-02 && permission demo-02 'Claude needs your permission to use Bash'
  working demo-08 && question demo-08 'Claude has a question about the migration rollback'
  echo "Demo sessions created on $URL"
}

live() {
  echo "Looping through state changes on $URL (Ctrl+C to stop)"
  while true; do
    working demo-02; sleep 6
    permission demo-02 'Claude needs your permission to use Bash'; sleep 8
    working demo-07; your_turn demo-07; sleep 8
    working demo-02; sleep 6
    working demo-04; sleep 4
    question demo-04 'Claude wants to know which icon set to use'; sleep 8
    working demo-04; working demo-07; sleep 6
    your_turn demo-02; sleep 8
  done
}

end_all() {
  local row
  for row in "${SESSIONS[@]}"; do
    send "${row%%|*}" SessionEnd ',"reason":"exit"'
  done
  echo "Demo sessions ended"
}

case "$SCENARIO" in
  seed) seed ;;
  live) live ;;
  end) end_all ;;
  *) echo "Unknown scenario: $SCENARIO (use seed, live or end)" >&2; exit 2 ;;
esac
