<#
.SYNOPSIS
  Stops a worktree's dev instance started by up.ps1. Databases and data are kept.

.DESCRIPTION
  By default stops the client (webpack), the proxy, the server and RTS of one worktree.
  MongoDB and the worktree's Redis keep running, and no data is deleted.
    -StopDatabases  also stops the worktree's Redis, and the shared MongoDB when no other
                    worktree's Redis is still running. Volumes are kept.
    -DeleteData     also drops the worktree's MongoDB database, its Redis container and
                    volume, and its saved settings (.work\<Name>). Asks first. Other
                    worktrees' data is never touched.

.EXAMPLE
  .\scripts\fork-uat\down.ps1
.EXAMPLE
  .\scripts\fork-uat\down.ps1 -Source C:\Projects\appsmith-wt\fork-roles-phase-a
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "High")]
param(
  # Repo or worktree folder the instance runs from. Default: the repo this script lives in.
  [string]$Source,
  # Instance name, as given to up.ps1. Default: the worktree folder name.
  [string]$Name,
  [switch]$StopDatabases,
  [switch]$DeleteData
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$MongoContainer = "appsmith-dev-mongo"

# Windows PowerShell 5.1 doesn't set $PSScriptRoot in parameter defaults.
if (-not $Source) { $Source = Join-Path $PSScriptRoot "..\.." }

# Runs a native command for its stdout; returns $null when it fails.
function Invoke-Probe([string]$exe, [string[]]$arguments) {
  $ErrorActionPreference = "Continue"
  $output = & $exe @arguments 2> $null
  if ($LASTEXITCODE -ne 0) { return $null }
  if ($null -eq $output) { return "" }
  return ($output -join "`n").Trim()
}

function Stop-Detached([string]$pidFile, [string]$what) {
  if (-not (Test-Path $pidFile)) { return }
  $processId = Get-Content $pidFile
  if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
    Invoke-Probe taskkill.exe @("/PID", $processId, "/T", "/F") | Out-Null
    Write-Host "Stopped $what (process $processId)."
  }
  Remove-Item -Force $pidFile
}

function Remove-Container([string]$container) {
  if ($null -ne (Invoke-Probe docker @("container", "inspect", "--format", "{{.Id}}", $container))) {
    Invoke-Probe docker @("rm", "--force", $container) | Out-Null
    Write-Host "Removed container $container."
  }
}

# Same instance name and database as up.ps1.
$repoRoot = (Invoke-Probe git @("-C", (Resolve-Path $Source).Path, "rev-parse", "--show-toplevel"))
if (-not $repoRoot) { throw "$Source is not a git repository or worktree." }
$repoRoot = $repoRoot -replace "/", "\"
if (-not $Name) { $Name = (Split-Path -Leaf $repoRoot).ToLowerInvariant() -replace "[^a-z0-9-]", "-" }
$workDir = Join-Path $PSScriptRoot ".work\$Name"
$Database = "appsmith_" + ($Name -replace "-", "_")
$instanceFile = Join-Path $workDir "instance.json"
if (Test-Path $instanceFile) { $Database = (Get-Content -Raw $instanceFile | ConvertFrom-Json).db }
$prefix = "appsmith-dev-$Name"
$redisContainer = "$prefix-redis"

Stop-Detached (Join-Path $workDir "client.pid") "the webpack dev server"
Stop-Detached (Join-Path $workDir "server.pid") "the server"
Stop-Detached (Join-Path $workDir "rts.pid") "RTS"
Remove-Container "$prefix-proxy"
# Left over from the earlier container-based setup.
Remove-Container "$prefix-server"

if ($DeleteData) {
  $what = "MongoDB database $Database, Redis $redisContainer and its volume, and $workDir"
  if ($PSCmdlet.ShouldProcess($what, "Delete")) {
    $dropped = Invoke-Probe docker @("exec", $MongoContainer, "mongosh", "--quiet", $Database, "--eval", "db.dropDatabase().ok")
    if ($dropped -eq "1") { Write-Host "Dropped MongoDB database $Database." } else { Write-Host "MongoDB database $Database not dropped (is $MongoContainer running?)." }
    Remove-Container $redisContainer
    if ($null -ne (Invoke-Probe docker @("volume", "rm", $redisContainer))) { Write-Host "Deleted volume $redisContainer." }
    if (Test-Path $workDir) { Remove-Item -Recurse -Force $workDir; Write-Host "Deleted $workDir." }
  }
} elseif ($StopDatabases) {
  Invoke-Probe docker @("stop", $redisContainer) | Out-Null
  Write-Host "Stopped $redisContainer (volume kept)."
  $otherRedis = Invoke-Probe docker @("ps", "--filter", "name=^appsmith-dev-.*-redis$", "--format", "{{.Names}}")
  if ($otherRedis) {
    Write-Host "MongoDB left running: other worktrees are up ($($otherRedis -replace "`n", ', '))."
  } else {
    # Give MongoDB time to shut down cleanly.
    Invoke-Probe docker @("stop", "--time", "60", $MongoContainer) | Out-Null
    Write-Host "Stopped $MongoContainer (volume kept)."
  }
} else {
  Write-Host "MongoDB ($MongoContainer) and Redis ($redisContainer) keep running; data kept."
}
