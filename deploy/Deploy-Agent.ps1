<#
.SYNOPSIS
Installs or updates VisionAgent on the inspection PCs.

.DESCRIPTION
Targets come from contracts/topology.json, so this script never carries its own list of
PCs or its own idea of which vision types a PC runs. Each PC gets three files: the agent
exe, the shared catalog, and a small agent.json generated from its topology entry - there
are no per-vision-type templates to render, which is where the old deployer's typos used
to enter the system.

The service is always left running. A stopped agent silently stops collecting, and the
only symptom is a cell that quietly goes grey hours later, so this script restarts the
service even when a step in the middle fails.

.PARAMETER Line
Only deploy to these lines (e.g. C-2). Omit for every line in the topology.

.PARAMETER VisionKey
Only deploy to PCs hosting these vision keys.

.PARAMETER Ip
Only deploy to these addresses.

.PARAMETER Status
Report what is installed and running, and change nothing.

.PARAMETER DryRun
Show the targets and the exact agent.json each would receive, and touch no PC.

.EXAMPLE
.\Deploy-Agent.ps1 -Status

.EXAMPLE
.\Deploy-Agent.ps1 -Line C-2 -DryRun

.EXAMPLE
.\Deploy-Agent.ps1
#>
[CmdletBinding()]
param(
    [string[]] $Line,
    [string[]] $VisionKey,
    [string[]] $Ip,
    [switch] $Status,
    [switch] $DryRun,
    [string] $Emit,
    [System.Management.Automation.PSCredential] $Credential
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here

# Works both from the repository and from the packaged folder copied onto the factory
# network, where everything sits side by side instead of in the source tree.
function Resolve-First([string[]] $candidates, [string] $what) {
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    throw "Cannot find $what. Looked in:`n  " + ($candidates -join "`n  ")
}

$config = Get-Content -Raw (Join-Path $here 'deploy.json') | ConvertFrom-Json
$topologyPath = Resolve-First @(
    (Join-Path $here 'contracts\topology.json'),
    (Join-Path $repo 'contracts\topology.json')) 'topology.json'
$catalogPath = Resolve-First @(
    (Join-Path $here 'contracts\vision-catalog.json'),
    (Join-Path $repo 'contracts\vision-catalog.json')) 'vision-catalog.json'
$topology = Get-Content -Raw $topologyPath | ConvertFrom-Json

$exeCandidates = @(
    (Join-Path $here 'VisionAgent.exe'),
    (Join-Path $repo 'agent\bin\Release\net8.0\win-x64\publish\VisionAgent.exe'))
$exePath = $exeCandidates[0]
foreach ($candidate in $exeCandidates) { if (Test-Path $candidate) { $exePath = $candidate; break } }

function Get-Targets {
    $targets = @()
    foreach ($pc in $topology.pcs) {
        if ($Line -and ($Line -notcontains $pc.line)) { continue }
        if ($Ip -and ($Ip -notcontains $pc.ip)) { continue }
        if ($VisionKey) {
            $hit = $false
            foreach ($key in $pc.hosts) { if ($VisionKey -contains $key) { $hit = $true } }
            if (-not $hit) { continue }
        }
        $targets += [PSCustomObject]@{
            Ip         = $pc.ip
            Line       = $pc.line
            VisionKeys = @($pc.hosts)
            # Derived, never configured: the server rejects a batch whose agentId does not
            # match the slot it claims, so there is nothing here to get out of step.
            AgentIds   = @($pc.hosts | ForEach-Object { "$($pc.line)_$_" })
        }
    }
    return $targets
}

function New-AgentConfig([object] $target) {
    $eventsUrl = "http://$($config.centralHost):$($config.serverPort)$($config.contextPath)/api/ingest/events"
    return [ordered]@{
        line           = $target.Line
        visionKeys     = @($target.VisionKeys)
        modelToken     = $config.modelToken
        pollIntervalMs = $config.pollIntervalMs
        server         = [ordered]@{
            heartbeatHost       = $config.centralHost
            heartbeatPort       = $config.heartbeatPort
            heartbeatIntervalMs = 2000
            eventsUrl           = $eventsUrl
            timeoutSeconds      = 10
        }
        dryRun         = $false
    }
}

# Windows PowerShell's -Encoding UTF8 writes a byte-order mark. Most readers cope, but a
# config file with invisible leading bytes is a nasty thing to debug over SMB at 3am.
function Write-Utf8NoBom([string] $path, [string] $text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-RemoteRoot([string] $ip) {
    # The install path is local to the inspection PC; reaching it needs the drive's share.
    $drive = $config.installPath.Substring(0, 1)
    $rest = $config.installPath.Substring(2)
    return "\\$ip\$drive$rest"
}

# $ErrorActionPreference = 'Stop' is right for the cmdlets below, but it also turns a
# native command's stderr into a thrown exception. net.exe and sc.exe both write to
# stderr in situations that are perfectly normal here ("there is no such connection to
# delete", "the service does not exist"), so their calls run with it relaxed.
function Invoke-Native([scriptblock] $block) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $block } finally { $ErrorActionPreference = $previous }
}

