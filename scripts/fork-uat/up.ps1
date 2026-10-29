<#
.SYNOPSIS
  Runs a worktree of the Appsmith fork in dev mode: databases in Docker, server and
  client from source, client with hot reload. Open http://localhost:<Port>.

.DESCRIPTION
  Steps, in order (each can be run alone with -Only):
    db      Shared MongoDB replica set (container appsmith-dev-mongo, 127.0.0.1:27017,
            volume appsmith-dev-mongo; one database per worktree) and a Redis for this
            worktree (127.0.0.1:<Port+4>). Both restart with Docker and stay up.
    server  Java server on 127.0.0.1:<Port+1> (local JDK 25 + Maven) and RTS on
            127.0.0.1:<Port+2> (local Node). The first run builds every module and the
            plugins; later runs rebuild only appsmith-server and the modules it uses.
    client  Webpack dev server with hot reload on 127.0.0.1:<Port+3>, and an nginx proxy on
            127.0.0.1:<Port> that sends /api, /login, /oauth2 to the server and /rts to RTS.

  On an empty database, a dev-only admin is created (see README.md).

.EXAMPLE
  .\scripts\fork-uat\up.ps1
.EXAMPLE
  .\scripts\fork-uat\up.ps1 -Source C:\Projects\appsmith-wt\fork-roles-phase-a -Port 8180
.EXAMPLE
  .\scripts\fork-uat\up.ps1 -Restart server     # rebuild and restart the server after Java changes
.EXAMPLE
  .\scripts\fork-uat\up.ps1 -Restart server -Full   # also rebuild the plugins
