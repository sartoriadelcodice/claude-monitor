#!/usr/bin/env bash
# Installazione dell'hook di Claude Monitor su macOS e Linux.
#
# Questo file è un modello: la dashboard lo scarica, sostituisce i tre segnaposto qui sotto
# (URL, chiave hook e contenuto di claude-monitor-hook.sh) e lo offre come download.
# Cosa fa:
#   1. copia lo script dell'hook in ~/.claude/hooks/
#   2. salva una copia di ~/.claude/settings.json
#   3. aggiunge a settings.json le variabili e gli hook, senza toccare il resto
# Si può rieseguire: le voci di Claude Monitor già presenti vengono sostituite, non duplicate.

set -euo pipefail

MONITOR_URL='__CLAUDE_MONITOR_URL__'
MONITOR_TOKEN='__CLAUDE_MONITOR_TOKEN__'

claude_dir="$HOME/.claude"
hook_file="$claude_dir/hooks/claude-monitor-hook.sh"
settings_file="$claude_dir/settings.json"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Errore: serve python3 per modificare $settings_file." >&2
  echo "Installalo e riesegui lo script, oppure segui l'installazione manuale in hooks/README.md." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "Attenzione: curl non è installato. L'hook non potrà inviare eventi finché non lo installi." >&2
fi

mkdir -p "$claude_dir/hooks"
cat >"$hook_file" <<'CLAUDE_MONITOR_HOOK_EOF'
__CLAUDE_MONITOR_HOOK_SCRIPT__
CLAUDE_MONITOR_HOOK_EOF
chmod +x "$hook_file"
echo "Hook installato in $hook_file"

MONITOR_URL="$MONITOR_URL" MONITOR_TOKEN="$MONITOR_TOKEN" python3 - "$settings_file" <<'CLAUDE_MONITOR_PY_EOF'
import json
import os
import shutil
import sys
import tempfile
import time

# Se settings.json è un collegamento simbolico si modifica il file vero, senza sostituire il collegamento
path = os.path.realpath(sys.argv[1])
command = "~/.claude/hooks/claude-monitor-hook.sh"
marker = "claude-monitor-hook"
async_events = ["SessionStart", "UserPromptSubmit", "Notification", "PostToolUse", "PostModelSwitch", "Stop"]


def fail(message):
    print("Errore: " + message, file=sys.stderr)
    print("Il file non è stato modificato.", file=sys.stderr)
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
            fail("%s non è un JSON valido (%s)." % (path, error))
if not isinstance(settings, dict):
    fail("%s non contiene un oggetto JSON." % path)

env = settings.setdefault("env", {})
hooks = settings.setdefault("hooks", {})
if not isinstance(env, dict) or not isinstance(hooks, dict):
    fail('le sezioni "env" e "hooks" di %s non hanno la forma attesa.' % path)

env["CLAUDE_MONITOR_URL"] = os.environ["MONITOR_URL"]
env["CLAUDE_MONITOR_TOKEN"] = os.environ["MONITOR_TOKEN"]


def is_ours(hook):
    return isinstance(hook, dict) and marker in str(hook.get("command", ""))


def without_ours(groups):
    """Toglie le voci di Claude Monitor già presenti e lascia intatti gli altri hook."""
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
        fail('la sezione "hooks.%s" di %s non è un elenco.' % (event, path))
    entry = {"type": "command", "command": command}
    if event == "SessionEnd":
        # Alla chiusura un processo in background potrebbe non finire: sincrono, con timeout breve
        entry["timeout"] = 5
    else:
        entry["async"] = True
    hooks[event] = without_ours(groups) + [{"hooks": [entry]}]

if exists:
    backup = "%s.backup-%s" % (path, time.strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(path, backup)
    print("Copia di sicurezza: " + backup)

# Scrittura su file temporaneo e sostituzione: settings.json non resta mai a metà
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
print("Impostazioni aggiornate: " + path)
CLAUDE_MONITOR_PY_EOF

echo
echo "Fatto. Apri una nuova sessione di Claude Code: comparirà nella dashboard ($MONITOR_URL)."
echo "Questo script contiene la chiave hook: ora puoi eliminarlo."
