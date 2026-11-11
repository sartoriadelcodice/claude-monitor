# Installs the Claude Monitor hook on Windows (PowerShell 5.1 or later).
#
# This file is a template: the dashboard downloads it, replaces the three placeholders below
# (URL, hook key and content of claude-monitor-hook.ps1) and offers it as a download.
# What it does:
#   1. copies the hook script to %USERPROFILE%\.claude\hooks\
#   2. saves a copy of %USERPROFILE%\.claude\settings.json
#   3. adds the variables and the hooks to settings.json, without touching anything else
# It can be run again: the Claude Monitor entries already present are replaced, not duplicated.

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
    Write-Host "Error: $message" -ForegroundColor Red
    Write-Host 'The settings file has not been modified.'
    exit 1
}

# Turns the result of ConvertFrom-Json into ordered dictionaries and lists, which are easier to modify
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
        # The comma stops PowerShell from unrolling the list
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

# 1. Hook script (with BOM, so that PowerShell 5.1 reads it as UTF-8)
New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null
[System.IO.File]::WriteAllText($hookFile, $HookScript, $utf8Bom)
Write-Host "Hook installed in $hookFile"

# 2. Read the current settings
$exists = Test-Path -LiteralPath $settingsFile
$settings = [ordered]@{}
if ($exists) {
    $raw = [System.IO.File]::ReadAllText($settingsFile)
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $settings = ConvertTo-Plain ($raw | ConvertFrom-Json)
        }
        catch {
            Stop-Install "$settingsFile is not valid JSON ($($_.Exception.Message))."
        }
    }
}
if (-not (Test-Map $settings)) {
    Stop-Install "$settingsFile does not contain a JSON object."
}
if (-not $settings.Contains('env')) { $settings['env'] = [ordered]@{} }
if (-not $settings.Contains('hooks')) { $settings['hooks'] = [ordered]@{} }
if (-not (Test-Map $settings['env']) -or -not (Test-Map $settings['hooks'])) {
    Stop-Install "the ""env"" and ""hooks"" sections of $settingsFile do not have the expected shape."
}

$settings['env']['CLAUDE_MONITOR_URL'] = $MonitorUrl
$settings['env']['CLAUDE_MONITOR_TOKEN'] = $MonitorToken

# Absolute path with forward slashes: works both from cmd and from Git Bash
$command = 'powershell -NoProfile -ExecutionPolicy Bypass -File "' + ($hookFile -replace '\\', '/') + '"'

foreach ($eventName in 'SessionStart', 'UserPromptSubmit', 'Notification', 'PostToolUse', 'PostModelSwitch', 'Stop', 'SessionEnd') {
    $groups = New-Object System.Collections.ArrayList
    if ($settings['hooks'].Contains($eventName)) {
        $current = $settings['hooks'][$eventName]
        if (-not (Test-List $current)) {
            Stop-Install "the ""hooks.$eventName"" section of $settingsFile is not a list."
        }
        # Remove the Claude Monitor entries already present and leave the other hooks untouched
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
        # On exit a background process might not finish: synchronous, with a short timeout
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

# 3. Backup copy and write
if ($exists) {
    $backup = "$settingsFile.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item -LiteralPath $settingsFile -Destination $backup
    Write-Host "Backup copy: $backup"
}
$temporary = "$settingsFile.tmp"
[System.IO.File]::WriteAllText($temporary, $json + "`n", $utf8NoBom)
Move-Item -LiteralPath $temporary -Destination $settingsFile -Force
Write-Host "Settings updated: $settingsFile"

Write-Host ''
Write-Host "Done. Open a new Claude Code session: it will appear in the dashboard ($MonitorUrl)."
Write-Host 'This script contains the hook key: you can delete it now.'
