<#
.SYNOPSIS
  Richtet die taegliche Sicherung in der Windows-Aufgabenplanung ein.

.DESCRIPTION
  Eine Aufgabe fuer den angemeldeten Benutzer, die backup/pull-backup.ps1
  einmal am Tag startet. War der PC zur geplanten Zeit aus, laeuft sie, sobald
  er wieder an ist (StartWhenAvailable). Ohne Fenster: conhost --headless.

  Erneut aufrufen ersetzt die Aufgabe (-Force). Entfernen:
    Unregister-ScheduledTask -TaskName 'Playmations Portal Backup'
#>
param(
  # Der Server sichert um 04:20; mittags ist der PC eher an als nachts.
  [string]$Time = '12:30',
  [string]$TaskName = 'Playmations Portal Backup'
)

$ErrorActionPreference = 'Stop'

$script = Join-Path $PSScriptRoot 'pull-backup.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$conhost = Join-Path $env:SystemRoot 'System32\conhost.exe'

$action = New-ScheduledTaskAction -Execute $conhost `
  -Argument "--headless `"$powershell`" -NoProfile -ExecutionPolicy Bypass -File `"$script`"" `
  -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Daily -At $Time
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
  -Principal $principal -Force `
  -Description 'Holt die Sicherungen von www.playmations.com auf diesen PC (backup/pull-backup.ps1).' | Out-Null

Write-Host "Aufgabe '$TaskName' eingerichtet: taeglich $Time, nachgeholt, wenn der PC aus war."
Write-Host "Sofort ausprobieren: Start-ScheduledTask -TaskName '$TaskName'"
