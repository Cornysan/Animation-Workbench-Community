# Betrieb - www.playmations.com

Das Portal laeuft auf demselben Server wie die Doku-Site: Ubuntu, SSH-Alias
`linux` (207.180.226.248), Caddy auf 80/443. Dort liegen schon
`docs.`, `feedback.`, `hub.`, `lauf.` und `stocks.` als eigene Caddy-Bloecke.

**Caddy ist gesetzt.** Das Portal bringt deshalb kein eigenes nginx und kein
certbot mit - zwei Dienste auf Port 443 waeren ein Neustart weit davon
entfernt, die Doku-Site mit abzuschalten. Der Container bindet an
`127.0.0.1:8090`, Caddy holt das Zertifikat und reicht durch.

| Wo | Was |
|---|---|
| `/srv/aw-community/` | `docker-compose.yml`, `.env`, `backup.sh` |
| Volume `aw-community_postgres_data` | Datenbank |
| Volume `aw-community_blob_data` | hochgeladene `.awclip`-Dateien |
| `/var/backups/aw-community/` | taegliche Sicherung |
| `/etc/caddy/Caddyfile` | der Block aus `server/caddy/` |

Kein Git auf dem Server. Die CI baut das Image und legt es unter
`ghcr.io/cornysan/animation-workbench-community` ab (oeffentlich, kein Login
noetig). Marken: `main` = letzter Stand, `main-<sha>` = eine feste Fassung.

---

## Vor dem ersten Mal

1. **DNS.** Bei STRATO zeigen der nackte Name, `www` (als Alias darauf) und
   `community` auf `207.180.226.248`. Es gibt **kein Wildcard** auf
   `playmations.com`, auch wenn das frueher in `docs-site/DEPLOY.md` stand -
   jede Subdomain hat ihren eigenen Eintrag.
   Pruefen: `nslookup www.playmations.com` muss die IP zeigen.
   Kommt keine Adresse zurueck, bekommt Caddy kein Zertifikat.

2. **Discord-Anwendung** (Plan P1) anlegen:
   https://discord.com/developers/applications > New Application > OAuth2.
   Scope `identify`, Redirect-URI
   `https://www.playmations.com/login/oauth2/code/discord` -
   zeichengleich, sonst bricht der Login mit `redirect_uri mismatch` ab.
   Client-ID und Secret notieren.

   **GitHub und Google** sind freiwillig und kommen spaeter dazu, ohne
   Neuaufbau: App anlegen, zwei Zeilen in `.env`, `docker compose up -d`.
   Die Schritte stehen in `.env.example` (Rueckkehradresse jeweils
   `.../login/oauth2/code/github` bzw. `/google`). Keine Scopes einstellen:
   GitHub bekommt keinen, Google nur `userinfo.profile`.

3. **Eigene Discord-ID** (P4): in Discord den Entwicklermodus einschalten,
   Rechtsklick auf sich selbst > ID kopieren. Ohne sie kann niemand Meldungen
   bearbeiten.

4. **Alarm-Webhook** (P3): ein eigener Discord-Kanal, nicht der aus dem
   Feedback-Dienst - sonst liegen Bugreports und Rechtsmeldungen im selben
   Strom.

## Erstes Ausrollen

```bash
ssh linux
mkdir -p /srv/aw-community
```

Von hier aus hochladen:

```bash
scp server/docker-compose.yml server/backup.sh linux:/srv/aw-community/
scp server/.env.example linux:/srv/aw-community/.env
```

Dann auf dem Server die `.env` ausfuellen und zumachen:

```bash
ssh linux
cd /srv/aw-community
openssl rand -base64 48        # dreimal, fuer DB_PASSWORD und die zwei Schluessel
nano .env
chmod 600 .env
chmod +x backup.sh
docker compose up -d
docker compose logs -f portal   # bis "Started ... in ... seconds"
```

Die Tabellen legt Flyway beim ersten Start selbst an.

Caddy-Bloecke anhaengen (der Inhalt steht in
`server/caddy/www.playmations.com.caddy`):

```bash
cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak.$(date +%F-%H%M)
cat >> /etc/caddy/Caddyfile   # Bloecke einfuegen, dann Strg-D
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
```

**Die Bloecke sind schon drin, aber die Datei im Repo hat sich geaendert?**
Die Portal-Bloecke im Caddyfile durch die aus dem Repo ersetzen, nicht noch
einmal anhaengen - zwei Bloecke fuer denselben Namen lehnt `caddy validate`
ab. Die anderen Bloecke (docs. usw.) bleiben, wie sie sind.

Pruefen:

```bash
curl -s https://www.playmations.com/api/v1/status
curl -sI https://playmations.com/ | head -3                   # 301 auf www
curl -sI https://community.playmations.com/clip.html | head -3  # 301 auf www
curl -s https://community.playmations.com/api/v1/status       # antwortet selbst
```

## Umzug von community. auf www (2026-09-25)

Bis dahin wohnte das Portal auf `community.playmations.com`, der nackte Name
und `www` leiteten dorthin. Die Reihenfolge zaehlt, sonst geht die Anmeldung
kaputt: die Rueckkehradresse der Anbieter baut Spring aus dem Host, auf dem
die Anfrage ankommt.

