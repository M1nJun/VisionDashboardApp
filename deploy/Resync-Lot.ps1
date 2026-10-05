<#
.SYNOPSIS
Re-reads the current lot for one or more inspectors, so the dashboard matches the machine.

.DESCRIPTION
An agent's file position only ever moves forward. If the server refused rows - a stale
read position, an outage, a bug since fixed - the agent has already moved past them and
will not offer them again, so that lot stays short for the rest of its run. The next lot
change clears it by itself; this is for when you want the current one to be right now.

For each inspector it:
  1. clears the server's counters, judgement breakdown, rollups and read position for
     the lot that inspector is on,
  2. deletes state.json on the inspection PC and restarts the agent service.

The agent then cold-starts, seeks to the start of the lot that is running, and re-reads
it from the beginning. Defect rows are protected by the database's own unique key, so
re-reading cannot duplicate them; the counters are rebuilt from scratch.

Run it from the central PC - it needs SMB and sc.exe access to the inspection PCs, the
same as Deploy-Agent.ps1.

.PARAMETER Line
Only this line, e.g. "C-3". Omit for every line.

.PARAMETER VisionKey
Only this vision type, e.g. "EXAMPLE_A_ANODE". Omit for every one on the chosen lines.

.PARAMETER WhatIf
Show what would be done and change nothing.

.EXAMPLE
.\Resync-Lot.ps1 -Line C-3 -VisionKey EXAMPLE_A_ANODE

.EXAMPLE
.\Resync-Lot.ps1 -Line C-3

