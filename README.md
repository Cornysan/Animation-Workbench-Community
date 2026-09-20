# Animation Workbench Community Portal - Backend

Spring Boot (Kotlin) Server für das Community-Portal der Animation Workbench:
Clips als `.awclip` hochladen, finden, auf dem Mannequin ansehen, laden, melden.

Konzept, Plan und offene Entscheidungen: MotionLabs-Vault,
`3. Feature Planning/Community-Portal - Plan.md`.

> Dieser Branch ersetzt den früheren FBX-Upload vollständig. FBX-Dateien,
> Blender-Worker und Docker-Socket sind entfernt - das Portal nimmt nur noch
> rein deklarative Bewegungsdaten an (Konzept §3).

---

## Lokal starten

Voraussetzung: JDK 17.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Das `dev`-Profil nutzt eine H2-Datei unter `./data`, legt Blobs unter
`./data/blobs` ab und schaltet den **Entwickler-Login** ein - Anmeldung ohne
Discord:

```bash
curl -X POST localhost:8080/api/v1/dev/login -H 'Content-Type: application/json' -d '{"name":"pablo"}'
```

Die Antwort enthält ein Bearer-Token und setzt zugleich eine Browser-Session.
Swagger UI: http://localhost:8080/swagger-ui.html (nur im `dev`-Profil).

## Tests

```bash
./gradlew test
```

- `format/AwclipReaderTest` prüft den Validator gegen dieselben Testdateien
  wie der Unity-Client (`src/test/resources/awclip`) - gleiche Fehlercodes,
  gleiche Inhalts-Hashes.
- `PortalFlowTest` spielt die Abläufe gegen den echten Spring-Kontext durch:
  Upload, Duplikat, signierter Download, Meldung mit Auto-Hide, Entfernen mit
  Strike, Wiederupload-Sperre, Takedown ohne Konto, Kill Switch,
  Workbench-Anmeldung, Sperre, IP-Pseudonymisierung.

## Aufbau

| Paket | Inhalt |
|---|---|
| `format` | `.awclip`-Validator und Inhalts-Hash, Portierung von `Editor/Community/Format` der Workbench |
| `account`, `auth` | Konten, Discord-OAuth2 (Browser), Device Flow + Bearer-Token (Workbench), Entwickler-Login |
| `catalog` | Upload mit Erklärung, Duplikat- und Wiederupload-Sperre, Suche, Vorschau, signierte Download-Links |
| `moderation` | Melden → sofort AUTO_HIDDEN, Takedown-Formular ohne Konto, Admin-Entscheidungen, Strikes, Benachrichtigungen |
| `system` | Kill Switch, Audit-Log, Alarme (Discord-Webhook + Mail), IP-Pseudonymisierung |
| `storage` | Dateiablage außerhalb der Datenbank, nie öffentlich |

## Die Oberflaeche

Kein Framework, kein Build-Schritt fuer die Seiten: Thymeleaf liefert den
Rahmen, `static/assets/*.js` sind gewoehnliche Skripte, und die Content
Security Policy erlaubt nur Skripte von dieser Adresse.

| Adresse | Vorlage | |
|---|---|---|
| `/` | `landing.html` | Startseite: ein Clip auf der Figur, drei Schritte, die neuesten Clips. |
| `/browse.html` | `browse.html` | Der Katalog. Lag bis zur Startseite auf `/`. |
| `/clip.html?p=<slug>` | `clip.html` | Ein Clip. Diese Adresse steht in Discord-Vorschauen und Takedown-Mails - sie aendert sich nicht. |

### Die Figur im Viewer

Humanoide Clips laufen auf dem **Standard-Mannequin der Workbench** -
derselben `AW_Default_Mannequin.prefab`, die im Paket liegt. Der Plan hielt
bewusst kein Modell bereit (O7: "kein Modell, keine Bibliothek"), damit sich
die Rechtefrage an einer fremden Figur nicht stellt. An unserer eigenen stellt
sie sich auch jetzt nicht.

| Datei | |
|---|---|
| `static/models/aw-mannequin.glb` | Die Figur, 326 KiB. Erzeugt mit `docs-site/tools/unity-mesh-to-glb.py` im Workbench-Repo - hier liegt nur die Kopie. |
| `static/assets/stage.js` | Die Buehne: laedt das Modell, rechnet die Vorschau auf seine Knochen um, zeichnet Boden, Licht und Schatten. |
| `static/assets/viewer-ui.js` | Wiedergabe, Zeitleiste und die Sichtschalter drumherum. |
| `static/assets/viewer.js` | Das Strichmaennchen. Bleibt: im Katalog, als Skelettansicht und als Rueckfall ohne WebGL. |
| `static/assets/vendor/three.module.js` | three.js r186 plus GLTFLoader und SkeletonUtils, gebuendelt. |

