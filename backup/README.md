# Sicherung auf dem PC

Der Server sichert jede Nacht um 04:20 Datenbank und Clip-Dateien nach
`/var/backups/aw-community` (`server/backup.sh`, 14 Tage). Das liegt auf
**derselben Maschine** - faellt sie aus, sind Daten und Sicherungen zusammen
weg. Deshalb holt dieser PC die Staende taeglich zu sich.

| Pfad | Was | Im Repo? |
|---|---|---|
| `pull-backup.ps1` | holt die Staende per rclone (SFTP) und prueft den neuesten | ja |
| `install-task.ps1` | richtet die taegliche Aufgabe in der Windows-Aufgabenplanung ein | ja |
| `restore-test.ps1` | spielt den neuesten Stand probehalber in eine Wegwerf-Datenbank ein (Docker) | ja |
| `rclone/rclone.exe` | rclone, Kopie aus `C:\Tools\rclone` | nein |
| `rclone/rclone.conf` | Zugang zum Server (Adresse, Benutzer, Pfad zum SSH-Schluessel) | nein |
| `data/server/` | die Sicherungen selbst - personenbezogene Daten | nein |
| `logs/` | `pull-backup.log`, `last-run.json` | nein |

`rclone/`, `data/` und `logs/` stehen in der `.gitignore`: die Sicherungen
enthalten Konten, Kommentare und IP-Adressen, und der Zugang zeigt auf einen
Root-Schluessel. Nichts davon gehoert in ein oeffentliches Repo.

## Was ein Lauf tut

1. `rclone copy` holt neue `db-*.sql.gz` und `blobs-*.tar.gz`. `copy` loescht
   hier nie etwas, auch wenn der Server alte Staende wegraeumt.
2. Der neueste Datenbank-Stand wird ganz entpackt und muss mit der
   Abschlusszeile von `pg_dump` enden - ein abgebrochener Dump hat sie nicht.
3. Das neueste Blob-Archiv muss sich vollstaendig lesen lassen.
4. Der neueste Stand darf hoechstens 36 Stunden alt sein - sonst steht der Cron
   auf dem Server.
5. Lokal bleiben 90 Tage, mindestens aber die neuesten sieben Staende.

Schlaegt etwas fehl, erscheint eine Windows-Benachrichtigung, und in
`logs/last-run.json` steht `"ok": false` mit dem Grund.

## Einrichten auf einem neuen PC

```powershell
# rclone hierher kopieren (oder neu laden: https://rclone.org/downloads/)
Copy-Item C:\Tools\rclone\rclone.exe backup\rclone\

# Zugang anlegen - derselbe Schluessel wie fuer `ssh linux`
backup\rclone\rclone.exe config create aw-server sftp `
  host 207.180.226.248 user root key_file $HOME\.ssh\linux `
  known_hosts_file $HOME\.ssh\known_hosts shell_type unix `
  --config backup\rclone\rclone.conf

# einmal von Hand, dann taeglich
powershell -ExecutionPolicy Bypass -File backup\pull-backup.ps1 -Quiet
powershell -ExecutionPolicy Bypass -File backup\install-task.ps1
```

Die Aufgabe laeuft taeglich um 12:30 (mittags ist der PC eher an als um 04:20)
und wird nachgeholt, wenn er zu der Zeit aus war.

## Zurueckspielen

- **Probe, ohne den Server anzufassen:** `restore-test.ps1` (Docker Desktop
  muss laufen). Zaehlt Konten, Clips, Kommentare und prueft, dass jede Datei,
  auf die die Datenbank zeigt, im Archiv liegt.
- **Echt, auf dem Server:** `server/DEPLOY.md`, Abschnitt "Sicherung
  zuruecklesen". Die beiden Dateien vorher hochladen, z. B.
  `backup\rclone\rclone.exe copy backup\data\server\db-<stand>.sql.gz aw-server:/var/backups/aw-community --config backup\rclone\rclone.conf`.