function Invoke-Sc([string] $ip, [string[]] $scArgs) {
    $output = Invoke-Native { & sc.exe "\\$ip" @scArgs 2>&1 }
    return [PSCustomObject]@{ ExitCode = $LASTEXITCODE; Output = ($output -join "`n") }
}

# Keeps sc.exe's own words alongside the state. Collapsing every failure into
# "UNREACHABLE" hid whether the cause was a password, a firewall, or RPC - which is the
# difference between a two-minute fix and an afternoon.
$script:LastServiceError = ''

function Get-ServiceState([string] $ip) {
    $result = Invoke-Sc $ip @('query', $config.serviceName)
    $script:LastServiceError = ''
    if ($result.ExitCode -ne 0) {
        if ($result.Output -match '1060') { return 'NOT_INSTALLED' }
        $script:LastServiceError = ($result.Output -replace '\s+', ' ').Trim()
        return 'UNREACHABLE'
    }
    if ($result.Output -match 'RUNNING') { return 'RUNNING' }
    if ($result.Output -match 'STOPPED') { return 'STOPPED' }
    if ($result.Output -match 'START_PENDING') { return 'STARTING' }
    return 'UNKNOWN'
}

# The message sc.exe returns says which of the prerequisites is actually missing.
function Get-UnreachableHint([string] $message) {
    if ($message -match '1326|1327|1331|logon failure|password') {
        return 'wrong password, or the VisionDeploy account differs on this PC (C-1)'
    }
    if ($message -match '\b5\b|Access is denied') {
        return 'LocalAccountTokenFilterPolicy is not set to 1 on this PC (C-2)'
    }
    if ($message -match '1722|RPC server is unavailable|53|network path was not found|1231') {
        return 'the PC is not reachable, or File and Printer Sharing is blocked (C-3)'
    }
    if ($message -match '1219|multiple connections') {
        return 'another session to this PC exists under different credentials - run: net use \\<ip>\IPC$ /delete'
    }
    return 'check the local admin account (C-1), LocalAccountTokenFilterPolicy (C-2) and the firewall (C-3)'
}

function Start-AgentService([string] $ip) {
    $state = Get-ServiceState $ip
    if ($state -eq 'RUNNING') { return $true }
    if ($state -eq 'NOT_INSTALLED') { return $false }
    $null = Invoke-Sc $ip @('start', $config.serviceName)
    Start-Sleep -Milliseconds 1500
    return (Get-ServiceState $ip) -eq 'RUNNING'
}

