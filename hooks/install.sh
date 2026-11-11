#!/usr/bin/env bash
# Installs the Claude Monitor hook on macOS and Linux.
#
# This file is a template: the dashboard downloads it, replaces the three placeholders below
# (URL, hook key and content of claude-monitor-hook.sh) and offers it as a download.
# What it does:
#   1. copies the hook script to ~/.claude/hooks/
#   2. saves a copy of ~/.claude/settings.json
#   3. adds the variables and the hooks to settings.json, without touching anything else
# It can be run again: the Claude Monitor entries already present are replaced, not duplicated.

set -euo pipefail

MONITOR_URL='__CLAUDE_MONITOR_URL__'
MONITOR_TOKEN='__CLAUDE_MONITOR_TOKEN__'

claude_dir="$HOME/.claude"
hook_file="$claude_dir/hooks/claude-monitor-hook.sh"
settings_file="$claude_dir/settings.json"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required to modify $settings_file." >&2
  echo "Install it and run the script again, or follow the manual installation in hooks/README.md." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "Warning: curl is not installed. The hook cannot send events until you install it." >&2
fi

mkdir -p "$claude_dir/hooks"
cat >"$hook_file" <<'CLAUDE_MONITOR_HOOK_EOF'
__CLAUDE_MONITOR_HOOK_SCRIPT__
CLAUDE_MONITOR_HOOK_EOF
chmod +x "$hook_file"
echo "Hook installed in $hook_file"

MONITOR_URL="$MONITOR_URL" MONITOR_TOKEN="$MONITOR_TOKEN" python3 - "$settings_file" <<'CLAUDE_MONITOR_PY_EOF'
import json
import os
import shutil
import sys
import tempfile
import time

# If settings.json is a symbolic link the real file is modified, without replacing the link
path = os.path.realpath(sys.argv[1])
command = "~/.claude/hooks/claude-monitor-hook.sh"
marker = "claude-monitor-hook"
async_events = ["SessionStart", "UserPromptSubmit", "Notification", "PostToolUse", "PostModelSwitch", "Stop"]


def fail(message):
    print("Error: " + message, file=sys.stderr)
    print("The file has not been modified.", file=sys.stderr)
    sys.exit(1)


exists = os.path.exists(path)
settings = {}
if exists:
    with open(path, encoding="utf-8-sig") as source:
        raw = source.read()
    if raw.strip():
        try:
            settings = json.loads(raw)
        except ValueError as error:
            fail("%s is not valid JSON (%s)." % (path, error))
if not isinstance(settings, dict):
    fail("%s does not contain a JSON object." % path)

env = settings.setdefault("env", {})
hooks = settings.setdefault("hooks", {})
if not isinstance(env, dict) or not isinstance(hooks, dict):
    fail('the "env" and "hooks" sections of %s do not have the expected shape.' % path)

env["CLAUDE_MONITOR_URL"] = os.environ["MONITOR_URL"]
env["CLAUDE_MONITOR_TOKEN"] = os.environ["MONITOR_TOKEN"]


def is_ours(hook):
    return isinstance(hook, dict) and marker in str(hook.get("command", ""))


def without_ours(groups):
    """Removes the Claude Monitor entries already present and leaves the other hooks untouched."""
    kept = []
    for group in groups:
        if isinstance(group, dict) and isinstance(group.get("hooks"), list):
            remaining = [hook for hook in group["hooks"] if not is_ours(hook)]
            if not remaining and group["hooks"]:
                continue
            group["hooks"] = remaining
        kept.append(group)
    return kept


for event in async_events + ["SessionEnd"]:
    groups = hooks.get(event, [])
    if not isinstance(groups, list):
        fail('the "hooks.%s" section of %s is not a list.' % (event, path))
    entry = {"type": "command", "command": command}
    if event == "SessionEnd":
        # On exit a background process might not finish: synchronous, with a short timeout
        entry["timeout"] = 5
    else:
        entry["async"] = True
    hooks[event] = without_ours(groups) + [{"hooks": [entry]}]

if exists:
    backup = "%s.backup-%s" % (path, time.strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(path, backup)
    print("Backup copy: " + backup)

# Write to a temporary file, then replace: settings.json is never left half-written
handle, temporary = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".settings-", suffix=".tmp")
try:
    with os.fdopen(handle, "w", encoding="utf-8") as target:
        json.dump(settings, target, indent=2, ensure_ascii=False)
        target.write("\n")
    if exists:
        shutil.copymode(path, temporary)
    os.replace(temporary, path)
except BaseException:
    if os.path.exists(temporary):
        os.unlink(temporary)
    raise
print("Settings updated: " + path)
CLAUDE_MONITOR_PY_EOF

echo
echo "Done. Open a new Claude Code session: it will appear in the dashboard ($MONITOR_URL)."
echo "This script contains the hook key: you can delete it now."
