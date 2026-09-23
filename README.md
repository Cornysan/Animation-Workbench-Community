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
- `ProfileFlowTest` und `CollectionFlowTest` nehmen sich die zwei Dinge vor,
  die ein Portal zu einer Gemeinschaft machen: Handle, Folgen, Melden eines
  Kontos (das NICHTS versteckt), Auszeichnungen - und Sammlungen aus fremden
  Clips, in die kein privater Clip hineinkommt und aus denen ein
  zurückgezogener still verschwindet.

## Aufbau

| Paket | Inhalt |
|---|---|
| `format` | `.awclip`-Validator und Inhalts-Hash, Portierung von `Editor/Community/Format` der Workbench |
| `account`, `auth` | Konten mit Anmeldungen je Anbieter (`account_identity`), OAuth2 bei Discord/GitHub/Google (Browser), Device Flow + Bearer-Token (Workbench), Entwickler-Login |
| `catalog` | Upload mit Erklärung, Duplikat- und Wiederupload-Sperre, Suche, Vorschau, signierte Download-Links |
| `moderation` | Melden → sofort AUTO_HIDDEN, Takedown-Formular ohne Konto, Admin-Entscheidungen, Strikes, Benachrichtigungen |
| `profile` | Handle als Adresse, Folgen, Bio und Avatar, abgeleitete Auszeichnungen |
| `collection` | Sammlungen: eigene und fremde Clips, öffentlich oder nur über den Link |
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
| `/u.html?u=<handle>` | `user.html` | Ein Profil: Clips, Sammlungen, Folgen, Auszeichnungen. Der Handle ist die Adresse, nicht der Anzeigename - er ueberlebt eine Umbenennung. |
| `/characters.html` | `characters.html` | Die eigenen Figuren. Der Server liefert nur den Rahmen; der Inhalt liegt im Browser. |
| `/collection.html?c=<slug>` | `collection.html` | Eine Sammlung. Derselbe Aufbau wie der Katalog, nur von Hand ausgesucht. |
| `/signin.html` | `signin.html` | Die Auswahl der Anbieter und der Ort fuer Anmeldefehler. Gibt es nur einen Anbieter, fuehrt "Sign in" direkt dorthin. |
| `/me.html` | `me.html` | Das private Fach, darin "Ways to sign in": weitere Anbieter verbinden, loesen (nie den letzten). |

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
| `static/assets/card-stage.js` | Dieselbe Buehne auf den Karten des Katalogs - ein Renderer fuer alle. |
| `static/assets/viewer-ui.js` | Wiedergabe, Zeitleiste und die Sichtschalter drumherum. |
| `static/assets/viewer.js` | Das Strichmaennchen. Bleibt: als Skelettansicht und als Rueckfall ohne WebGL. |
| `static/assets/vendor/three.module.js` | three.js r186 plus GLTFLoader und SkeletonUtils, gebuendelt. |

**Vier Proportionen derselben Figur.** Rechts unten auf der Buehne steht
`Default / Tall / Short / Heavy` (Taste `P` geht die Reihe durch). Das ist
keine zweite Figur, sondern dieselbe in anderen Massen: jede Auswahl skaliert
ein paar Wurzelknochen gleichfoermig, die Kette darunter haengt daran. Damit
laesst sich die Frage beantworten, fuer die es sonst eine Modellbibliothek
braeuchte - traegt die Bewegung auch einen anderen Koerperbau? -, ohne ein
fremdes Modell und damit ohne Rechtefrage. `Default` bleibt die Vorgabe, und
Katalogkarten wie Discord-Bilder zeigen immer sie. Die Rechnung dazu steht in
`stage.js` bei `PROPORTIONS`.

Auch die **Karten** im Katalog zeigen die Figur. Eine Seite zeigt bis zu 24
davon, und so viele WebGL-Kontexte gibt kein Browser her - aber eine Seite
braucht auch keine 24 Kontexte, sondern 24 Bilder: ein Renderer zeichnet reihum
fuer jede Karte in eine eigene Kachel, und jede Karte kopiert sich ihr Bild auf
ihre 2D-Leinwand. Was ausserhalb des Bildes steht, ruht.

Der **Blickwinkel ist ueberall derselbe** (`yaw = PI - 0.55`, `pitch = 0.2`,
wachsender Pitch hebt die Kamera): Buehne, Strichmaennchen und das Bild fuer
Discord-Vorschauen (`web/ClipCard.kt`). Wer eine der drei Rechnungen anfasst,
muss an die anderen denken.

**Warum die Vorschau umgerechnet werden muss.** Die Vorschau im `.awclip` ist
auf der Figur gebacken, die der Hochladende in der Workbench ausgewaehlt hat,
und bringt deren Achsenkonvention mit. Die Umrechnung bestimmt pro Knochen eine
feste Korrektur aus der Richtung zum Kindknochen - in beiden Rigs im lokalen
Raum bekannt. Bei gleicher Konvention ist sie die Einheit und das Bild exakt.
Der Kopf von `stage.js` erklaert es im Ganzen.

### Die eigene Figur

