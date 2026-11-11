# Claude Code hook for Claude Monitor

The hook script sends the events of every Claude Code session on the machine to the backend.
It must be installed on every machine you want to monitor.

## Requirements

- macOS or Linux: `bash` and `curl`. `jq` is recommended; without `jq` the script still works,
  but it sends the full payload, including prompts and tool input.
- Windows: PowerShell 5.1 or later.

## Automatic installation

Right after you create a hook key, the dashboard offers a script that does everything on its own.
The platform is suggested based on the browser and can be changed.

- macOS and Linux: `bash claude-monitor-install.sh` (requires `python3`).
- Windows: `powershell -ExecutionPolicy Bypass -File .\claude-monitor-install.ps1`.

The script copies the hook to `~/.claude/hooks/`, saves a copy of `~/.claude/settings.json` as
`settings.json.backup-<date>-<time>` and then adds the variables and the hooks, leaving everything else untouched.
It can be run again: the Claude Monitor entries already present are replaced. If `settings.json` is not
valid JSON, the script stops without modifying it. The script contains the key in plain text: delete it after use.

The templates are `install.sh` and `install.ps1` in this folder: the dashboard fills in the URL, the key and the
hook script. Do not run them as they are.

## Manual installation on macOS and Linux

1. Copy the script and make it executable:

   ```bash
   mkdir -p ~/.claude/hooks
   cp hooks/claude-monitor-hook.sh ~/.claude/hooks/
   chmod +x ~/.claude/hooks/claude-monitor-hook.sh
   ```

2. Open `~/.claude/settings.json` and merge in the content of `settings.example.json`:
   the `env` section with URL and token, and the `hooks` section.
   If you already have other hooks on the same events, add the block to the existing array instead of replacing it.
   The session name appears in the dashboard only if you gave the session one with `--name` or `/rename`:
   Claude Code does not pass automatically generated names to hooks.

3. Set the values:

   | Variable                 | Value                                                                       |
   | ------------------------ | --------------------------------------------------------------------------- |
   | `CLAUDE_MONITOR_URL`     | Public URL of the dashboard, without a trailing `/api`.                     |
   | `CLAUDE_MONITOR_TOKEN`   | A hook key created from the dashboard (the key button at the top).          |
   | `CLAUDE_MONITOR_MACHINE` | Optional: name to show instead of the hostname.                             |

   A hook key is shown only when it is created. It is best to create one per machine: sessions appear to the
   user who created the key, and each key can be revoked on its own.

4. Open a new Claude Code session: its card appears in the dashboard.

## Manual installation on Windows

1. Copy `claude-monitor-hook.ps1` to `%USERPROFILE%\.claude\hooks\`.
2. In `%USERPROFILE%\.claude\settings.json` use the same structure as `settings.example.json`,
   replacing every command with:

   ```
   powershell -NoProfile -ExecutionPolicy Bypass -File "%USERPROFILE%\.claude\hooks\claude-monitor-hook.ps1"
   ```

## Updating

The dashboard publishes the updated scripts under `/hooks`. To update a machine that is already connected, just
download the script again; the key and `settings.json` do not change. The ready-made command, with the address
already filled in, is at the bottom of the "Hook keys" dialog in the dashboard. On Linux and macOS:

```bash
curl -fsS -o ~/.claude/hooks/claude-monitor-hook.sh 'https://monitor.example.com/hooks/claude-monitor-hook.sh' \
  && chmod +x ~/.claude/hooks/claude-monitor-hook.sh
```

## Window focus (macOS, optional)

With the agent running, the cards of the sessions on that Mac show a button that brings the Claude Code window
to the front. Installing the hook does not start the agent: it must be enabled by hand on each Mac. The
ready-made command is in the "Hook keys" dialog of the dashboard:

```bash
curl -fsS -o ~/.claude/hooks/claude-monitor-agent.sh 'https://monitor.example.com/hooks/claude-monitor-agent.sh' \
  && bash ~/.claude/hooks/claude-monitor-agent.sh install