function Connect-Share([string] $ip) {
    if (-not $Credential) { return }
    # One authenticated session per PC; the copies below then ride on it. Any existing
    # session is dropped first - Windows refuses a second session to the same host under
    # different credentials - and "there was no session" is a normal outcome, not a failure.
    Invoke-Native {
        $null = & net.exe use "\\$ip\IPC$" /delete /y 2>&1
        $user = $Credential.UserName
        $plain = $Credential.GetNetworkCredential().Password
        $null = & net.exe use "\\$ip\IPC$" $plain /user:"$ip\$user" 2>&1
    }
}

function Deploy-One([object] $target) {
    $result = [PSCustomObject]@{
        Ip       = $target.Ip
        Line     = $target.Line
        Agents   = ($target.AgentIds -join ', ')
        Before   = ''
        Action   = ''
        After    = ''
        Cleaned  = $false
        Ok       = $false
        Note     = ''
    }

    $stopped = $false
    try {
        Connect-Share $target.Ip
        $result.Before = Get-ServiceState $target.Ip
        if ($result.Before -eq 'UNREACHABLE') {
            $message = $script:LastServiceError
            $result.Note = "$(Get-UnreachableHint $message)  [sc.exe: $message]"
            return $result
        }

        $root = Get-RemoteRoot $target.Ip
        if (-not (Test-Path $root)) {
            New-Item -ItemType Directory -Path $root -Force | Out-Null
        }

        # The exe cannot be overwritten while the service holds it open.
        if ($result.Before -eq 'RUNNING') {
            $null = Invoke-Sc $target.Ip @('stop', $config.serviceName)
            Start-Sleep -Milliseconds 1500
            $stopped = $true
        }

        Copy-Item $exePath (Join-Path $root 'VisionAgent.exe') -Force
        Copy-Item $catalogPath (Join-Path $root 'vision-catalog.json') -Force
        $json = New-AgentConfig $target | ConvertTo-Json -Depth 5
        Write-Utf8NoBom (Join-Path $root 'agent.json') $json

        # No argument: the agent falls back to agent.json beside its own exe. That keeps
        # binPath free of spaces and quotes, which is worth doing deliberately - passing a
        # quoted path through PowerShell to sc.exe is a well-known way to lose the quotes.
        $binPath = Join-Path $config.installPath 'VisionAgent.exe'

        # Each 'name=' and its value are separate arguments. sc.exe reads the value from
        # the following argument, so 'start= auto' arriving as one token is rejected
        # outright ("Invalid start= field").
        $serviceArgs = @('binPath=', $binPath, 'start=', 'auto', 'obj=', 'LocalSystem')

        if ($result.Before -eq 'NOT_INSTALLED') {
            $create = Invoke-Sc $target.Ip (@('create', $config.serviceName) + $serviceArgs)
            if ($create.ExitCode -ne 0) {
                $result.Note = "could not register the service: $($create.Output)"
                return $result
            }
            $result.Action = 'installed'
        }
        else {
            # Always rewritten, never assumed. A service left over from the previous
            # system carries the same name and the same install folder but launches a
            # different exe, so copying new files beside it would change nothing at all.
            $reconfigure = Invoke-Sc $target.Ip (@('config', $config.serviceName) + $serviceArgs)
            if ($reconfigure.ExitCode -ne 0) {
                $result.Note = "could not repoint the service: $($reconfigure.Output)"
                return $result
            }
            $result.Action = 'repointed'
        }

        # Only once the service is pointed at the new exe. Deleting the old binary first
        # would leave a service whose binPath names a file that no longer exists, so a
        # failure anywhere above would also take away the ability to start what was there.
        foreach ($stale in @('WeldingCsvAgent.exe', 'personality.json', 'state.json')) {
            $path = Join-Path $root $stale
            if (Test-Path $path) {
                Remove-Item $path -Force -ErrorAction SilentlyContinue
                $result.Cleaned = $true
            }
        }

        $result.Ok = $true
        return $result
    }
    catch {
        $result.Note = $_.Exception.Message
        return $result
    }
    finally {
        # Always, including when something above threw. Leaving an agent stopped is the
        # one outcome with no visible symptom until data has already gone missing.
        if (-not $DryRun) {
            $running = Start-AgentService $target.Ip
            $result.After = Get-ServiceState $target.Ip
            if (-not $running -and -not $result.Note) {
                $result.Ok = $false
                $result.Note = 'service did not come back up'
            }
            if ($stopped -and -not $running) {
                Write-Warning "$($target.Ip): stopped for the update and did not restart"
            }
        }
    }
}