Seit dem 21.09.2026 muss es nicht das Mannequin sein. Es gibt zwei Orte dafür,
und die Trennung ist Absicht:

**`/characters.html`** ist der Ort. Dort kommen Figuren herein (ziehen oder
auswählen), dort stehen sie als Karten mit ihrem Bild und ihren Zahlen, dort
wird eine gewählt und dort wieder vergessen. Die Seite steht in der
Navigationsleiste, weil eine Funktion, die man nur findet, wenn man schon
weiß, dass es sie gibt, keine ist.

**Die Schalterreihe an der Bühne** ist der Schalter. Rechts unten, über den
Proportionen: `Mannequin`, dann die abgelegten Figuren, dann ein Plus, das auf
die Seite führt. Dort entscheidet man, auf WEM der Clip gerade läuft - mehr
nicht. Hinzufügen und Löschen am Rand einer Bühne zu verstecken war der erste
Entwurf und der falsche.

| Datei | |
|---|---|
| `static/assets/figures.js` | Die Ablage: IndexedDB, Lesen des `extras`-Blocks, die Zahlen für die Karte, gemerkte Auswahl. |
| `static/assets/pages/characters.js` | Die Seite: Ablegen, Karten, ein Bild je Figur, wählen und vergessen. |
| `static/assets/viewer-ui.js` | Die Schalterreihe an der Bühne. |
| `static/assets/stage.js` | `parseFigure()` und `options.figure` - dieselbe Bühne, andere Figur. |

**Das Bild auf der Karte misst am Skelett, nicht am Mesh.**
`Box3.setFromObject` nimmt für eine gehäutete Haut ihren Geometrie-Kasten, und
der steht im Mesh-Raum, vor jeder Häutung: bei quantisierten Ecken liegt er
zwischen -1 und 1, während die Figur 1,74 m groß ist. Die Knochen stehen
dagegen dort, wo die Figur wirklich steht. Ein Renderer zeichnet reihum für
alle Karten, jede kopiert sich ihr Bild auf ihre eigene 2D-Leinwand - derselbe
Griff wie im Katalog.

**Die Datei bleibt hier.** Sie geht nie an den Server. Ein Mesh ist Megabyte
groß und gehört jemandem: ein gekauftes Synty- oder Mixamo-Modell auf unserem
Server wäre eine Kopie eines lizenzierten Assets, mit allem, was daran hängt.
Im Browser des Käufers ist es dieselbe Ansicht, die Unity zwei Fenster weiter
auch zeigt. Deshalb IndexedDB und kein Upload - und deshalb sieht eine
abgelegte Figur außer ihrem Besitzer niemand, auch wir nicht.

**Woher die Datei kommt.** Aus der Workbench: Figuren-Ansicht, `⋯`-Menü, *Use
my figures on the portal*, abwählen was nicht mit soll, dann **Export**. Das
schreibt je Figur ein `.glb` - Geometrie, Skelett, Haut, Basisfarbe und
-textur. Der Schreiber ist `Editor/Community/Format/AWGlb.cs`, ohne
Paketabhängigkeit; gesammelt wird aus Unitys Speicher und nicht aus der FBX,
weil eine modulare Figur (Synty Sidekick) als Datei gar nicht existiert und
weil Maßstab, Achsen und Material-Remaps in der `.meta` stehen, nicht in der
FBX.

**Was die Umrechnung braucht, steht in der Datei.** Ein `extras`-Block, den
jeder andere Betrachter ignoriert:

```json
{ "aw": 1, "name": "Starter_03", "rig": "humanoid", "pose": "tpose",
  "height": 1.78, "bones": { "...": 0.42 },
  "humanoid": { "Hips": "B_Hip", "LeftUpperArm": "B_UpperArm_L" } }
```

`humanoid` ist der Teil, ohne den nichts geht: eine Vorschau nennt ihre
Knochen `LeftUpperArm`, das Skelett in der Datei heißt vielleicht
`B_UpperArm_L`. Nur der Exporteur kennt beide Namen; im Mannequin-Fall steht
dieselbe Zuordnung als `BONE_MAP` fest in `stage.js`. Die Rechnung darüber
ändert sich nicht - die Korrektur je Knochen kommt ohnehin aus der Bindepose
des Ziels, und ob das Ziel unser Mannequin ist oder eine fremde Figur, macht
dabei keinen Unterschied.

**Drei Kleinigkeiten, die in der Bühne dafür nachgezogen wurden.** Eine
modulare Figur bringt MEHRERE Häute mit (Kopf, Körper, Haare), und jede nennt
nur die Knochen, die sie braucht - gerechnet wird deshalb auf der Vereinigung,
und die inversen Bindematrizen werden über den Knochen nachgeschlagen statt
über den Platz in einer Haut. Der Boden misst über alle Häute. Und die eigene
Figur behält ihre Materialien, während das Mannequin weiter seine zwei
bekommt.

**Die Proportionen verschwinden bei einer eigenen Figur.** `Tall` und `Heavy`
skalieren Knochen des Mannequins; eine eigene Figur HAT ihre Proportionen. Ein
Schalter, der nichts tut, ist schlimmer als keiner.