```

### How it works

- On macOS the hook records in `~/.claude/claude-monitor/windows/<session_id>` the application hosting the
  session, its terminal and the project folder. The file stays on the machine and is never sent;
  it is written on `SessionStart` (or on the first event, if missing) and deleted on `SessionEnd`.
- `claude-monitor-agent.sh install` registers a LaunchAgent (`it.sartoriadelcodice.claude-monitor-agent`)
  that stays connected to `GET /api/agent/stream` with the hook key. It reads URL, key and machine name from
  the `env` section of `~/.claude/settings.json`: the launchd file contains no secrets.
- The backend only sends the session id. The agent checks it, reads the local file and:
  - Terminal and iTerm: selects the tab with that terminal and brings the window to the front (AppleScript);
  - VS Code, VSCodium and Cursor: opens the project folder, which brings forward the window that already has it
    open (if no window has that folder, a new one opens);
  - other applications, or tab not found (for example inside tmux): brings the application to the front.
- The agent does not execute commands received from the network and does not send keystrokes. On first use
  macOS asks to allow control of Terminal: accept it.

### Agent commands

All commands are run as `bash ~/.claude/hooks/claude-monitor-agent.sh <command>`.

| Command                | Effect                                                                           |
| ---------------------- | -------------------------------------------------------------------------------- |
| `install`              | Registers the agent in launchd (starts now and at every login). Also updates it. |
| `uninstall`            | Stops and removes the agent.                                                     |
| `status`               | Tells whether the agent is registered and whether it is locked.                  |
| `unlock`               | Removes the lock triggered by anomalous requests.                                |
| `focus <session_id>`   | Local test, without the backend: brings the window of a session to the front.    |
| `remote <session_...>` | Local test: opens a Remote Control session in the Claude app.                    |
| `idle`                 | Local test: seconds since keyboard and mouse were last used.                     |

Log: `~/Library/Logs/claude-monitor-agent.log`.

### Remote Control sessions

With Claude Code Remote Control enabled (`/remote-control`, or `remoteControlAtStartup` in the settings), a
session running on one Mac can be followed from the Claude app on another. "Open window" opens it on the Mac
you are using:

- The hook reads the Remote Control id (`bridgeSessionId`, such as `session_...`) from the process file in
  `~/.claude/sessions/<pid>.json` and sends it with every event as `remote_session_id`. If Remote Control is
  turned off, the field is missing and the dashboard forgets it. This also works on Linux and Windows.
- When it connects, the agent declares whether the Claude app is on the Mac (`com.anthropic.claudefordesktop` in
  `/Applications` or `~/Applications`).
- When "Open window" is tapped, the backend asks the agents involved (the session's Mac and those with the
  Claude app) how many seconds keyboard and mouse have been idle, waits a second and a half for the answers and
  picks the Mac used most recently. If it is the session's Mac, the window is brought to the front as usual;
  otherwise the agent of that Mac opens the session in the Claude app with `claude://code/<id>`. On a tie the
  session's Mac wins. The button says where the session was opened.
- If the session's machine has no agent (for example Linux or Windows), the button is called
  "Open in Claude" and the session opens in the Claude app of a Mac with the agent.
- The agent reports its idle time only when the backend asks for it, that is on every tap on
  "Open window", and sends nothing else. Local tests: `bash ~/.claude/hooks/claude-monitor-agent.sh idle` and
  `bash ~/.claude/hooks/claude-monitor-agent.sh remote <session_...>`.
- After updating, reinstall the agent on every Mac (same command as for the installation): an agent from a
  previous version does not answer the requests and does not open the Claude app.

Sessions that are already open become reachable from the first event after the hook is updated.
Linux and Windows have no agent: their sessions open only in the Claude app of a Mac, and only if they have
Remote Control enabled.

### Protections

