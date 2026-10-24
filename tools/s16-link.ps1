<#
  Grafit · lien local S-16 → Grafit Mobile (page 6.4 S-16)

  Diffuse l'état du PC sur le réseau local, en UDP (port 47616), vers l'adresse de diffusion de chaque carte réseau
  active : rien n'est envoyé sur Internet, aucun port n'est ouvert en entrée sur le PC. Le téléphone écoute quand
  Grafit est au premier plan.

    toutes les 5 s  : {"v":1,"k":"s","pc":…,"t":…,"cpu":{"l":%, "t":°C},"gpu":{"l":%, "t":°C, "mu":Mo, "mt":Mo, "p":W},
                       "ram":{"u":Go, "t":Go},"disk":[["C",% libre],…],"up":s}
    toutes les 60 s : {"v":1,"k":"h","pc":…,"t0":…,"step":60,"ct":[…],"gt":[…],"cl":[…],"gl":[…]}   (2 h, à la minute)

  Température CPU : lue dans HWiNFO si « Report value in Gadget » est coché pour la ligne CPU (Tctl/Tdie ou Package) ;
  sinon absente. GPU : nvidia-smi (pilote NVIDIA).

  Utilisation (PowerShell) :
    .\s16-link.ps1              lance la diffusion dans cette fenêtre (Ctrl+C pour arrêter)
    .\s16-link.ps1 -Install     démarrage automatique à l'ouverture de session (tâche planifiée, fenêtre cachée)
    .\s16-link.ps1 -Uninstall   retire la tâche planifiée
    .\s16-link.ps1 -Test        affiche un paquet d'état et les adresses de diffusion, sans rien envoyer

  Si PowerShell refuse le script (stratégie d'exécution, fichier téléchargé) :
    Unblock-File .\s16-link.ps1
    powershell -ExecutionPolicy Bypass -File .\s16-link.ps1 -Install
#>
param([switch]$Install, [switch]$Uninstall, [switch]$Test, [int]$Port = 47616, [int]$Every = 5)

$TaskName = 'Grafit S-16 link'
if ($Install) {
  $ps = (Get-Command powershell.exe).Source
  $act = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
  $trg = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
  $set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
  Register-ScheduledTask -TaskName $TaskName -Action $act -Trigger $trg -Settings $set -Description 'Diffuse l''état du S-16 vers Grafit Mobile sur le réseau local (UDP 47616).' -Force | Out-Null
  Start-ScheduledTask -TaskName $TaskName
  Write-Host "Tâche « $TaskName » installée et lancée."
  return
}
if ($Uninstall) {
  Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "Tâche « $TaskName » retirée."
  return
}

$ErrorActionPreference = 'SilentlyContinue'
$inv = [Globalization.CultureInfo]::InvariantCulture

# adresses de diffusion des cartes actives (hors boucle locale, APIPA et cartes virtuelles sans passerelle)
function Get-Targets {
  $out = @()
  foreach ($a in Get-NetIPAddress -AddressFamily IPv4) {
    if ($a.IPAddress -like '127.*' -or $a.IPAddress -like '169.254.*') { continue }
    $cfg = Get-NetIPConfiguration -InterfaceIndex $a.InterfaceIndex
    if (-not $cfg.IPv4DefaultGateway) { continue }
    $ip = [Net.IPAddress]::Parse($a.IPAddress).GetAddressBytes()
    $len = [int]$a.PrefixLength; $bc = New-Object byte[] 4
    for ($i = 0; $i -lt 4; $i++) {
      $bits = [Math]::Max(0, [Math]::Min(8, $len - 8 * $i))
      $mask = [byte]((0xFF -shl (8 - $bits)) -band 0xFF)
      $bc[$i] = [byte]($ip[$i] -bor (-bnot $mask -band 0xFF))
    }
    $out += [Net.IPEndPoint]::new([Net.IPAddress]::new($bc), $Port)
  }
  if (-not $out) { $out = @([Net.IPEndPoint]::new([Net.IPAddress]::Broadcast, $Port)) }
  $out
}

function Get-CpuTemp {
  # HWiNFO : HKCU\Software\HWiNFO64\VSB (Label#, ValueRaw#) quand « Report value in Gadget » est coché
  $k = Get-ItemProperty 'HKCU:\Software\HWiNFO64\VSB'
  if (-not $k) { return $null }
  $best = $null
  foreach ($p in $k.PSObject.Properties) {
    if ($p.Name -notmatch '^Label(\d+)$') { continue }
    $i = $Matches[1]; $lab = [string]$p.Value
    if ($lab -match 'Tctl|Tdie|CPU Package|CPU \(Tctl') {
      $raw = $k."ValueRaw$i"
      if ($raw) { $v = 0.0; if ([double]::TryParse(($raw -replace ',', '.'), [Globalization.NumberStyles]::Float, $inv, [ref]$v)) { $best = $v; break } }
    }
  }
  $best
}

function Get-Gpu {
  $smi = "$env:SystemRoot\System32\nvidia-smi.exe"
  if (-not (Test-Path $smi)) { return $null }
  $l = & $smi --query-gpu=temperature.gpu,utilization.gpu,memory.used,memory.total,power.draw --format=csv,noheader,nounits 2>$null | Select-Object -First 1
  if (-not $l) { return $null }
  $f = $l -split ',\s*'
  $n = { param($s) $v = 0.0; if ([double]::TryParse($s, [Globalization.NumberStyles]::Float, $inv, [ref]$v)) { $v } else { $null } }
  @{ t = (& $n $f[0]); l = (& $n $f[1]); mu = (& $n $f[2]); mt = (& $n $f[3]); p = (& $n $f[4]) }
}

$boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
function Get-State {
  $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $cpuL = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime
  $os = Get-CimInstance Win32_OperatingSystem
  $ramT = [Math]::Round($os.TotalVisibleMemorySize / 1MB, 1); $ramU = [Math]::Round(($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / 1MB, 1)
  $disks = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object { , @($_.DeviceID.TrimEnd(':'), [Math]::Round(100.0 * [double]$_.FreeSpace / [Math]::Max(1.0, [double]$_.Size))) })
  @{ v = 1; k = 's'; pc = $env:COMPUTERNAME; t = $now; up = [int]((Get-Date) - $boot).TotalSeconds
     cpu = @{ l = $cpuL; t = (Get-CpuTemp) }; gpu = (Get-Gpu); ram = @{ u = $ramU; t = $ramT }; disk = $disks }
}

if ($Test) {
  $ErrorActionPreference = 'Continue'
  Write-Host "Paquet d'état (port UDP $Port) :"
  Get-State | ConvertTo-Json -Compress -Depth 4 | Write-Host
  Write-Host 'Adresses de diffusion :'
  Get-Targets | ForEach-Object { Write-Host ('  ' + $_.ToString()) }
  if ($null -eq (Get-CpuTemp)) { Write-Host 'CPU °C absent : HWiNFO64 > Sensors > ligne CPU (Tctl/Tdie ou Package) > clic droit > Report value in Gadget.' }
  return
}

$udp = New-Object Net.Sockets.UdpClient
$udp.EnableBroadcast = $true
$targets = Get-Targets; $tgtT = Get-Date
$H = @{ ct = New-Object Collections.ArrayList; gt = New-Object Collections.ArrayList; cl = New-Object Collections.ArrayList; gl = New-Object Collections.ArrayList }
$acc = @{ ct = @(); gt = @(); cl = @(); gl = @() }
$minute = [int][Math]::Floor(([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) / 60)

function Send($obj) {
  $b = [Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress -Depth 4))
  foreach ($ep in $targets) { [void]$udp.Send($b, $b.Length, $ep) }
}
function Avg($a) { if ($a.Count) { [Math]::Round((($a | Measure-Object -Average).Average), 1) } else { $null } }

while ($true) {
  if (((Get-Date) - $tgtT).TotalMinutes -ge 5) { $targets = Get-Targets; $tgtT = Get-Date }      # réseau changé
  $st = Get-State; Send $st
  $now = $st.t; $cpuT = $st.cpu.t; $cpuL = $st.cpu.l; $gpu = $st.gpu
  if ($cpuT -ne $null) { $acc.ct += $cpuT }; if ($cpuL -ne $null) { $acc.cl += [double]$cpuL }
  if ($gpu) { if ($gpu.t -ne $null) { $acc.gt += $gpu.t }; if ($gpu.l -ne $null) { $acc.gl += $gpu.l } }
  $m = [int][Math]::Floor($now / 60)
  if ($m -ne $minute) {                                    # moyenne de la minute écoulée → historique 2 h → diffusion
    foreach ($k in 'ct', 'gt', 'cl', 'gl') { [void]$H[$k].Add((Avg $acc[$k])); while ($H[$k].Count -gt 120) { $H[$k].RemoveAt(0) }; $acc[$k] = @() }
    $minute = $m
    Send @{ v = 1; k = 'h'; pc = $env:COMPUTERNAME; t0 = ($m - $H.ct.Count) * 60; step = 60; ct = @($H.ct); gt = @($H.gt); cl = @($H.cl); gl = @($H.gl) }
  }
  Start-Sleep -Seconds $Every
}