Das Strichmaennchen zeichnet die **Karten** im Katalog, nicht die Figur: eine
Seite zeigt bis zu 24 Karten, und so viele WebGL-Kontexte gibt kein Browser
her. Die Figur steht dort, wo sie einzeln ist - auf der Clip-Seite und im Kopf
der Startseite.

**Warum die Vorschau umgerechnet werden muss.** Die Vorschau im `.awclip` ist
auf der Figur gebacken, die der Hochladende in der Workbench ausgewaehlt hat,
und bringt deren Achsenkonvention mit. Die Umrechnung bestimmt pro Knochen eine
feste Korrektur aus der Richtung zum Kindknochen - in beiden Rigs im lokalen
Raum bekannt. Bei gleicher Konvention ist sie die Einheit und das Bild exakt.
Der Kopf von `stage.js` erklaert es im Ganzen.

### three.js erneuern

Der Buendel ist ein erzeugtes Artefakt, kein Fremdcode zum Anfassen. Die
Einstiegsdatei steht daneben (`static/assets/vendor/entry.js`) und nennt genau
die Namen, die das Portal benutzt - alles andere faellt beim Buendeln weg.

```bash
# in einem Verzeichnis mit `three` in node_modules (z. B. dem docs-site-Repo)
npx esbuild entry.js --bundle --format=esm --minify --legal-comments=none   --target=es2020 --outfile=three.module.js
```

## Betrieb

Live-Adresse: **https://community.playmations.com**, auf demselben Server wie
die Doku-Site (SSH-Alias `linux`), hinter dem Caddy, der dort schon laeuft.
Der ganze Weg - DNS, Discord-Anwendung, `.env`, erstes Ausrollen, Sicherung,
Rueckfall, Not-Aus - steht in [`server/DEPLOY.md`](server/DEPLOY.md).

Alles Geheime kommt aus der Umgebung; `server/docker-compose.yml` liest
`/srv/aw-community/.env`, Vorlage ist `server/.env.example`.

| Variable | Zweck |
|---|---|
| `DB_PASSWORD` | PostgreSQL im Docker-Netz |
| `PORTAL_BASE_URL` | oeffentliche Adresse, `https://community.playmations.com` |
| `PORTAL_DOWNLOAD_SECRET` | HMAC fuer Download-Links (zufaellig, >= 32 Zeichen) |
| `PORTAL_PSEUDONYM_SECRET` | HMAC fuer IP-Pseudonyme (ein anderer!) |
| `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET` | Discord-Anwendung, Redirect `.../login/oauth2/code/discord`, Scope `identify` |
| `PORTAL_ADMIN_DISCORD_IDS` | komma-getrennte Discord-IDs der Moderation |
| `PORTAL_ALERT_WEBHOOK` | Discord-Webhook fuer Meldungen und Takedowns |
| `PORTAL_ALERT_MAIL_TO`, `_FROM`, `MAIL_*` | zweiter Alarmkanal per SMTP |
| `TAG` | Image-Marke, leer = `main` |

Nie in Produktion: `portal.dev-login=true` bzw. `PORTAL_DEV_LOGIN`.

## API (Kurzfassung)

Öffentlich: `GET /api/v1/status`, `GET /api/v1/packages?q=&tag=&license=&sort=new|popular`,
`GET /api/v1/packages/{slug}`, `GET /api/v1/packages/{slug}/preview`,
`POST /api/v1/packages/{slug}/download-link`, `GET /api/v1/files/{version}?exp=&sig=`,
`POST /api/v1/takedowns`, `POST /api/v1/auth/editor/start|poll`.

Angemeldet: `POST /api/v1/packages` (multipart `file` + Erklärung),
`POST /api/v1/packages/{slug}/versions`, `DELETE /api/v1/packages/{slug}`,
`POST /api/v1/packages/{slug}/reports`, `GET /api/v1/me`, `/me/packages`,
`/me/notifications`, `POST /api/v1/auth/editor/approve`.

Admin: `GET /api/v1/admin/cases`, `POST /api/v1/admin/packages/{slug}/restore|remove`,
`POST /api/v1/admin/reports/{id}/dismiss`, `POST /api/v1/admin/takedowns/{id}/resolve`,
`POST /api/v1/admin/accounts/{id}/status`, `POST /api/v1/admin/settings`, `GET /api/v1/admin/audit`.

Fehler haben immer die Form `{"error":{"code":"…","message":"…"}}`; die
Workbench entscheidet über `code`.
