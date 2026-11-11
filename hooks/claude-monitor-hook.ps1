# Hook di Claude Code per Claude Monitor (Windows).
# Legge il JSON dell'evento da stdin, aggiunge macchina e progetto e lo invia al backend.
# Non deve mai bloccare né far fallire Claude Code: timeout breve ed esce sempre con 0.
#
# Variabili d'ambiente:
#   CLAUDE_MONITOR_URL      URL base del backend, ad esempio https://monitor.example.com
#   CLAUDE_MONITOR_TOKEN    token Bearer condiviso con il backend (HOOK_TOKEN)
#   CLAUDE_MONITOR_MACHINE  (facoltativa) nome della macchina, al posto dell'hostname

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

    # Il progetto è l'ultima parte di cwd
    $project = 'sconosciuto'
    if ($data.cwd) {
        $leaf = Split-Path -Path ($data.cwd.TrimEnd('\', '/')) -Leaf
        if ($leaf) { $project = $leaf }
    }

    $data | Add-Member -NotePropertyName machine -NotePropertyValue $machine -Force
    $data | Add-Member -NotePropertyName project -NotePropertyValue $project -Force

    # Il modello arriva con SessionStart solo in alcuni casi (mai dopo /clear): quando manca si legge
    # dall'ultima risposta nel transcript. Solo su Stop e PostToolUse, quando il transcript è di sicuro
    # aggiornato: negli altri eventi potrebbe riportare il modello precedente a un cambio.
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

    # Controllo remoto (Remote Control) di Claude Code: l'identificativo della sessione remota (session_...) sta in
    # ~/.claude/sessions/<pid>.json, un file per ogni processo di Claude Code. Con quello la dashboard apre la
    # sessione nell'app Claude del Mac in uso. Si legge a ogni evento: spento il controllo remoto, il campo manca.
    $configDir = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.claude' }
    $sessionsDir = Join-Path $configDir 'sessions'
    if ($data.session_id -and (Test-Path -LiteralPath $sessionsDir)) {
        # Se più file hanno la sessione (processo chiuso male) vale il più recente
        $files = Get-ChildItem -LiteralPath $sessionsDir -Filter '*.json' -File | Sort-Object LastWriteTime -Descending
        foreach ($file in $files) {
            try { $info = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json }
            catch { continue }
            if ($info.sessionId -ne $data.session_id) { continue }
            # Finisce in un link aperto da un altro Mac: solo la forma di Claude Code, mai altro testo
            if ($info.bridgeSessionId -cmatch '^(cse|session)_[A-Za-z0-9_-]{1,80}$') {
                $data | Add-Member -NotePropertyName remote_session_id -NotePropertyValue $info.bridgeSessionId -Force
            }
            break
        }
    }

    # Si tolgono i campi voluminosi o riservati che alla dashboard non servono
    foreach ($name in 'prompt', 'tool_input', 'tool_response', 'permission_suggestions', 'last_assistant_message') {
        $data.PSObject.Properties.Remove($name)
    }
    # Dei task in background e dei risvegli pianificati basta sapere che esistono: niente comandi né prompt
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
    # Backend non raggiungibile o errore qualsiasi: Claude Code non deve accorgersene
}
exit 0
