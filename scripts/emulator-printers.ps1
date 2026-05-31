<#
.SYNOPSIS
  Seed (or clear) saved-printer entries for the flashforge-emulator-v2 over adb.

.DESCRIPTION
  Separate from scripts/seed_printers.py (which re-pairs the maintainer's two REAL printers).
  This adds DB entries for the five emulator models so they sit ready in the app's Printers list.

  Seeding does NOT auto-connect and does NOT arm startup-reconnect — entries just appear in the
  list; you connect the one you want manually. Only one emulator runs at a time on the shared
  hardcoded ports (HTTP 8898 / TCP 8899), so only that model will actually connect.

  All five entries point at your PC's LAN IP (auto-detected 192.x, or pass -Ip), because the
  Android emulator reaches the flashforge-emulator-v2 over the LAN.

  Requires a DEBUG build installed (the seed intent is a no-op in release) and adb on PATH.

.PARAMETER Action
  seed  (default) — insert the five emulator printer entries.
  clear           — remove only those five entries; real printers are left untouched.

.PARAMETER Ip
  PC LAN IPv4 the emulator binds to. Auto-detected (first 192.168.x.x) if omitted.

.PARAMETER CheckCode
  Check-code stored for the modern (5M/5M Pro/5X) entries. Must match the --check-code you launch
  the emulator instance with. Ignored by legacy A3/A4. Default: 123456.

.PARAMETER Device
  adb device serial. Auto-detected when exactly one device is connected.

.EXAMPLE
  .\scripts\emulator-printers.ps1                       # seed all five at the auto-detected LAN IP
  .\scripts\emulator-printers.ps1 -Ip 192.168.1.117
  .\scripts\emulator-printers.ps1 -Action clear         # remove the five emulator entries
#>
[CmdletBinding()]
param(
  [ValidateSet('seed', 'clear')]
  [string]$Action = 'seed',
  [string]$Ip,
  [string]$CheckCode = '123456',
  [string]$Device
)

$ErrorActionPreference = 'Stop'

$AppId    = 'me.ghost.ffui'
$Activity = "$AppId/.MainActivity"

# Canonical emulator printer table. `model` is the flashforge-emulator-v2 --model id.
$Printers = @(
  [pscustomobject]@{ model = 'adventurer-3';      serial = 'EMU-ADV3';  name = 'Emu Adventurer 3' }
  [pscustomobject]@{ model = 'adventurer-4';      serial = 'EMU-ADV4';  name = 'Emu Adventurer 4' }
  [pscustomobject]@{ model = 'adventurer-5m';     serial = 'EMU-5M';    name = 'Emu Adventurer 5M' }
  [pscustomobject]@{ model = 'adventurer-5m-pro'; serial = 'EMU-5MPRO'; name = 'Emu Adventurer 5M Pro' }
  [pscustomobject]@{ model = 'adventurer-5x';     serial = 'EMU-AD5X';  name = 'Emu AD5X' }
)

function Resolve-Device {
  param([string]$Requested)
  $lines = (& adb devices) -split "`r?`n" | Select-Object -Skip 1
  $devices = foreach ($ln in $lines) {
    if ($ln.Trim() -and $ln -match "^(\S+)\s+device$") { $Matches[1] }
  }
  if ($Requested) {
    if ($devices -notcontains $Requested) { throw "Device '$Requested' not found. Connected: $($devices -join ', ')" }
    return $Requested
  }
  if (-not $devices) { throw 'No adb devices/emulators connected.' }
  if ($devices.Count -gt 1) { throw "Multiple devices connected: $($devices -join ', '). Pass -Device." }
  return $devices[0]
}

function Resolve-LanIp {
  $cand = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -like '192.168.*' -and $_.IPAddress -ne '127.0.0.1' } |
    Select-Object -First 1
  if (-not $cand) { throw 'Could not auto-detect a 192.168.x.x address. Pass -Ip explicitly.' }
  return $cand.IPAddress
}

function ConvertTo-B64 {
  param([Parameter(Mandatory)] $Object)
  $jsonStr = ConvertTo-Json -InputObject @($Object) -Depth 5 -Compress
  return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($jsonStr))
}

$dev = Resolve-Device -Requested $Device

if ($Action -eq 'seed') {
  if (-not $Ip) { $Ip = Resolve-LanIp }
  $seed = foreach ($p in $Printers) {
    [pscustomobject]@{ serial = $p.serial; ip = $Ip; name = $p.name; checkCode = $CheckCode }
  }
  $b64 = ConvertTo-B64 -Object $seed
  Write-Host "-> seeding $($seed.Count) emulator printer(s) at $Ip into $dev ..."
  # Force-stop first: am start won't re-deliver the intent (onNewIntent) to an already-foreground
  # standard-launch activity, so the seed would silently no-op without this.
  & adb -s $dev shell am force-stop $AppId | Out-Null
  & adb -s $dev shell am start -n $Activity --es seed_b64 $b64 | Out-Null
  Write-Host "Done. They appear in the Printers list (no auto-connect)." -ForegroundColor Green
  Write-Host ""
  Write-Host "To bring one online, launch the matching emulator instance, e.g.:" -ForegroundColor Cyan
  foreach ($p in $Printers) {
    Write-Host ("  {0,-22} npx tsx scripts/headless/run-instance.ts --model {1} --serial {2} --check-code {3} --tcp-port 8899 --http-port 8898" -f $p.name, $p.model, $p.serial, $CheckCode)
  }
}
else {
  $serials = $Printers | ForEach-Object { $_.serial }
  $b64 = ConvertTo-B64 -Object $serials
  Write-Host "-> removing $($serials.Count) emulator printer entr(ies) from $dev (real printers untouched) ..."
  # Force-stop first (see seed branch) so the intent is processed on a fresh onCreate.
  & adb -s $dev shell am force-stop $AppId | Out-Null
  & adb -s $dev shell am start -n $Activity --es unseed_b64 $b64 | Out-Null
  Write-Host "Done." -ForegroundColor Green
}
