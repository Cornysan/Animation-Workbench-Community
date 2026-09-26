<#
.SYNOPSIS
  Probt die Ruecksicherung: der neueste Stand in eine Wegwerf-Datenbank.

.DESCRIPTION
  Eine Sicherung, die nie zurueckgelesen wurde, ist eine Hoffnung. Dieses
  Skript startet einen leeren Postgres-Container (dieselbe Hauptversion wie
  auf dem Server), spielt die neueste db-*.sql.gz aus backup/data/server ein,
  zaehlt die wichtigsten Tabellen und prueft, dass jede Clip-Datei, auf die
  die Datenbank zeigt (package_version.blob_key / preview_blob_key), im
  neuesten Blob-Archiv steht. Danach wird der Container wieder entfernt.

  Der Server wird dabei nicht angefasst. Braucht Docker Desktop.
  Die echte Ruecksicherung auf dem Server steht in server/DEPLOY.md.
#>
param(
  [string]$Image = 'postgres:18'
)

# 'Continue', nicht 'Stop': Windows PowerShell 5.1 macht aus jeder Zeile, die
# ein Programm auf stderr schreibt, einen Abbruch, sobald sie umgeleitet wird -
# auch aus einem harmlosen "No such container" beim Aufraeumen. Geprueft wird
# statt dessen der Exit-Code jedes Aufrufs (Invoke-Docker, Get-Scalar).
$ErrorActionPreference = 'Continue'
$source = Join-Path $PSScriptRoot 'data\server'
$tar = Join-Path $env:SystemRoot 'System32\tar.exe'
$name = 'aw-restore-test'

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') endete mit Code $LASTEXITCODE" }
}

function Get-Scalar([string]$sql) {
  $value = & docker exec $name psql -U awcommunity -d awcommunity -At -c $sql
  if ($LASTEXITCODE -ne 0) { throw "Abfrage fehlgeschlagen: $sql" }
  return ($value | Out-String).Trim()
}

$db = Get-ChildItem -Path $source -Filter 'db-*.sql.gz' | Sort-Object Name -Descending | Select-Object -First 1
$blobs = Get-ChildItem -Path $source -Filter 'blobs-*.tar.gz' | Sort-Object Name -Descending | Select-Object -First 1
if (-not $db -or -not $blobs) { throw "Keine Sicherung in $source - zuerst pull-backup.ps1 laufen lassen." }
Write-Host "Probe mit $($db.Name) und $($blobs.Name)"

& docker rm -f $name 2>$null | Out-Null
try {
  Invoke-Docker run -d --name $name -e POSTGRES_USER=awcommunity -e POSTGRES_PASSWORD=restoretest `
    -e POSTGRES_DB=awcommunity $Image | Out-Null

  $ready = $false
  for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
    Start-Sleep -Seconds 1
    & docker exec $name pg_isready -U awcommunity -d awcommunity 2>$null | Out-Null
    $ready = ($LASTEXITCODE -eq 0)
  }
  if (-not $ready) { throw 'Postgres wurde nicht bereit.' }
  # pg_isready meldet sich schon, waehrend das Image noch seine Startskripte
  # abarbeitet und einmal neu startet - kurz warten, dann erst einspielen.
  Start-Sleep -Seconds 3

  # Die Datei geht in den Container und wird DORT entpackt: eine Pipe durch
  # Windows PowerShell 5.1 wuerde die Bytes als Text umkodieren.
  Invoke-Docker cp $db.FullName "${name}:/tmp/db.sql.gz"
  Invoke-Docker exec $name sh -c 'gunzip -c /tmp/db.sql.gz | psql -q -v ON_ERROR_STOP=1 -U awcommunity -d awcommunity > /dev/null'

  $counts = [ordered]@{}
  foreach ($table in 'account', 'animation_package', 'package_version', 'package_comment', 'collection', 'clip_pack', 'notification') {
    $counts[$table] = [int](Get-Scalar "select count(*) from $table")
  }
  $schema = Get-Scalar 'select max(version::int) from flyway_schema_history where success'

  # Jede Datei, auf die die Datenbank zeigt, muss im Archiv stehen.
  $keys = (Get-Scalar "select blob_key from package_version union select preview_blob_key from package_version where preview_blob_key is not null") -split "`r?`n" |
    Where-Object { $_ }
  $archived = @{}
  & $tar -tzf $blobs.FullName | ForEach-Object { if ($_ -match '([0-9a-f]{32})$') { $archived[$Matches[1]] = $true } }
  if ($LASTEXITCODE -ne 0) { throw "$($blobs.Name) laesst sich nicht lesen." }
  $missing = @($keys | Where-Object { -not $archived.ContainsKey($_) })

  Write-Host ''
  Write-Host "Schema-Version: V$schema"
  $counts.GetEnumerator() | ForEach-Object { Write-Host ('  {0,-18} {1,6}' -f $_.Key, $_.Value) }
  Write-Host ("Dateien: {0} in der Datenbank genannt, {1} im Archiv, {2} fehlen" -f $keys.Count, $archived.Count, $missing.Count)
  if ($missing.Count -gt 0) { throw "Im Blob-Archiv fehlen: $($missing -join ', ')" }
  if ($counts['account'] -eq 0) { throw 'Die Datenbank ist leer.' }
  Write-Host ''
  Write-Host 'Ruecksicherung gelungen.'
} finally {
  & docker rm -f $name 2>$null | Out-Null
}