Eine Figur wieder loswerden: **Forget** auf ihrer Karte. Die Datei auf der
Platte bleibt, nur dieser Browser vergisst sie.

**Was noch nicht geht:** ein fremdes Modell hereinziehen - eine `.fbx` von
Mixamo, eine `.glb` aus Blender. Die tragen Knochennamen, aber keine
Zuordnung, und ohne die lässt sich kein Clip darauf rechnen. Der nächste
Schritt ist, die Namen zu lesen (`mixamorig:LeftArm`, `upperarm_l`,
`B_UpperArm_L` meinen dasselbe Gelenk) und nach dem Rest zu fragen.

### Generische Clips

Neben `humanoid` nimmt das Portal `generic` an - eine Tür, ein Schwanz, ein
Kranarm. Der Unterschied ist nicht die Größe der erlaubten Menge, sondern ihre
Art:

| | humanoid | generic |
|---|---|---|
| Kurvennamen | feste Liste (`HUMANOID_ATTRIBUTES`) | Regel: 1-255 Zeichen, keine Steuerzeichen, kein Leerraum am Rand |
| Knochen der Vorschau | feste Liste (`PREVIEW_BONES`, 55) | dieselbe Regel, max. 64 Zeichen, bis zu 128 Knochen |
| Kurven je Clip | 300 | 2 000 |
| Vorschau läuft auf | dem Mannequin, umgerechnet | dem Skelett, das der Clip selbst mitbringt |

**Die Kurvengrenze ist gemessen, nicht geschätzt.** 83 Clips mit
Transformkurven im Dev-Projekt der Workbench tragen im Median 47 Kurven - aber
ein Drache mit 125 Knochen trägt 1 136, und 30 % aller gemessenen Clips liegen
über den 300 des Humanoiden. Mit 300 wäre ausgerechnet der Fall ausgeschlossen,
für den es generische Clips gibt: eine Kreatur, die kein Mensch ist. Die
Speichergrenze ist die Kurvenzahl nicht - dafür stehen `MAX_KEYS_TOTAL` und
`MAX_UNCOMPRESSED_BYTES`.

**Warum das ohne Figurenbibliothek auskommt.** Ein generischer Clip lässt sich
nicht auf eine fremde Figur umrechnen - zwischen einem Türscharnier und einem
Oberschenkel gibt es keine Entsprechung. Er braucht aber auch keine: seine
Vorschau trägt `bones`, `parents` und `rest` bei sich, und das Strichmännchen
zeichnet genau das. Für ihn ist es darum nicht der Rückfall, sondern die
richtige Ansicht. Mixamos Figurenauswahl löst ein Problem, das hier gar nicht
entsteht.

Das Rig steht seit `V5` an der Fassung (`package_version.rig`) und in beiden
API-Antworten. Der Viewer entscheidet daran, ob die Figur überhaupt auftritt:
`clip.html` trägt es als `data-rig`, die Katalogkarte als `data-rig` an ihrer
Leinwand. Ohne diese Angabe bliebe nur, es am Scheitern der Umrechnung zu
merken - das fängt zwar, sieht aber aus wie ein Fehler.

> **Der Client ist mitgezogen** (Workbench `d3ce60e` und der Commit danach).
> `.awclip` ist ein geteiltes Format: `AWClipSchema.cs` und `AWClipReader.cs`
> tragen dieselbe Lockerung, sonst würde das Werkzeug ablehnen, was der Server
> annimmt. Die Testdateien liegen auf beiden Seiten gleich und kommen aus
> `make_fixtures.py` im Workbench-Repo - `invalid-rig.json` trägt jetzt
> `quadruped` statt `generic` (`generic` ist ja gültig geworden), und
> `valid-generic.json`, `invalid-generic-attribute.json` und
> `invalid-generic-bone.json` sind neu.
>
> Wie der Kurvenname gebaut ist, entscheidet allein der Client: `<Pfad>.`
> plus eine Transform-Eigenschaft (`Hinge/Panel.m_LocalRotation.y`). Der Server
> prüft ihn nur auf Länge und Zeichen - er muss ihn nicht verstehen, und der
> Viewer braucht ihn nicht, weil er das Skelett aus dem Vorschau-Block zeichnet.
>
> Der Inhalts-Hash umfasst weiterhin nur Kurvennamen und Schlüssel, nicht das
> Rig. Ein generischer Clip, dessen Kurven zufällig genau wie humanoide Muskeln
> hießen, gälte als dasselbe Werk. Das ist gewollt gelassen: den Hash zu ändern
> hieße, jeden bestehenden ungültig zu machen.

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
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | freiwillig: GitHub-OAuth-App, Redirect `.../login/oauth2/code/github`, kein Scope |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | freiwillig: Google-OAuth-Client, Redirect `.../login/oauth2/code/google`, Scope `profile` |
| `PORTAL_ADMIN_DISCORD_IDS` | Moderation, komma-getrennt: Discord-IDs als Zahl, andere als `github:<id>` / `google:<sub>` |
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
