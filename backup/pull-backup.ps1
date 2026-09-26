<#
.SYNOPSIS
  Holt die Sicherungen des Portal-Servers auf diesen PC und prueft sie.

.DESCRIPTION
  Der Server sichert jede Nacht um 04:20 (server/backup.sh) nach
  /var/backups/aw-community - auf DERSELBEN Maschine. Faellt die aus, sind
  Daten und Sicherungen zusammen weg. Dieses Skript zieht die Staende per
  rclone (SFTP) nach backup/data/server und prueft den neuesten:

    - die Datenbank-Sicherung laesst sich ganz entpacken und endet mit der
      Abschlusszeile von pg_dump (ein abgebrochener Dump hat sie nicht),
    - das Blob-Archiv laesst sich vollstaendig lesen,
    - beide sind hoechstens -MaxAgeHours alt (sonst ist der Cron auf dem
      Server stehen geblieben).

  Lokal bleiben Staende -KeepDays Tage, mindestens aber die neuesten sieben -
  auch wenn der PC laenger aus war, loescht ein Lauf nie die letzten Staende.
  `rclone copy` loescht hier nie etwas, was auf dem Server fehlt.

  Ergebnis: backup/logs/pull-backup.log (fortlaufend) und
  backup/logs/last-run.json (der letzte Lauf). Schlaegt etwas fehl, endet das
  Skript mit Code 1 und meldet sich mit einer Windows-Benachrichtigung.

  Einrichten: backup/install-task.ps1. Was wo liegt: backup/README.md.