.EXAMPLE
.\Resync-Lot.ps1 -WhatIf
#>
[CmdletBinding()]
param(
    [string] $Line,
    [string] $VisionKey,
    [string] $MysqlExe = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
    [string] $Database = "visiondash",
    [string] $DbUser = "root",
    [string] $DbPassword,
    [switch] $WhatIf
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here

# sc.exe, net.exe and mysql.exe all write to stderr on perfectly ordinary conditions -
# mysql warns about the password on every single call. Under $ErrorActionPreference =
# 'Stop' PowerShell turns each of those lines into a terminating error, so the exit code
# is the only thing worth believing here.
function Invoke-Native {
    param([string] $Exe, [string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Exe @Arguments 2>&1
        # Merged for the message when something fails; stderr stripped for when the
        # output is a value we are going to use. mysql warns about the password on the
        # command line every single call, and that warning is not a lot id.
        $all = ($output | ForEach-Object { $_.ToString() }) -join "`n"
        $clean = ($output |
            Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } |
            ForEach-Object { $_.ToString() }) -join "`n"
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $all; StdOut = $clean }
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Read-Json($path) {
    if (-not (Test-Path $path)) { throw "Not found: $path" }
    return Get-Content $path -Raw | ConvertFrom-Json
}

# Works both from the repository and from the packaged folder copied onto the factory
# network, where everything sits side by side instead of in the source tree.
function Resolve-First([string[]] $candidates, [string] $what) {
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    throw "Cannot find $what. Looked in:`n  " + ($candidates -join "`n  ")
}

$config = Read-Json (Join-Path $here 'deploy.json')
$topology = Read-Json (Resolve-First @(
    (Join-Path $here 'contracts\topology.json'),
    (Join-Path $repo 'contracts\topology.json')) 'topology.json')

if (-not $DbPassword) {
    $secure = Read-Host "MySQL password for $DbUser" -AsSecureString
    $DbPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

function Invoke-Sql([string] $Statement) {
    $result = Invoke-Native $MysqlExe @("-u", $DbUser, "-p$DbPassword", "-N", "-B",
                                        $Database, "-e", $Statement)
    if ($result.ExitCode -ne 0) { throw "mysql failed: $($result.Output)" }
    return $result.StdOut
}

# Build the work list from the topology, so this can never target a slot that does not
# exist or miss one that does.
$targets = @()
foreach ($pc in $topology.pcs) {
    if ($Line -and $pc.line -ne $Line) { continue }
    foreach ($key in $pc.hosts) {
        if ($VisionKey -and $key -ne $VisionKey) { continue }
        $targets += [pscustomobject]@{
            AgentId = "$($pc.line)_$key"
            Line    = $pc.line
            Key     = $key
            Ip      = $pc.ip
        }
    }
}

if ($targets.Count -eq 0) {
    throw "Nothing matched. Line='$Line' VisionKey='$VisionKey'"
}

Write-Host ""
Write-Host "Resyncing $($targets.Count) inspector(s)$(if ($WhatIf) { ' - WhatIf, nothing will change' })" -ForegroundColor Cyan
Write-Host ""

$credential = $null
if (-not $WhatIf) {
    $credential = Get-Credential -Message "Local administrator on the inspection PCs" `
                                 -UserName $config.deployAccount
}

$done = 0
$failed = @()

foreach ($target in $targets) {
    $lot = (Invoke-Sql "SELECT COALESCE(lot_id,'') FROM lot_counters WHERE agent_id='$($target.AgentId)'").Trim()
    $counted = (Invoke-Sql "SELECT COALESCE(inspected_count,0) FROM lot_counters WHERE agent_id='$($target.AgentId)'").Trim()

    if (-not $lot) {
        Write-Host ("  {0,-28} no lot recorded - skipping" -f $target.AgentId) -ForegroundColor DarkGray
        continue
    }

    Write-Host ("  {0,-28} lot {1}, counted {2}" -f $target.AgentId, $lot, $counted) -ForegroundColor White
    if ($WhatIf) {
        Write-Host ("  {0,-28}   would clear the lot and re-read from {1}" -f "", $target.Ip) -ForegroundColor DarkGray
        continue
    }

    try {
        $share = "\\$($target.Ip)\$($config.installPath.Substring(0,1))`$$($config.installPath.Substring(2))"
        Invoke-Native "net.exe" @("use", $share, "/user:$($credential.UserName)",
                                  $credential.GetNetworkCredential().Password) | Out-Null

        # Stop the agent before clearing anything it writes to.
        #
        # Clearing first and stopping afterwards left the agent running through the
        # few seconds the clear and the file copy took - and it sends the whole time.
        # Its next batch put the read position straight back at the live unit number,
        # so when the service came up and re-read the lot from its first unit, every
        # one of them was refused as a replay:
        #
        #   lot change A-1_EXAMPLE_D: null -> LOTID053K1   <- agent, still running
        #   ingest A-1_EXAMPLE_D: accepted=1 replayed=499  <- 3s later, after restart
        #   offering unit 500 but already read to 8152
        #
        # The inspector then counted only what the machine made after the resync.
        $stop = Invoke-Native "sc.exe" @("\\$($target.Ip)", "stop", $config.serviceName)
        # Long enough for a batch already on its way to land before the clear runs.
        Start-Sleep -Seconds 3

        # Scoped to the inspector, not to one lot.
        #
        # It used to clear only the lot lot_counters named, which is the one thing a
        # resync cannot trust: you run this because that value is wrong. When a misread
        # LOT-ID had opened a phantom lot, the counters named the phantom, so the real
        # lot's read position survived the clear - and the re-read was then thrown away
        # as a replay, unit by unit, leaving the inspector counting from zero.
        #
        # Read positions and archived lots are both rebuilt from the CSV, so clearing
        # them costs nothing. The defect rows go too: they are written INSERT IGNORE, so
        # a row that stayed would keep the timestamp it was first given rather than the
        # one the re-read carries. Their items and images cascade with them.
        #
        # One statement, so an inspector cannot be left half cleared if this dies midway.
        $agent = $target.AgentId
        Invoke-Sql @"
START TRANSACTION;
DELETE FROM defect_occurrences      WHERE agent_id='$agent';
DELETE FROM lot_counter_judgement   WHERE agent_id='$agent';
DELETE FROM vision_rollup_judgement WHERE agent_id='$agent';
DELETE FROM vision_rollup           WHERE agent_id='$agent';
DELETE FROM agent_lot_progress      WHERE agent_id='$agent';
DELETE FROM lot_history             WHERE agent_id='$agent';
UPDATE lot_counters SET lot_id=NULL, lot_started_at=NULL,
       inspected_count=0, defect_unit_count=0, unknown_count=0
 WHERE agent_id='$agent';
COMMIT;
"@ | Out-Null


        $statePath = Join-Path $share 'state.json'
        if (Test-Path $statePath) { Remove-Item $statePath -Force }

        $start = Invoke-Native "sc.exe" @("\\$($target.Ip)", "start", $config.serviceName)
        if ($start.ExitCode -ne 0) {
            throw "service would not start: $($start.Output)"
        }

        Invoke-Native "net.exe" @("use", $share, "/delete") | Out-Null

        Write-Host ("  {0,-28}   cleared and re-reading" -f "") -ForegroundColor Green
        $done++
    }
    catch {
        Write-Host ("  {0,-28}   FAILED: {1}" -f "", $_.Exception.Message) -ForegroundColor Red
        $failed += $target.AgentId
    }
}

Write-Host ""
Write-Host "$($targets.Count) attempted, $done resynced$(if ($failed) { ", $($failed.Count) failed" })" `
    -ForegroundColor $(if ($failed) { 'Yellow' } else { 'Green' })
if ($failed) { $failed | ForEach-Object { Write-Host "  $_" -ForegroundColor Red } }
Write-Host ""
Write-Host "Counts climb back over the next minute or two as each agent re-reads its lot."