# ---- run ------------------------------------------------------------------

$targets = @(Get-Targets)
if ($targets.Count -eq 0) {
    Write-Warning 'No PC in the topology matches those filters.'
    return
}

if ($Status) {
    $rows = @(foreach ($target in $targets) {
        Connect-Share $target.Ip
        [PSCustomObject]@{
            Line   = $target.Line
            Ip     = $target.Ip
            Agents = ($target.AgentIds -join ', ')
            State  = Get-ServiceState $target.Ip
        }
    })
    @($rows) | Sort-Object Line, Ip | Format-Table -AutoSize
    $down = @($rows | Where-Object { $_.State -ne 'RUNNING' })
    Write-Host ""
    Write-Host "$($rows.Count) PC(s), $($down.Count) not running." -ForegroundColor $(if ($down.Count) { 'Yellow' } else { 'Green' })
    return
}

if ($Emit) {
    # Writes what each PC would receive to a local folder. Useful before a big rollout,
    # and it is what the deploy test feeds to a real agent to prove the generated config
    # is one the agent actually accepts.
    if (-not (Test-Path $Emit)) { New-Item -ItemType Directory -Path $Emit -Force | Out-Null }
    foreach ($target in $targets) {
        $file = Join-Path $Emit "$($target.Line)_$($target.Ip).json"
        Write-Utf8NoBom $file (New-AgentConfig $target | ConvertTo-Json -Depth 5)
    }
    Write-Host "Wrote $($targets.Count) agent config(s) to $Emit"
    return
}

if ($DryRun) {
    Write-Host "Dry run - nothing is copied and no service is touched.`n" -ForegroundColor Cyan
    foreach ($target in $targets) {
        Write-Host "$($target.Line)  $($target.Ip)  ->  $($target.AgentIds -join ', ')" -ForegroundColor White
        Write-Host ((New-AgentConfig $target | ConvertTo-Json -Depth 5) -replace '(?m)^', '    ')
        Write-Host ""
    }
    Write-Host "$($targets.Count) PC(s) would be updated."
    return
}

foreach ($required in @($exePath, $catalogPath)) {
    if (-not (Test-Path $required)) {
        throw "Missing $required. Build the agent first: cd agent; dotnet publish -c Release"
    }
}

if (-not $Credential) {
    Write-Host "Local administrator on the inspection PCs (account: $($config.deployAccount))"
    $Credential = Get-Credential -UserName $config.deployAccount -Message 'Inspection PC local administrator'
}

Write-Host "Deploying to $($targets.Count) PC(s)...`n"
$results = @(foreach ($target in $targets) { Deploy-One $target })

$results | Format-Table Line, Ip, Agents, Before, Action, After, Cleaned, Ok -AutoSize

$failed = @($results | Where-Object { -not $_.Ok })
foreach ($failure in $failed) {
    Write-Host "$($failure.Ip): $($failure.Note)" -ForegroundColor Red
}

$stoppedAfter = @($results | Where-Object { $_.After -ne 'RUNNING' })
Write-Host ""
Write-Host "$($results.Count) attempted, $($results.Count - $failed.Count) succeeded." -ForegroundColor $(if ($failed.Count) { 'Yellow' } else { 'Green' })
if ($stoppedAfter.Count -gt 0) {
    Write-Host "$($stoppedAfter.Count) PC(s) are NOT running an agent - they are collecting nothing:" -ForegroundColor Red
    $stoppedAfter | ForEach-Object { Write-Host "  $($_.Line)  $($_.Ip)  $($_.After)" -ForegroundColor Red }
}