#>
param(
  [int]$KeepDays = 90,
  [int]$MaxAgeHours = 36,
  # Keine Benachrichtigung - zum Ausprobieren von Hand.
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$root   = $PSScriptRoot
$rclone = Join-Path $root 'rclone\rclone.exe'
$config = Join-Path $root 'rclone\rclone.conf'
$target = Join-Path $root 'data\server'
$logs   = Join-Path $root 'logs'
$log    = Join-Path $logs 'pull-backup.log'
$status = Join-Path $logs 'last-run.json'
$remote = 'aw-server:/var/backups/aw-community'
$tar    = Join-Path $env:SystemRoot 'System32\tar.exe'

New-Item -ItemType Directory -Force -Path $target, $logs | Out-Null

function Write-Log([string]$message) {
  $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message
  Add-Content -Path $log -Value $line -Encoding UTF8
  Write-Host $line
}

# Eine Benachrichtigung unten rechts. Nur als Zugabe: klappt sie nicht (keine
# Sitzung, gesperrte Benachrichtigungen), bleibt der Eintrag im Log.
function Show-Toast([string]$title, [string]$text) {
  if ($Quiet) { return }
  try {
    [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
    [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime]
    $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
    $escape = { param($s) [Security.SecurityElement]::Escape($s) }
    $xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>$(& $escape $title)</text><text>$(& $escape $text)</text></binding></visual></toast>")
    $app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe'
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show(
      [Windows.UI.Notifications.ToastNotification]::new($xml))
  } catch {
    Write-Log "Benachrichtigung ging nicht: $($_.Exception.Message)"
  }
}

# Eine .gz ganz entpacken (ohne sie auf die Platte zu schreiben) und die
# letzten Bytes zurueckgeben. Ein abgeschnittenes Archiv wirft hier.
function Read-GzipTail([string]$path, [int]$keep = 4096) {
  $file = [IO.File]::OpenRead($path)
  try {
    $gzip = New-Object IO.Compression.GZipStream($file, [IO.Compression.CompressionMode]::Decompress)
    $buffer = New-Object byte[] 65536
    $previous = New-Object byte[] 0
    $current = New-Object byte[] 0
    $total = 0L
    while (($n = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
      $total += $n
      $previous = $current
      $current = New-Object byte[] $n
      [Array]::Copy($buffer, $current, $n)
    }
    $joined = New-Object byte[] ($previous.Length + $current.Length)
    [Array]::Copy($previous, 0, $joined, 0, $previous.Length)
    [Array]::Copy($current, 0, $joined, $previous.Length, $current.Length)
    $start = [Math]::Max(0, $joined.Length - $keep)
    return @{ Bytes = $total; Tail = [Text.Encoding]::UTF8.GetString($joined, $start, $joined.Length - $start) }
  } finally {
    $file.Dispose()
  }
}

$result = [ordered]@{ time = (Get-Date).ToString('s'); ok = $false; message = ''; newestDb = $null; newestBlobs = $null }

try {
  if (-not (Test-Path $rclone)) { throw "rclone fehlt: $rclone (siehe backup/README.md)" }
  if (-not (Test-Path $config)) { throw "Zugang fehlt: $config (siehe backup/README.md)" }

  Write-Log "Hole $remote"
  & $rclone copy $remote $target --config $config `
    --include 'db-*.sql.gz' --include 'blobs-*.tar.gz' `
    --retries 3 --low-level-retries 5 --contimeout 20s --timeout 60s `
    --log-file $log --log-level NOTICE --stats 0
  if ($LASTEXITCODE -ne 0) { throw "rclone copy endete mit Code $LASTEXITCODE" }

  # ---- Pruefen: der neueste Stand je Art --------------------------------
  $files = Get-ChildItem -Path $target -File |
    Where-Object { $_.Name -match '^(db|blobs)-\d{4}-\d{2}-\d{2}-\d{4}\.(sql|tar)\.gz$' }
  $db = $files | Where-Object Name -like 'db-*' | Sort-Object Name -Descending | Select-Object -First 1
  $blobs = $files | Where-Object Name -like 'blobs-*' | Sort-Object Name -Descending | Select-Object -First 1
  if (-not $db) { throw 'Keine Datenbank-Sicherung gefunden.' }
  if (-not $blobs) { throw 'Kein Blob-Archiv gefunden.' }
  $result.newestDb = $db.Name
  $result.newestBlobs = $blobs.Name

  $dump = Read-GzipTail $db.FullName
  if ($dump.Tail -notmatch 'PostgreSQL database dump complete') {
    throw "$($db.Name) ist unvollstaendig: die Abschlusszeile von pg_dump fehlt."
  }

  $entries = & $tar -tzf $blobs.FullName
  if ($LASTEXITCODE -ne 0) { throw "$($blobs.Name) laesst sich nicht lesen (tar Code $LASTEXITCODE)." }
  $blobCount = @($entries | Where-Object { $_ -match '[0-9a-f]{32}$' }).Count

  $age = (Get-Date) - $db.LastWriteTime
  if ($age.TotalHours -gt $MaxAgeHours) {
    throw ("Der neueste Stand ist {0:N0} Stunden alt - laeuft der Cron auf dem Server noch? " +
      "(ssh linux 'tail /var/log/aw-community-backup.log')") -f $age.TotalHours
  }

  # ---- Aufraeumen: alt UND nicht unter den neuesten sieben --------------
  $cutoff = (Get-Date).AddDays(-$KeepDays)
  $removed = 0
  foreach ($kind in 'db', 'blobs') {
    $files | Where-Object Name -like "$kind-*" | Sort-Object Name -Descending |
      Select-Object -Skip 7 | Where-Object { $_.LastWriteTime -lt $cutoff } |
      ForEach-Object { Remove-Item -LiteralPath $_.FullName; $removed++ }
  }

  $result.ok = $true
  $result.message = ('{0} ({1:N0} KB Daten entpackt), {2} ({3} Dateien); {4} alte Staende entfernt' -f
    $db.Name, ($dump.Bytes / 1KB), $blobs.Name, $blobCount, $removed)
  Write-Log "OK: $($result.message)"
} catch {
  $result.message = $_.Exception.Message
  Write-Log "FEHLER: $($result.message)"
  Show-Toast 'Portal-Sicherung fehlgeschlagen' $result.message
}

$result | ConvertTo-Json | Set-Content -Path $status -Encoding UTF8
if (-not $result.ok) { exit 1 }
