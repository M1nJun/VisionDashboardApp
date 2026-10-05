<#
.SYNOPSIS
Builds the folder to copy onto the central PC.

.DESCRIPTION
Collects everything the central PC needs into dist\central-pc - the server jar, the
schema, the contracts, and the two scripts that run it. Copy that one folder across and
nothing else is needed from this repository.

Run after building the web bundle and the server:
  cd web;    npm run build
  Remove-Item server	arget\classes\static -Recurse -Force -ErrorAction Ignore
  cd server; mvn -o -DskipTests package

Delete target\classes\static first: Maven copies resources in but never removes ones
that are gone from the source, so without it the jar carries every frontend bundle ever
built alongside the current one. (mvn clean would do it too, but the clean plugin needs
a dependency this offline repo does not have.)

.EXAMPLE
.\Package-Central.ps1
.EXAMPLE
.\Package-Central.ps1 -Contracts ..\..\vision-contracts
#>
[CmdletBinding()]
param(
    [string] $Output,
    # Where the contracts for this plant live.
    #
    # The pair committed to this repository is an example: the real one's shape, with
    # example inspection stages, defect codes, line names and addresses, because the
    # repository is public and the plant it was written for is not. Point this at the
    # folder holding the real pair when packaging a deployment.
    [string] $Contracts
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
if (-not $Output) { $Output = Join-Path $repo 'dist\central-pc' }
if (-not $Contracts) { $Contracts = Join-Path $repo 'contracts' }
$Contracts = (Resolve-Path $Contracts).Path

foreach ($name in @('vision-catalog.json', 'topology.json')) {
    if (-not (Test-Path (Join-Path $Contracts $name))) {
        throw "$name is not in $Contracts"
    }
}
Write-Host "Contracts: $Contracts" -ForegroundColor DarkGray

$jar = Join-Path $repo 'server\target\server-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
    throw "Server jar not found. Build it first:`n  cd server`n  mvn -o -DskipTests package"
}

# The jar must carry exactly one frontend bundle. More than one means the build was not
# clean, and a browser still holding an old index.html would keep resolving against a
# stale bundle instead of failing loudly and picking up the new one.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $bundles = @($zip.Entries | Where-Object { $_.FullName -match 'static/assets/index-.+\.js$' } |
                 ForEach-Object { $_.Name })
}
finally {
    $zip.Dispose()
}
if ($bundles.Count -ne 1) {
    throw ("The jar carries {0} frontend bundles ({1}). Rebuild cleanly:`n" -f $bundles.Count, ($bundles -join ', ')) +
          "  cd web; npm run build`n" +
          "  Remove-Item server	arget\classes\static -Recurse -Force`n" +
          "  cd server; mvn -o -DskipTests package"
}

if (Test-Path $Output) { Remove-Item $Output -Recurse -Force }
New-Item -ItemType Directory -Path $Output -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Output 'db') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Output 'contracts') -Force | Out-Null

# Named server.jar so run-server.cmd never has to know the version.
Copy-Item $jar (Join-Path $Output 'server.jar') -Force
# Every .sql: the schema, its smoke test, and any migration an existing central PC
# needs before the new jar will read its database correctly.
Copy-Item (Join-Path $repo 'db\*.sql') (Join-Path $Output 'db') -Force

# Also packaged inside the jar; this copy is the one an operator can edit and point at
# with CATALOG_DIR without a rebuild.
Copy-Item (Join-Path $Contracts 'vision-catalog.json') (Join-Path $Output 'contracts') -Force
Copy-Item (Join-Path $Contracts 'topology.json') (Join-Path $Output 'contracts') -Force

Copy-Item (Join-Path $here 'central\run-server.cmd') $Output -Force
Copy-Item (Join-Path $here 'central\Install-Service.ps1') $Output -Force

# The deployer ships alongside the server: it needs SMB and sc.exe access to the
# inspection PCs, which the central PC has and a desk machine generally does not.
$deployer = Join-Path $Output 'deploy-agents'
New-Item -ItemType Directory -Path (Join-Path $deployer 'contracts') -Force | Out-Null
Copy-Item (Join-Path $here 'Deploy-Agent.ps1') $deployer -Force
Copy-Item (Join-Path $here 'Resync-Lot.ps1') $deployer -Force
Copy-Item (Join-Path $here 'deploy.json') $deployer -Force
Copy-Item (Join-Path $Contracts 'vision-catalog.json') (Join-Path $deployer 'contracts') -Force
Copy-Item (Join-Path $Contracts 'topology.json') (Join-Path $deployer 'contracts') -Force

$agentExe = Join-Path $repo 'agent\bin\Release\net8.0\win-x64\publish\VisionAgent.exe'
if (Test-Path $agentExe) {
    Copy-Item $agentExe $deployer -Force
}
else {
    Write-Warning "VisionAgent.exe is not built, so deploy-agents can copy files but not the agent itself.`n  cd agent; dotnet publish -c Release"
}

$size = [math]::Round(((Get-ChildItem $Output -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "Packaged the central PC folder: $Output  ($size MB)" -ForegroundColor Green
Get-ChildItem $Output -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($Output.Length + 1)
    "{0,-34} {1,8:N0} KB" -f $relative, ($_.Length / 1KB)
}
Write-Host ""
Write-Host "Copy that whole folder to the central PC, then follow deploy\RUNBOOK.md."
