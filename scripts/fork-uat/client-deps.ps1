# Reuses the main folder's client dependencies in a worktree, instead of a 30-minute
# yarn install. Dot-sourced by up.ps1 and down.ps1.
#
# The worktree gets a real app\client\node_modules folder holding one junction per entry
# of the main folder's node_modules, not one junction for the whole folder:
# - Yarn links the workspace packages (@appsmith/ads, @shared/dsl, appsmith-rts, ...) with
#   absolute junctions into the main folder's packages\; here they point at the worktree's
#   own packages\, so the worktree builds with its own code;
# - node_modules\.cache (webpack, babel-loader and tsc caches) is the worktree's own, so two
#   running webpacks never share a cache.
# The packages' own node_modules (packages\rts\node_modules, ...) are linked the same way.
# A marker file lists the linked folders. Never run yarn in a linked worktree: it would
# write through the junctions into the main folder; remove the links first
# (Remove-LinkedClientDeps, or up.ps1 -NoLink).

$LinkMarkerName = ".fork-uat-linked"

function Test-ReparsePoint([System.IO.FileSystemInfo]$item) {
  return [bool]($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
}

# The main folder's app\client, or $null when $repoRoot is the main folder itself.
function Get-MainClientDir([string]$repoRoot) {
  $ErrorActionPreference = "Continue"
  $commonDir = & git -C $repoRoot rev-parse --git-common-dir 2> $null
  if ($LASTEXITCODE -ne 0 -or -not $commonDir) { return $null }
  if (-not [System.IO.Path]::IsPathRooted($commonDir)) { $commonDir = Join-Path $repoRoot $commonDir }
  $mainRoot = Split-Path -Parent ([System.IO.Path]::GetFullPath($commonDir))
  if ($mainRoot.TrimEnd("\") -ieq ([System.IO.Path]::GetFullPath($repoRoot)).TrimEnd("\")) { return $null }
  return Join-Path $mainRoot "app\client"
}

function Test-LinkedClientDeps([string]$clientDir) {
  return Test-Path -LiteralPath (Join-Path $clientDir "node_modules\$LinkMarkerName")
}

# False for a link folder whose linking was interrupted.
function Test-LinkedClientDepsComplete([string]$clientDir) {
  $marker = Join-Path $clientDir "node_modules\$LinkMarkerName"
  return (Test-Path -LiteralPath $marker) -and (@(Get-Content -LiteralPath $marker) -contains "complete")
}

# Why the main folder's dependencies can't be reused, or $null when they can: a finished
# install there (not itself linked) and the same package.json and yarn.lock.
function Get-ClientDepsLinkBlocker([string]$clientDir, [string]$mainClientDir) {
  if (-not $mainClientDir) { return "this is the main folder" }
  if (-not (Test-Path -LiteralPath (Join-Path $mainClientDir "node_modules\.yarn-state.yml"))) {
    return "the main folder has no finished install ($mainClientDir\node_modules)"
  }
  if (Test-LinkedClientDeps $mainClientDir) { return "the main folder's node_modules is itself linked" }
  foreach ($file in @("package.json", "yarn.lock")) {
    $ours = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $clientDir $file)
    $theirs = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $mainClientDir $file)
    if ($ours.Hash -ne $theirs.Hash) { return "$file differs from the main folder's" }
  }
  return $null
}

# The workspace packages' own node_modules in the main folder, as paths relative to
# app\client (packages\rts\node_modules, packages\design-system\ads\node_modules, ...).
function Get-PackageNodeModules([string]$mainClientDir) {
  $packagesDir = Join-Path $mainClientDir "packages"
  $found = @()
  $level = @(Get-Item -LiteralPath $packagesDir)
  # Workspaces are "packages/**"; they sit at most three levels down.
  for ($depth = 1; $depth -le 3; $depth++) {
    $level = @($level | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory -Force } |
        Where-Object { $_.Name -ne "node_modules" -and $_.Name -ne "build" -and -not (Test-ReparsePoint $_) })
    foreach ($dir in $level) {
      if (Test-Path -LiteralPath (Join-Path $dir.FullName "node_modules")) {
        $found += "packages\" + $dir.FullName.Substring($packagesDir.Length).TrimStart("\") + "\node_modules"
      }
    }
  }
  return $found
}

