# Claude Code hook for Claude Monitor (Windows).
# Reads the event JSON from stdin, adds machine and project and sends it to the backend.
# It must never block or break Claude Code: short timeout and it always exits with 0.
#
# Environment variables:
#   CLAUDE_MONITOR_URL      backend base URL, for example https://monitor.example.com
#   CLAUDE_MONITOR_TOKEN    Bearer token shared with the backend (HOOK_TOKEN)
#   CLAUDE_MONITOR_MACHINE  (optional) machine name, instead of the hostname

try {
    $url = $env:CLAUDE_MONITOR_URL
    $token = $env:CLAUDE_MONITOR_TOKEN
    $payload = [Console]::In.ReadToEnd()

    if ([string]::IsNullOrWhiteSpace($url) -or [string]::IsNullOrWhiteSpace($token) -or [string]::IsNullOrWhiteSpace($payload)) {
        exit 0
    }

    $data = $payload | ConvertFrom-Json

    $machine = $env:CLAUDE_MONITOR_MACHINE
    if ([string]::IsNullOrWhiteSpace($machine)) { $machine = $env:COMPUTERNAME }

    # The project is the last part of cwd
    $project = 'unknown'
    if ($data.cwd) {
        $leaf = Split-Path -Path ($data.cwd.TrimEnd('\', '/')) -Leaf
        if ($leaf) { $project = $leaf }
    }

    $data | Add-Member -NotePropertyName machine -NotePropertyValue $machine -Force
    $data | Add-Member -NotePropertyName project -NotePropertyValue $project -Force

    # The model comes with SessionStart only in some cases (never after /clear): when it is missing it is read
    # from the last response in the transcript. Only on Stop and PostToolUse, when the transcript is surely
    # up to date: on other events it could report the model from before a switch.
    if (-not $data.model -and $data.transcript_path -and ($data.hook_event_name -in 'Stop', 'PostToolUse') `
            -and (Test-Path -LiteralPath $data.transcript_path)) {
        $stream = [System.IO.File]::Open($data.transcript_path, 'Open', 'Read', 'ReadWrite')
        try {
            $length = [int][Math]::Min($stream.Length, 262144)
            $stream.Seek(-$length, 'End') | Out-Null
            $buffer = New-Object byte[] $length
            $read = $stream.Read($buffer, 0, $length)
            $tail = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $read)
        }
        finally { $stream.Dispose() }
        $found = [regex]::Matches($tail, '"model":"(claude-[^"]*)"')
        if ($found.Count -gt 0) {
            $data | Add-Member -NotePropertyName model -NotePropertyValue $found[$found.Count - 1].Groups[1].Value -Force
        }
    }

    # Claude Code Remote Control: the id of the remote session (session_...) is in
    # ~/.claude/sessions/<pid>.json, one file per Claude Code process. With it the dashboard opens the
    # session in the Claude app of the Mac in use. Read on every event: when Remote Control is off, the field is missing.
    $configDir = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.claude' }
    $sessionsDir = Join-Path $configDir 'sessions'
    if ($data.session_id -and (Test-Path -LiteralPath $sessionsDir)) {
        # If more than one file has the session (process not closed cleanly) the most recent one wins
        $files = Get-ChildItem -LiteralPath $sessionsDir -Filter '*.json' -File | Sort-Object LastWriteTime -Descending
        foreach ($file in $files) {
            try { $info = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json }
            catch { continue }
            if ($info.sessionId -ne $data.session_id) { continue }
            # It ends up in a link opened by another Mac: only the Claude Code format, never any other text
            if ($info.bridgeSessionId -cmatch '^(cse|session)_[A-Za-z0-9_-]{1,80}$') {
                $data | Add-Member -NotePropertyName remote_session_id -NotePropertyValue $info.bridgeSessionId -Force
            }
            break
        }
    }

    # Remove the bulky or private fields that the dashboard does not need
    foreach ($name in 'prompt', 'tool_input', 'tool_response', 'permission_suggestions', 'last_assistant_message') {
        $data.PSObject.Properties.Remove($name)
    }
    # For background tasks and scheduled wake-ups it is enough to know they exist: no commands or prompts
    if ($data.background_tasks) {
        $data.background_tasks = @($data.background_tasks | Select-Object id, type, status)
    }
    if ($data.session_crons) {
        $data.session_crons = @($data.session_crons | Select-Object id, schedule, recurring)
    }

    $body = [System.Text.Encoding]::UTF8.GetBytes(($data | ConvertTo-Json -Depth 20 -Compress))
    $headers = @{ Authorization = "Bearer $token" }

    Invoke-RestMethod -Uri ($url.TrimEnd('/') + '/api/events') -Method Post -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 3 | Out-Null
}
catch {
    # Backend unreachable or any other error: Claude Code must not notice
}
exit 0
