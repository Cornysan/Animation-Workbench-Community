# GitHub Actions

Zwei Workflows:

- `ci-cd.yml` baut und testet bei jedem Push auf jeden Branch (unten).
- `uptime.yml` fragt alle zehn Minuten `www.playmations.com` und
  `/actuator/health`. Schlaegt das dreimal hintereinander fehl, scheitert der
  Lauf - und GitHub schickt dafuer von sich aus eine Mail an die Person, die
  die cron-Zeile zuletzt geaendert hat. Mit dem Secret `UPTIME_DISCORD_WEBHOOK`
  kommt zusaetzlich eine Discord-Nachricht.

## ci-cd.yml

Er laeuft bei jedem Push auf jeden Branch.

| Schritt | Was |
|---|---|
| Build & Test | Gradle-Build mit JDK 21, Testbericht in der GitHub-Oberflaeche |
| Docker Build & Push | nur bei Push, nicht bei Pull Requests - Image nach `ghcr.io/cornysan/animation-workbench-community` |
| Summary | Zusammenfassung im Lauf |

**Marken am Image:** der Branchname (`main`) und `<branch>-<sha>`
(`main-49061b8`). Es gibt **kein `latest`** - wer eine feste Fassung
festhalten will, nimmt die SHA-Marke.

Das Paket ist oeffentlich; der Server zieht es ohne Anmeldung.

## Ausrollen passiert hier nicht

Ein Push nach `main` baut ein Image, mehr nicht. Auf den Server kommt es von
Hand:

```bash
ssh linux 'cd /srv/aw-community && docker compose pull && docker compose up -d'
```

Das ist Absicht. Ein Deploy-Job braeuchte SSH-Zugangsdaten als Secrets in
einem **oeffentlichen** Repository, und ein Portal mit fremden Uploads soll
nicht bei jedem Push durchgereicht werden. Der frueher hier stehende Job zeigte
ohnehin auf einen Branch und ein Verzeichnis, die es beide nicht mehr gibt.

Der ganze Weg auf den Server steht in `server/DEPLOY.md`.