1. **Bei den Anbietern die neue Rueckkehradresse DAZU eintragen**, die alte
   stehen lassen, bis alles laeuft:
   - Discord: Developer Portal > App > OAuth2 > Redirects >
     `https://www.playmations.com/login/oauth2/code/discord`
   - GitHub: Settings > Developer settings > OAuth Apps > App > Callback URL
     hinzufuegen: `https://www.playmations.com/login/oauth2/code/github`
     (GitHub nimmt inzwischen mehrere).
   - Google: Cloud Console > Credentials > OAuth-Client > Authorized redirect
     URIs: `https://www.playmations.com/login/oauth2/code/google`
2. Sicherung (`/srv/aw-community/backup.sh`), dann in der Server-`.env`
   `PORTAL_BASE_URL=https://www.playmations.com` und `docker compose up -d`.
3. Im Caddyfile den alten `community.`-Block und den Weiterleitungsblock
   `playmations.com, www.playmations.com` durch die drei Bloecke aus dem
   Repo ersetzen, `caddy validate`, `systemctl reload caddy`.
4. Pruefen wie oben, dann einmal mit jedem Anbieter anmelden.

Wer angemeldet war, ist danach abgemeldet: das Merk-Cookie gehoerte zum alten
Host. Die Homepage-/Datenschutz-Links in den Anbieter-Konsolen koennen
nachgezogen werden, muessen aber nicht - die alten leiten weiter.

Zuletzt die taegliche Sicherung eintragen:

```bash
crontab -e
# 4:20 statt 4:00: um Punkt laeuft die stuendliche Vault-Sicherung.
20 4 * * * /srv/aw-community/backup.sh >> /var/log/aw-community-backup.log 2>&1
```

Eingetragen am 2026-09-19 und unter cron-Bedingungen geprueft (`env -i
PATH=/usr/bin:/bin`) - das Skript ruft `docker`, und cron bringt nicht das
PATH mit, das eine Anmeldeschale hat.

## Aktualisieren

Die CI baut bei jedem Push nach `main`. Danach:

```bash
ssh linux 'cd /srv/aw-community && docker compose pull && docker compose up -d'
```

Nur die `.env` geaendert: `docker compose up -d` reicht, kein `pull`.

## Zurueckfallen

Jede Fassung liegt als eigene Marke im Registry. In der `.env`:

```
TAG=main-49061b8
```

Dann `docker compose up -d`. Marken auflisten geht ohne Login:

```bash
TOKEN=$(curl -s "https://ghcr.io/token?scope=repository:cornysan/animation-workbench-community:pull&service=ghcr.io" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -H "Authorization: Bearer $TOKEN" https://ghcr.io/v2/cornysan/animation-workbench-community/tags/list
```

**Achtung bei Schema-Aenderungen.** Flyway wandert nur vorwaerts. Ein Rueckfall
auf ein Image vor einer Migration startet nicht, weil die Datenbank eine
Fassung kennt, die das Image nicht hat. Dann erst die Sicherung
zuruecklesen, dann das alte Image.

## Sicherung zuruecklesen

Das muss einmal geuebt sein, bevor echte Uploads darin liegen (Plan L1):

```bash
cd /srv/aw-community
docker compose stop portal

gunzip -c /var/backups/aw-community/db-<stand>.sql.gz \
  | docker exec -i aw_community_db psql -U awcommunity -d awcommunity

docker run --rm -v aw-community_blob_data:/data \
  -v /var/backups/aw-community:/backup \
  alpine sh -c "rm -rf /data/* && tar xzf /backup/blobs-<stand>.tar.gz -C /data"

docker compose start portal
```

Danach im Browser eine Clip-Seite oeffnen und den Download ziehen: erst wenn
die Datei kommt, passen Datenbank und Blobs wieder zusammen.

## Not-Aus

Der Kill Switch schaltet Uploads und Downloads ab, ohne dass jemand an den
Server muss - Admin-Anmeldung, dann `POST /api/v1/admin/settings` oder der
Schalter in der Admin-Ansicht. Das Portal bleibt erreichbar und erklaert sich.

Ganz aus:

```bash
ssh linux 'cd /srv/aw-community && docker compose down'
```

Caddy antwortet dann mit 502. Soll auch das weg, den Block aus dem Caddyfile
nehmen und neu laden.

## Wenn etwas klemmt

| Zeichen | Woran es meist liegt |
|---|---|
| Caddy bekommt kein Zertifikat | A-Record fehlt oder zeigt woanders hin. `journalctl -u caddy -n 50` |
| 502 von Caddy | Container laeuft nicht oder haengt beim Start. `docker compose logs portal` |
| Login endet mit `redirect_uri mismatch` | `PORTAL_BASE_URL` und die Redirect-URI in der Discord-Anwendung weichen ab |
| Upload bricht bei grossen Dateien ab | `request_body max_size` in Caddy und `max-file-size` in `application.yaml` muessen zueinander passen |
| Container startet neu im Kreis | fehlende Pflichtwerte in der `.env` - Compose nennt den ersten fehlenden beim Start |