#>
[CmdletBinding()]
param(
  # Repo or worktree folder to run. Default: the repo this script lives in.
  [string]$Source,
  # Browser port. The worktree also uses Port+1 (server), +2 (RTS), +3 (webpack), +4 (Redis).
  [int]$Port = 8080,
  # Instance name for containers, volumes and logs. Default: the worktree folder name.
  [string]$Name,
  # MongoDB database name. Default: appsmith_<Name>.
  [string]$Database,
  # Run only these steps: db, server, client.
  [ValidateSet("db", "server", "client")]
  [string[]]$Only = @("db", "server", "client"),
  # Rebuild and restart one part of a running instance.
  [ValidateSet("server", "client")]
  [string]$Restart,
  # JDK 25 folder for the server and Maven. Default: JAVA_HOME if it is a JDK 25, otherwise
  # the newest jdk-25* under Program Files. The global JAVA_HOME is never changed.
  [string]$JavaHome,
  # Rebuild every server module and the plugins, not only appsmith-server.
  [switch]$Full,
  # Compile only the changed .java files. Faster, but classes that use a changed signature
  # or constant are not recompiled; leave it off after pulls or signature changes.
  [switch]$Fast,
  # Don't create the dev admin on an empty database.
  [switch]$NoSeed,
  # Minutes to wait for the server (the first build downloads all Maven dependencies).
  [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Dev-only admin, created only on an empty local database. Documented in README.md.
$SeedEmail = "dev-admin@example.com"
$SeedPassword = "DevAdmin#2026"
$SeedName = "Dev Admin"

$MongoContainer = "appsmith-dev-mongo"
$Network = "appsmith-dev"

# Windows PowerShell 5.1 doesn't set $PSScriptRoot in parameter defaults.
if (-not $Source) { $Source = Join-Path $PSScriptRoot "..\.." }
$started = Get-Date

function Write-Step([string]$message) {
  Write-Host ""
  Write-Host "==> $message" -ForegroundColor Cyan
}

# Windows PowerShell 5.1 turns a native command's stderr into a terminating error
# under "Stop", so native commands run with "Continue" and are judged by exit code.
function Invoke-Native([string]$exe, [string[]]$arguments) {
  $ErrorActionPreference = "Continue"
  & $exe @arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$exe $($arguments -join ' ') failed with exit code $LASTEXITCODE"
  }
}

# Runs a native command for its stdout; returns $null when it fails.
function Invoke-Probe([string]$exe, [string[]]$arguments) {
  $ErrorActionPreference = "Continue"
  $output = & $exe @arguments 2> $null
  if ($LASTEXITCODE -ne 0) { return $null }
  if ($null -eq $output) { return "" }
  return ($output -join "`n").Trim()
}

function Test-Container([string]$container) {
  return $null -ne (Invoke-Probe docker @("container", "inspect", "--format", "{{.Id}}", $container))
}

function Test-ContainerRunning([string]$container) {
  return (Invoke-Probe docker @("container", "inspect", "--format", "{{.State.Running}}", $container)) -eq "true"
}

function Remove-Container([string]$container) {
  if (Test-Container $container) { Invoke-Native docker @("rm", "--force", $container) | Out-Null }
}

# Starts a command in its own hidden console, so it keeps running after this script
# ends and its stdin never closes. Output goes to $logFile.
function Start-Detached([string]$workingDir, [hashtable]$environment, [string]$command, [string]$logFile) {
  $sets = ($environment.GetEnumerator() | ForEach-Object { "set `"$($_.Key)=$($_.Value)`"&&" }) -join " "
  $process = Start-Process -FilePath "cmd.exe" -WorkingDirectory $workingDir -WindowStyle Hidden -PassThru `
    -ArgumentList "/d /s /c `"$sets $command > `"$logFile`" 2>&1`""
  return $process.Id
}

function Stop-Detached([string]$pidFile) {
  if (-not (Test-Path $pidFile)) { return }
  $processId = Get-Content $pidFile
  if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
    Invoke-Probe taskkill.exe @("/PID", $processId, "/T", "/F") | Out-Null
    Start-Sleep -Seconds 2
  }
  Remove-Item -Force $pidFile
}

function Test-PortListening([int]$portNumber) {
  return [bool](Get-NetTCPConnection -State Listen -LocalPort $portNumber -ErrorAction SilentlyContinue)
}

function Test-Jdk25([string]$dir) {
  $release = Join-Path $dir "release"
  return (Test-Path (Join-Path $dir "bin\java.exe")) -and (Test-Path $release) -and
    [bool]((Get-Content $release) -match '^JAVA_VERSION="25[."]')
}

function Find-Jdk25 {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
  foreach ($vendor in @("Microsoft", "Eclipse Adoptium", "Java", "Zulu", "Amazon Corretto")) {
    $root = Join-Path $env:ProgramFiles $vendor
    if (Test-Path $root) {
      $candidates += Get-ChildItem -Directory $root -Filter "*25*" | Sort-Object Name -Descending |
        ForEach-Object { $_.FullName }
    }
  }
  foreach ($dir in $candidates) { if (Test-Jdk25 $dir) { return $dir } }
  return $null
}

# A shell opened before Maven was installed doesn't have it on PATH yet, so the
# machine and user PATH are searched too.
function Find-Maven {
  $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  $paths = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
  foreach ($dir in $paths -split ";") {
    if ($dir -and (Test-Path (Join-Path $dir "mvn.cmd"))) { return (Join-Path $dir "mvn.cmd") }
  }
  return $null
}

# Runs Maven with JAVA_HOME set to the JDK 25 for this call only; output goes to $logFile.
function Invoke-Maven([string]$workingDir, [string[]]$arguments, [string]$logFile) {
  $ErrorActionPreference = "Continue"
  $savedJavaHome = $env:JAVA_HOME
  $env:JAVA_HOME = $JavaHome
  Push-Location $workingDir
  try { & $mvn --log-file $logFile @arguments } finally { Pop-Location; $env:JAVA_HOME = $savedJavaHome }
  if ($LASTEXITCODE -ne 0) {
    Get-Content $logFile -Tail 40 | Write-Host
    throw "Maven failed with exit code $LASTEXITCODE. Full log: $logFile"
  }
}

function Wait-Http([string]$url, [string]$what, [int]$minutes, [scriptblock]$stillAlive) {
  $deadline = (Get-Date).AddMinutes($minutes)
  while ((Get-Date) -lt $deadline) {
    try {
      # Without an Accept header, webpack's history fallback answers 404 for /user/login.
      $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Headers @{ Accept = "text/html,*/*" } $url
      if ($response.StatusCode -eq 200) { return $response }
    } catch {
      # Not up yet.
    }
    if ($stillAlive -and -not (& $stillAlive)) { throw "$what stopped while starting." }
    Start-Sleep -Seconds 5
  }
  throw "$what not ready at $url after $minutes minutes."
}

# ---- Resolve the worktree and the instance ----------------------------------

$Source = (Resolve-Path $Source).Path
$repoRoot = (& git -C $Source rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0) { throw "$Source is not a git repository or worktree." }
$repoRoot = $repoRoot -replace "/", "\"
if (-not $Name) { $Name = (Split-Path -Leaf $repoRoot).ToLowerInvariant() -replace "[^a-z0-9-]", "-" }
if (-not $Database) { $Database = "appsmith_" + ($Name -replace "-", "_") }
$branch = (& git -C $repoRoot rev-parse --abbrev-ref HEAD)

$workDir = Join-Path $PSScriptRoot ".work\$Name"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$instanceFile = Join-Path $workDir "instance.json"
# -Restart keeps the port and database the instance was started with.
if ($Restart -and (Test-Path $instanceFile)) {
  $instance = Get-Content -Raw $instanceFile | ConvertFrom-Json
  if (-not $PSBoundParameters.ContainsKey("Port")) { $Port = [int]$instance.port }
  if (-not $PSBoundParameters.ContainsKey("Database")) { $Database = $instance.db }
}

$ports = @{ ui = $Port; server = $Port + 1; rts = $Port + 2; client = $Port + 3; redis = $Port + 4 }
$prefix = "appsmith-dev-$Name"
$redisContainer = "$prefix-redis"
$proxyContainer = "$prefix-proxy"
$clientDir = Join-Path $repoRoot "app\client"
$serverDir = Join-Path $repoRoot "app\server"
$rtsDir = Join-Path $clientDir "packages\rts"
$node = (Get-Command node).Source

# Remembered so down.ps1 and -Restart find the same instance.
@{ source = $repoRoot; port = $Port; db = $Database; name = $Name } | ConvertTo-Json |
  Set-Content -Encoding UTF8 $instanceFile

if ($Restart) { $Only = @($Restart) }

Write-Host "Instance $Name ($branch) from $repoRoot"
Write-Host ("Ports: browser {0}, server {1}, RTS {2}, webpack {3}, Redis {4}; MongoDB database {5}" -f `
    $ports.ui, $ports.server, $ports.rts, $ports.client, $ports.redis, $Database)

Write-Step "Waiting for Docker"
$dockerReady = $false
for ($i = 1; $i -le 30; $i++) {
  if (Invoke-Probe docker @("info", "--format", "{{.ServerVersion}}")) { $dockerReady = $true; break }
  Start-Sleep -Seconds 5
}
if (-not $dockerReady) { throw "Docker is not responding. Start Docker Desktop and try again." }

# ---- db: MongoDB replica set (shared) and Redis (this instance) ---------------

if ($Only -contains "db") {
  Write-Step "Databases"
  if ($null -eq (Invoke-Probe docker @("network", "inspect", $Network))) {
    Invoke-Native docker @("network", "create", $Network) | Out-Null
  }

  if (-not (Test-Container $MongoContainer)) {
    Invoke-Native docker @("run", "--detach", "--name", $MongoContainer, "--network", $Network,
      "--restart", "unless-stopped", "--publish", "127.0.0.1:27017:27017",
      "--volume", "appsmith-dev-mongo:/data/db", "mongo:7", "--replSet", "rs0", "--bind_ip_all") | Out-Null
  } elseif (-not (Test-ContainerRunning $MongoContainer)) {
    Invoke-Native docker @("start", $MongoContainer) | Out-Null
  }

  # Initiate the replica set once, then wait for a primary. Clients connect with
  # directConnection=true, so the member host name doesn't need to resolve outside.
  $initScript = "try { rs.status().ok } catch (e) { rs.initiate({ _id: 'rs0', members: [{ _id: 0, host: 'localhost:27017' }] }).ok }"
  $primary = $false
  for ($i = 1; $i -le 30; $i++) {
    Invoke-Probe docker @("exec", $MongoContainer, "mongosh", "--quiet", "--eval", $initScript) | Out-Null
    $isPrimary = Invoke-Probe docker @("exec", $MongoContainer, "mongosh", "--quiet", "--eval", "db.hello().isWritablePrimary")
    if ($isPrimary -eq "true") { $primary = $true; break }
    Start-Sleep -Seconds 2
  }
  if (-not $primary) { throw "MongoDB replica set has no primary. See: docker logs $MongoContainer" }
  Write-Host "MongoDB replica set rs0 is up on 127.0.0.1:27017 (database $Database)."

  if (-not (Test-Container $redisContainer)) {
    Invoke-Native docker @("run", "--detach", "--name", $redisContainer, "--network", $Network,
      "--restart", "unless-stopped", "--publish", "127.0.0.1:$($ports.redis):6379",
      "--volume", "${prefix}-redis:/data", "redis:7", "redis-server", "--appendonly", "yes") | Out-Null
  } elseif (-not (Test-ContainerRunning $redisContainer)) {
    Invoke-Native docker @("start", $redisContainer) | Out-Null
  }
  $pong = $null
  for ($i = 1; $i -le 15; $i++) {
    $pong = Invoke-Probe docker @("exec", $redisContainer, "redis-cli", "ping")
    if ($pong -eq "PONG") { break }
    Start-Sleep -Seconds 1
  }
  if ($pong -ne "PONG") { throw "Redis is not answering. See: docker logs $redisContainer" }
  Write-Host "Redis is up on 127.0.0.1:$($ports.redis)."
}

# ---- Client dependencies and the RTS bundle (needed by server and client) -----

if ($Only -contains "server" -or $Only -contains "client") {
  $yarnState = Join-Path $clientDir "node_modules\.yarn-state.yml"
  $lockFile = Join-Path $clientDir "yarn.lock"
  if (-not (Test-Path $yarnState) -or (Get-Item $lockFile).LastWriteTime -gt (Get-Item $yarnState).LastWriteTime) {
    Write-Step "yarn install (app/client)"
    $env:CYPRESS_INSTALL_BINARY = "0"
    $env:HUSKY = "0"
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1"
    $yarn = (Get-ChildItem (Join-Path $clientDir ".yarn\releases\yarn-*.cjs") | Select-Object -First 1).FullName
    Push-Location $clientDir
    try { Invoke-Native $node @($yarn, "install", "--immutable") } finally { Pop-Location }
  }
}

# ---- server: Java server (local JDK 25 + Maven) and RTS (local Node) ----------

# Defaults; docker.env (settings saved from the admin pages) can override them.
$serverEnv = @{
  APPSMITH_ENCRYPTION_PASSWORD     = "appsmith-dev"
  APPSMITH_ENCRYPTION_SALT         = "appsmith-dev"
  APPSMITH_MAIL_ENABLED            = "false"
  APPSMITH_DISABLE_TELEMETRY       = "true"
  # An empty value falls back to cs.appsmith.com; point it at a closed port instead.
  APPSMITH_CLOUD_SERVICES_BASE_URL = "http://127.0.0.1:9"
}

if ($Only -contains "server") {
  if ($JavaHome) {
    if (-not (Test-Jdk25 $JavaHome)) { throw "-JavaHome $JavaHome is not a JDK 25 (needs bin\java.exe and a release file saying 25)." }
  } else {
    $JavaHome = Find-Jdk25
    if (-not $JavaHome) { throw "No JDK 25 found. Install one (winget install Microsoft.OpenJDK.25) or pass -JavaHome <folder>." }
  }
  $mvn = Find-Maven
  if (-not $mvn) { throw "Maven (mvn.cmd) not found on PATH. Install it (choco install maven) and try again." }
  Write-Host "JDK 25: $JavaHome"
  Write-Host "Maven:  $mvn"

  $dist = Join-Path $serverDir "dist"
  $pluginsDist = Join-Path $dist "plugins"
  $buildAll = $Full -or -not (Test-Path (Join-Path $pluginsDist "*.jar"))
  # maven.test.skip also skips compiling the tests (-DskipTests only skips running them).
  $mavenArgs = @("--batch-mode", "--threads", "1C", "-Dmaven.test.skip=true")
  # The running server keeps serving during the build; it stops only once the build is done.
  $buildStarted = Get-Date
  if ($buildAll) {
    # Plugins don't change with server code; later runs reuse them from dist\plugins.
    Write-Step "Building all server modules and plugins (log: $(Join-Path $workDir 'build-all.log'))"
    Invoke-Maven $serverDir ($mavenArgs + "package") (Join-Path $workDir "build-all.log")
  }
  # The server runs from its compiled classes, as in an IDE. Compiling without packaging
  # lets the reactor use the other modules' target\classes, so unchanged modules compile
  # nothing. appsmith-server itself is always recompiled whole: it has empty .java files
  # (no .class to compare with), so Maven always sees it as changed. -Fast compiles only
  # the files newer than their classes instead.
  if ($Fast) { $mavenArgs += "-Dmaven.compiler.useIncrementalCompilation=false" }
  Write-Step "Compiling appsmith-server and the modules it uses (log: $(Join-Path $workDir 'build.log'))"
  Invoke-Maven $serverDir ($mavenArgs + @("--projects", "appsmith-server", "--also-make",
      "-Dmdep.outputFile=target/classpath.txt", "-DincludeScope=runtime", "compile", "dependency:build-classpath")) `
    (Join-Path $workDir "build.log")
  Push-Location $rtsDir
  try { Invoke-Native $node @("build.js") | Out-Null } finally { Pop-Location }
  Write-Host ("Built in {0:mm\:ss}." -f ((Get-Date) - $buildStarted))

  $serverPid = Join-Path $workDir "server.pid"
  $rtsPid = Join-Path $workDir "rts.pid"
  Stop-Detached $serverPid
  Stop-Detached $rtsPid
  # Left over from the earlier container-based setup.
  Remove-Container "$prefix-server"
  foreach ($p in @($ports.server, $ports.rts)) {
    if (Test-PortListening $p) { throw "Port $p is in use by something else. Pick another -Port (e.g. -Port $($Port + 100))." }
  }

  # Plugins as in app/server/build.sh (dist\plugins\*.jar); the server runs in dist so
  # that it finds them.
  New-Item -ItemType Directory -Force -Path $pluginsDist | Out-Null
  if ($buildAll) {
    Remove-Item -Force (Join-Path $pluginsDist "*.jar")
    Get-ChildItem (Join-Path $serverDir "appsmith-plugins\*\target\*.jar") | Where-Object { $_.Name -notlike "original-*" } |
      Copy-Item -Destination $pluginsDist
  }

  # Like the Docker image, settings saved from the admin pages (docker.env) apply on start.
  $envFile = Join-Path $workDir "docker.env"
  if (-not (Test-Path $envFile)) { New-Item -ItemType File -Path $envFile | Out-Null }
  $gitRoot = Join-Path $workDir "git-storage"
  New-Item -ItemType Directory -Force -Path $gitRoot | Out-Null
  $localEnv = $serverEnv.Clone()
  foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $localEnv[$Matches[1]] = $Matches[2].Trim().Trim('"', "'") }
  }
  $localEnv.PORT = $ports.server
  $localEnv.APPSMITH_RTS_PORT = $ports.rts
  $localEnv.APPSMITH_DB_URL = "mongodb://127.0.0.1:27017/${Database}?directConnection=true"
  $localEnv.APPSMITH_REDIS_URL = "redis://127.0.0.1:$($ports.redis)"
  $localEnv.APPSMITH_ENVFILE_PATH = $envFile
  $localEnv.APPSMITH_GIT_ROOT = $gitRoot
  $localEnv.JAVA_HOME = $JavaHome

  Write-Step "Starting RTS on 127.0.0.1:$($ports.rts) and the server on 127.0.0.1:$($ports.server)"
  $serverStarted = Get-Date
  Start-Detached $rtsDir @{ APPSMITH_RTS_PORT = $ports.rts; APPSMITH_API_BASE_URL = "http://127.0.0.1:$($ports.server)/api/v1" } `
    "`"$node`" dist\bundle\server.js" (Join-Path $workDir "rts.log") | Set-Content $rtsPid
  # The classpath is too long for a command line, so it goes in a java @argfile
  # (forward slashes: backslashes are escapes there).
  $classpath = @(Join-Path $serverDir "appsmith-server\target\classes") +
    ((Get-Content -Raw (Join-Path $serverDir "appsmith-server\target\classpath.txt")).Trim() -split ";")
  $argFile = Join-Path $workDir "server.args"
  [System.IO.File]::WriteAllText($argFile,
    "-cp `"$(($classpath -join ';') -replace '\\', '/')`"`ncom.appsmith.server.ServerApplication`n")
  Start-Detached $dist $localEnv "`"$(Join-Path $JavaHome 'bin\java.exe')`" `"@$argFile`"" (Join-Path $workDir "server.log") |
    Set-Content $serverPid
  $serverAlive = { Get-Process -Id (Get-Content $serverPid) -ErrorAction SilentlyContinue }
}

# ---- client: webpack dev server (hot reload) + nginx proxy --------------------

if ($Only -contains "client") {
  $clientPid = Join-Path $workDir "client.pid"
  Stop-Detached $clientPid
  Remove-Container $proxyContainer
  foreach ($p in @($ports.ui, $ports.client)) {
    if (Test-PortListening $p) { throw "Port $p is in use by something else. Pick another -Port (e.g. -Port $($Port + 100))." }
  }

  Write-Step "Starting the webpack dev server on 127.0.0.1:$($ports.client)"
  $clientEnv = @{ PORT = $ports.client; HOST = "127.0.0.1"; NODE_OPTIONS = "--max-old-space-size=8192" }
  Start-Detached $clientDir $clientEnv "`"$node`" `"$(Join-Path $PSScriptRoot 'client-dev.js')`" `"$clientDir`"" `
    (Join-Path $workDir "client.log") | Set-Content $clientPid

  Write-Step "Starting the proxy on http://localhost:$($ports.ui)"
  # Same routes as app/client/start-https.sh, over plain http on localhost.
  $nginxConf = @"
worker_processes 1;
events { worker_connections 1024; }
http {
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  sendfile on;
  keepalive_timeout 65;
  map `$http_upgrade `$connection_upgrade { default upgrade; '' close; }

  server {
    listen 80 default_server;
    client_max_body_size 150m;
    proxy_http_version 1.1;
    proxy_read_timeout 300s;
    proxy_set_header Host `$http_host;
    proxy_set_header X-Forwarded-Host `$http_host;
    proxy_set_header X-Forwarded-Proto `$scheme;
    proxy_set_header Upgrade `$http_upgrade;
    proxy_set_header Connection `$connection_upgrade;

    location / {
      proxy_pass http://host.docker.internal:$($ports.client);
      # Dev-time: never cache the client.
      add_header Cache-Control 'no-store, must-revalidate' always;
      proxy_hide_header Cache-Control;
    }
    location /api { proxy_pass http://host.docker.internal:$($ports.server); }
    location /oauth2 { proxy_pass http://host.docker.internal:$($ports.server); }
    location /login { proxy_pass http://host.docker.internal:$($ports.server); }
    location /rts { proxy_pass http://host.docker.internal:$($ports.rts); }
  }
}
"@
  $nginxConfPath = Join-Path $workDir "nginx.conf"
  [System.IO.File]::WriteAllText($nginxConfPath, ($nginxConf -replace "`r`n", "`n"))
  Invoke-Native docker @("run", "--detach", "--name", $proxyContainer,
    "--add-host", "host.docker.internal:host-gateway",
    "--publish", "127.0.0.1:$($ports.ui):80",
    "--volume", "${nginxConfPath}:/etc/nginx/nginx.conf:ro",
    "nginx:alpine") | Out-Null
}

# ---- Wait, seed, report ------------------------------------------------------

if ($Only -contains "server") {
  Write-Step "Waiting for the server to start"
  Wait-Http "http://127.0.0.1:$($ports.server)/api/v1/health" "The server ($(Join-Path $workDir 'server.log'))" $TimeoutMinutes $serverAlive | Out-Null
  Write-Host ("Server healthy {0:mm\:ss} after its start ({1:mm\:ss} in total)." -f ((Get-Date) - $serverStarted), ((Get-Date) - $started))
}

if ($Only -contains "client") {
  Write-Step "Waiting for the client's first compile"
  $clientAlive = { Get-Process -Id (Get-Content (Join-Path $workDir "client.pid")) -ErrorAction SilentlyContinue }
  $page = Wait-Http "http://localhost:$($ports.ui)/user/login" "The client ($(Join-Path $workDir 'client.log'))" $TimeoutMinutes $clientAlive
  if ($page.Content -notmatch '<div id="root"') { throw "http://localhost:$($ports.ui)/user/login did not return the Appsmith page." }
  Write-Host ("Client ready after {0:mm\:ss}." -f ((Get-Date) - $started))
}

if ($Only -contains "server" -and -not $NoSeed) {
  # Creates the first (super) user like the setup form does. The server refuses this
  # once any user exists, so it only ever acts on an empty database.
  $result = & curl.exe --silent --output NUL --write-out "%{http_code} %{redirect_url}" `
    --header "X-Requested-By: Appsmith" --header "Origin: http://localhost:$($ports.ui)" `
    --data-urlencode "email=$SeedEmail" --data-urlencode "password=$SeedPassword" `
    --data-urlencode "name=$SeedName" --data "allowCollectingAnonymousData=false" `
    --data "signupForNewsletter=false" "http://127.0.0.1:$($ports.server)/api/v1/users/super"
  if ($result -match "error=") {
    Write-Host "Dev admin not created: the database already has users."
  } else {
    Write-Host "Created dev admin $SeedEmail (password in scripts/fork-uat/README.md)."
  }
}

Write-Host ""
if ($Only -contains "server" -or $Only -contains "client") {
  Write-Host ("Appsmith ($branch) is running at http://localhost:{0}  (took {1:mm\:ss})" -f $ports.ui, ((Get-Date) - $started)) -ForegroundColor Green
  Write-Host "Logs: $workDir"
  Write-Host "Stop: .\scripts\fork-uat\down.ps1 -Source `"$repoRoot`""
} else {
  Write-Host ("Databases for $Name are up: MongoDB 127.0.0.1:27017 (database $Database), Redis 127.0.0.1:{0}" -f $ports.redis) -ForegroundColor Green
}
