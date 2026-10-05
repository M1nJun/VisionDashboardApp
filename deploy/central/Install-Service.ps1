<#
.SYNOPSIS
Makes the central server start with Windows and stay up.

.DESCRIPTION
Registers run-server.cmd as a scheduled task that runs at boot as SYSTEM and restarts if
it ever exits. Task Scheduler rather than a real Windows service because it is built into
Windows - the factory network is offline, so anything that would need a service wrapper
downloaded (nssm, WinSW) is not an option.

Run this from an elevated PowerShell in the folder it sits in.

.EXAMPLE
.\Install-Service.ps1

.EXAMPLE
.\Install-Service.ps1 -Remove
#>
[CmdletBinding()]
param(
    [string] $TaskName = 'VisionDashboardServer',
    [switch] $Remove
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$cmd = Join-Path $here 'run-server.cmd'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this from an elevated PowerShell (right-click, Run as administrator).'
}

if ($Remove) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
    Write-Host "Removed the '$TaskName' task." -ForegroundColor Green
    return
}

if (-not (Test-Path $cmd)) {
    throw "run-server.cmd is not next to this script ($here)."
}

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Replacing the existing '$TaskName' task."
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

# /silent suppresses the pause at the end of run-server.cmd - nothing is there to press a
# key on a machine that just booted.
$action = New-ScheduledTaskAction -Execute $cmd -Argument '/silent' -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartInterval (New-TimeSpan -Minutes 1) -RestartCount 999 `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0)   # never kill it for running too long

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings | Out-Null

Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 8

$state = (Get-ScheduledTask -TaskName $TaskName).State
Write-Host ""
Write-Host "Task '$TaskName' registered and started. State: $state" -ForegroundColor Green
Write-Host "  log      : $(Join-Path $here 'logs\server.log')"
Write-Host "  dashboard: http://localhost:8080/dashboard/"
Write-Host ""
Write-Host "It will now start automatically whenever this PC boots." -ForegroundColor Green