- **https only**: the agent does not connect to an `http` address, except towards the machine itself.
- **Anomalous requests**: each one is worth some points, added up over ten minutes. A malformed id
  (of a session, of Remote Control or of an idle-time request) is worth 10 points; a session the Mac does not
  know, a remote session on a Mac without the Claude app, a focus less than two seconds after the previous one
  or an idle-time request in the same second as the previous one are worth 1 point. At 10 points, beyond 60
  focus actions executed in an hour or beyond 120 idle-time requests in an hour, the agent locks itself.
- **Links built locally**: for Remote Control the backend only sends the id (`session_` or `cse_` followed by
  letters, digits, `_` and `-`); the agent builds the `claude://code/...` link itself and opens it only with
  the Claude app.
- **Lock**: the agent closes the connection, shows a notification, writes the reason to
  `~/.claude/claude-monitor/agent.locked` and does not reconnect. In the dashboard the focus button disappears.
  The lock cannot be removed from the network: after reading the log, run
  `bash ~/.claude/hooks/claude-monitor-agent.sh unlock` on the Mac. `status` shows the current state.
- **Switch in the dashboard**: in Settings, "Window focus" turns the feature off for all the user's machines
  and sessions. The backend disconnects the agents and refuses their new connections; the agent retries every
  minute and starts again on its own when the switch is turned back on.

The thresholds are at the top of the script (`suspect_limit`, `suspect_window`, `hourly_limit`,
`probe_hourly_limit`, `min_interval`).

## Manual test

```bash
export CLAUDE_MONITOR_URL=http://localhost:8088
export CLAUDE_MONITOR_TOKEN=cmk_your-hook-key
echo '{"session_id":"test-1","hook_event_name":"Notification","cwd":"/tmp/test","notification_type":"permission_prompt","message":"Claude needs your permission"}' \
  | hooks/claude-monitor-hook.sh
```

## Events and states

| Event              | Resulting state                                                          |
| ------------------ | ------------------------------------------------------------------------ |
| `SessionStart`     | `IDLE` (unchanged if `source` is `compact`)                              |
| `UserPromptSubmit` | `WORKING`                                                                |
| `Notification`     | `NEEDS_ATTENTION` for `permission_prompt` and `elicitation_dialog`; `WAITING_INPUT` for `idle_prompt` if the session was `WORKING`; other types are only kept in the history |
| `PostToolUse`      | `WORKING` if the session was `NEEDS_ATTENTION`, `IDLE` or `STALE`, or `BACKGROUND` and the tool does not belong to a subagent |
| `Stop`             | `BACKGROUND` if `background_tasks` or `session_crons` are not empty, otherwise `WAITING_INPUT` |
| `PostModelSwitch`  | No state change: updates the model shown on the card                     |
| `SessionEnd`       | `ENDED`, and the session is archived                                     |

## Behaviour

- Data sent: session id and name, folder, machine, event type, text of the Claude Code notifications, model,
  and the Remote Control id if enabled. With `jq` installed (and always on Windows) the script removes, before
  sending, prompts, Claude's responses, tool input and output, background task commands and the prompts of
  scheduled wake-ups. Without `jq` the full payload is sent and the backend discards those fields before
  storing it: this is why `jq` is recommended.
- The model comes with `SessionStart` only in some cases (never after `/clear`). When it is missing, on `Stop`
  and `PostToolUse` the script reads it from the last response in the transcript and adds it to the payload as
  `model`.
- The hooks are declared `async`: they run in the background and do not slow Claude Code down.
  `SessionEnd` is synchronous with a 5-second timeout, because on exit a background process might not finish.
- The script has a 3-second network timeout and always exits with code 0:
  if the backend does not respond, Claude Code shows no errors.
- Without `CLAUDE_MONITOR_URL` or `CLAUDE_MONITOR_TOKEN` the script does nothing.
- With a revoked or unknown key the backend responds with 401 and the script ignores it:
  the machine simply stops appearing in the dashboard.