# Points a junction target inside the main folder's app\client at the same path in the worktree.
function Convert-LinkTarget([string]$target, [string]$clientDir, [string]$mainClientDir) {
  $prefix = $mainClientDir.TrimEnd("\") + "\"
  if ($target.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    return Join-Path $clientDir $target.Substring($prefix.Length)
  }
  return $target
}

# The actions that link $source (a node_modules folder in the main folder) into $destination:
# @{ Kind = "Dir" | "Junction" | "Copy"; Path; Target }. Nothing is changed.
function Get-NodeModulesLinkPlan([string]$source, [string]$destination, [string]$clientDir, [string]$mainClientDir) {
  $plan = New-Object System.Collections.Generic.List[object]
  $plan.Add(@{ Kind = "Dir"; Path = $destination; Target = $null })
  foreach ($item in Get-ChildItem -LiteralPath $source -Force) {
    $path = Join-Path $destination $item.Name
    if ($item.Name -eq ".cache") {
      $plan.Add(@{ Kind = "Dir"; Path = $path; Target = $null })
    } elseif (Test-ReparsePoint $item) {
      $target = [string]@($item.Target)[0]
      $plan.Add(@{ Kind = "Junction"; Path = $path; Target = (Convert-LinkTarget $target $clientDir $mainClientDir) })
    } elseif (-not $item.PSIsContainer) {
      $plan.Add(@{ Kind = "Copy"; Path = $path; Target = $item.FullName })
    } elseif ($item.Name.StartsWith("@") -and
        @(Get-ChildItem -LiteralPath $item.FullName -Force | Where-Object { Test-ReparsePoint $_ }).Count -gt 0) {
      # A scope holding workspace links (@appsmith, @shared): link its entries one by one.
      foreach ($entry in @(Get-NodeModulesLinkPlan $item.FullName $path $clientDir $mainClientDir)) { $plan.Add($entry) }
    } else {
      $plan.Add(@{ Kind = "Junction"; Path = $path; Target = $item.FullName })
    }
  }
  return $plan
}

# All actions for the worktree: app\client\node_modules and the packages' node_modules.
function Get-ClientDepsLinkPlan([string]$clientDir, [string]$mainClientDir) {
  $plan = New-Object System.Collections.Generic.List[object]
  $folders = @("node_modules") + @(Get-PackageNodeModules $mainClientDir)
  foreach ($relative in $folders) {
    $destination = Join-Path $clientDir $relative
    # A package the worktree doesn't have, or one it already installed itself, is left alone.
    if (-not (Test-Path -LiteralPath (Split-Path -Parent $destination)) -or (Test-Path -LiteralPath $destination)) { continue }
    foreach ($entry in @(Get-NodeModulesLinkPlan (Join-Path $mainClientDir $relative) $destination $clientDir $mainClientDir)) {
      $plan.Add($entry)
    }
  }
  return $plan
}

function New-LinkedClientDeps([string]$clientDir, [string]$mainClientDir) {
  $plan = @(Get-ClientDepsLinkPlan $clientDir $mainClientDir)
  $linked = @($plan | Where-Object { $_.Kind -eq "Dir" -and (Split-Path -Leaf $_.Path) -eq "node_modules" } |
      ForEach-Object { $_.Path.Substring($clientDir.TrimEnd("\").Length + 1) })
  # The marker comes first, so an interrupted run still counts as linked (never as an own
  # install that yarn could update through the junctions); "complete" is added at the end.
  $marker = Join-Path $clientDir "node_modules\$LinkMarkerName"
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $marker) | Out-Null
  @("main=$mainClientDir") + $linked | Set-Content -Encoding UTF8 $marker
  foreach ($action in $plan) {
    switch ($action.Kind) {
      "Dir" { New-Item -ItemType Directory -Force -Path $action.Path | Out-Null }
      "Junction" {
        if (Test-Path -LiteralPath $action.Target) {
          New-Item -ItemType Junction -Path $action.Path -Value $action.Target | Out-Null
        } else {
          Write-Warning "Not linking $($action.Path): $($action.Target) does not exist."
        }
      }
      # Keeps the file's time, so .yarn-state.yml still dates the main folder's install.
      "Copy" { Copy-Item -LiteralPath $action.Target -Destination $action.Path }
    }
  }
  Add-Content -Encoding UTF8 -LiteralPath $marker -Value "complete"
  return $plan.Count
}

# Deletes $path without ever following a junction or symlink: links are removed as links,
# so nothing in the main folder is touched. (Remove-Item -Recurse in Windows PowerShell
# 5.1 deletes a junction's target contents.)
function Remove-TreeNoFollow([string]$path) {
  $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
  if (-not $item) { return }
  if (Test-ReparsePoint $item) {
    if ($item.PSIsContainer) { [System.IO.Directory]::Delete($item.FullName, $false) } else { [System.IO.File]::Delete($item.FullName) }
    return
  }
  if ($item.PSIsContainer) {
    foreach ($child in Get-ChildItem -LiteralPath $item.FullName -Force) { Remove-TreeNoFollow $child.FullName }
    [System.IO.Directory]::Delete($item.FullName, $false)
  } else {
    $item.Attributes = [System.IO.FileAttributes]::Normal
    [System.IO.File]::Delete($item.FullName)
  }
}

# Removes the folders New-LinkedClientDeps made (their links and the worktree's own .cache).
function Remove-LinkedClientDeps([string]$clientDir) {
  $marker = Join-Path $clientDir "node_modules\$LinkMarkerName"
  if (-not (Test-Path -LiteralPath $marker)) { return }
  $linked = @(Get-Content -LiteralPath $marker | Where-Object { $_ -like "*node_modules" -and $_ -notlike "*..*" -and $_ -notlike "*:*" })
  # The packages' folders first, app\client\node_modules (with the marker) last.
  foreach ($relative in @($linked | Sort-Object { $_ -eq "node_modules" })) {
    Remove-TreeNoFollow (Join-Path $clientDir $relative)
  }
}
