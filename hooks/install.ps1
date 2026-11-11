# Installazione dell'hook di Claude Monitor su Windows (PowerShell 5.1 o successivo).
#
# Questo file è un modello: la dashboard lo scarica, sostituisce i tre segnaposto qui sotto
# (URL, chiave hook e contenuto di claude-monitor-hook.ps1) e lo offre come download.
# Cosa fa:
#   1. copia lo script dell'hook in %USERPROFILE%\.claude\hooks\
#   2. salva una copia di %USERPROFILE%\.claude\settings.json
#   3. aggiunge a settings.json le variabili e gli hook, senza toccare il resto
# Si può rieseguire: le voci di Claude Monitor già presenti vengono sostituite, non duplicate.

$ErrorActionPreference = 'Stop'

$MonitorUrl = '__CLAUDE_MONITOR_URL__'
$MonitorToken = '__CLAUDE_MONITOR_TOKEN__'

$HookScript = @'
__CLAUDE_MONITOR_HOOK_SCRIPT__
'@

$claudeDir = Join-Path $HOME '.claude'
$hooksDir = Join-Path $claudeDir 'hooks'
$hookFile = Join-Path $hooksDir 'claude-monitor-hook.ps1'
$settingsFile = Join-Path $claudeDir 'settings.json'
$marker = 'claude-monitor-hook'
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$utf8Bom = New-Object System.Text.UTF8Encoding $true

function Stop-Install([string]$message) {
    Write-Host "Errore: $message" -ForegroundColor Red
    Write-Host 'Il file delle impostazioni non è stato modificato.'
    exit 1
}

# Trasforma il risultato di ConvertFrom-Json in dizionari ordinati ed elenchi, più semplici da modificare
function ConvertTo-Plain($value) {
    if ($null -eq $value) {
        return $null
    }
    if ($value -is [System.Management.Automation.PSCustomObject]) {
        $map = [ordered]@{}
        foreach ($property in $value.PSObject.Properties) {
            $map[$property.Name] = ConvertTo-Plain $property.Value
        }
        return $map
    }
    if ($value -is [System.Collections.IEnumerable] -and $value -isnot [string]) {
        $list = New-Object System.Collections.ArrayList
        foreach ($item in $value) {
            [void]$list.Add((ConvertTo-Plain $item))
        }
        # La virgola impedisce a PowerShell di srotolare l'elenco
        return , $list
    }
    return $value
}

function Test-Map($value) {
    return $value -is [System.Collections.IDictionary]
}

function Test-List($value) {
    return $value -is [System.Collections.IList]
}

# 1. Script dell'hook (con BOM, così PowerShell 5.1 lo legge come UTF-8)
New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null
[System.IO.File]::WriteAllText($hookFile, $HookScript, $utf8Bom)
Write-Host "Hook installato in $hookFile"

# 2. Lettura delle impostazioni attuali
$exists = Test-Path -LiteralPath $settingsFile
$settings = [ordered]@{}
if ($exists) {
    $raw = [System.IO.File]::ReadAllText($settingsFile)
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $settings = ConvertTo-Plain ($raw | ConvertFrom-Json)
        }
        catch {
            Stop-Install "$settingsFile non è un JSON valido ($($_.Exception.Message))."
        }
    }
}
if (-not (Test-Map $settings)) {
    Stop-Install "$settingsFile non contiene un oggetto JSON."
}
if (-not $settings.Contains('env')) { $settings['env'] = [ordered]@{} }
if (-not $settings.Contains('hooks')) { $settings['hooks'] = [ordered]@{} }
if (-not (Test-Map $settings['env']) -or -not (Test-Map $settings['hooks'])) {
    Stop-Install "le sezioni ""env"" e ""hooks"" di $settingsFile non hanno la forma attesa."
}

$settings['env']['CLAUDE_MONITOR_URL'] = $MonitorUrl
$settings['env']['CLAUDE_MONITOR_TOKEN'] = $MonitorToken

# Percorso assoluto con barre normali: funziona sia da cmd sia da Git Bash
$command = 'powershell -NoProfile -ExecutionPolicy Bypass -File "' + ($hookFile -replace '\\', '/') + '"'

foreach ($eventName in 'SessionStart', 'UserPromptSubmit', 'Notification', 'PostToolUse', 'PostModelSwitch', 'Stop', 'SessionEnd') {
    $groups = New-Object System.Collections.ArrayList
    if ($settings['hooks'].Contains($eventName)) {
        $current = $settings['hooks'][$eventName]
        if (-not (Test-List $current)) {
            Stop-Install "la sezione ""hooks.$eventName"" di $settingsFile non è un elenco."
        }
        # Si tolgono le voci di Claude Monitor già presenti e si lasciano intatti gli altri hook
        foreach ($group in $current) {
            if ((Test-Map $group) -and (Test-List $group['hooks'])) {
                $remaining = New-Object System.Collections.ArrayList
                foreach ($hook in $group['hooks']) {
                    $ours = (Test-Map $hook) -and ([string]$hook['command']).Contains($marker)
                    if (-not $ours) { [void]$remaining.Add($hook) }
                }
                if ($remaining.Count -eq 0 -and $group['hooks'].Count -gt 0) { continue }
                $group['hooks'] = $remaining
            }
            [void]$groups.Add($group)
        }
    }
    $entry = [ordered]@{ type = 'command'; command = $command }
    if ($eventName -eq 'SessionEnd') {
        # Alla chiusura un processo in background potrebbe non finire: sincrono, con timeout breve
        $entry['timeout'] = 5
    }
    else {
        $entry['async'] = $true
    }
    $entries = New-Object System.Collections.ArrayList
    [void]$entries.Add($entry)
    [void]$groups.Add([ordered]@{ hooks = $entries })
    $settings['hooks'][$eventName] = $groups
}

$json = $settings | ConvertTo-Json -Depth 100

# 3. Copia di sicurezza e scrittura
if ($exists) {
    $backup = "$settingsFile.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item -LiteralPath $settingsFile -Destination $backup
    Write-Host "Copia di sicurezza: $backup"
}
$temporary = "$settingsFile.tmp"
[System.IO.File]::WriteAllText($temporary, $json + "`n", $utf8NoBom)
Move-Item -LiteralPath $temporary -Destination $settingsFile -Force
Write-Host "Impostazioni aggiornate: $settingsFile"

Write-Host ''
Write-Host "Fatto. Apri una nuova sessione di Claude Code: comparirà nella dashboard ($MonitorUrl)."
Write-Host 'Questo script contiene la chiave hook: ora puoi eliminarlo.'
