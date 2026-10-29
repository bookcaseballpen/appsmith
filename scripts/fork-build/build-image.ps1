# Fork: builds the image from source with Docker only; no Appsmith image or host is used. See FORK.md.
# Twin of build-image.sh, for Windows PowerShell 5.1 and later.
#
# Usage: scripts\fork-build\build-image.ps1 [-Image creative-app-builder:local] [-BaseImage creative-app-builder-base:local] [-Version v0.0.0-local]

param(
  [string]$Image = "creative-app-builder:local",
  [string]$BaseImage = "creative-app-builder-base:local",
  [string]$Version = "v0.0.0-local"
)

# No $ErrorActionPreference = "Stop": Windows PowerShell 5.1 turns docker's progress output on stderr into
# errors when the output is redirected. Each docker call checks $LASTEXITCODE instead.

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

$commitSha = (git rev-parse HEAD)
if ($LASTEXITCODE -ne 0 -or -not $commitSha) { $commitSha = "unknown" }
$builtAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

Write-Host "== Building runtime base $BaseImage (deploy/docker/base.dockerfile)"
# The base copies nothing from a context, so the Dockerfile goes in on stdin with no context. Carriage returns are
# stripped: in a checkout made before .gitattributes forced LF, its heredocs would fail on CRLF.
$baseDockerfile = [IO.File]::ReadAllText((Join-Path $root "deploy\docker\base.dockerfile")).Replace("`r", "")
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$baseDockerfile | docker build --tag $BaseImage -
if ($LASTEXITCODE -ne 0) { throw "Base image build failed" }

Write-Host "== Building $Image (deploy/fork/Dockerfile)"
docker build `
  --file deploy/fork/Dockerfile `
  --build-arg "BASE=$BaseImage" `
  --build-arg "COMMIT_SHA=$commitSha" `
  --build-arg "VERSION=$Version" `
  --build-arg "IMAGE_BUILT_AT=$builtAt" `
  --tag $Image `
  .
if ($LASTEXITCODE -ne 0) { throw "Image build failed" }

Write-Host "== Built $Image"
