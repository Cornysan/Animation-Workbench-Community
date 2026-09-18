# GitHub Actions Workflows

Dieses Projekt verwendet zwei separate CI/CD Workflows für Production und Testing.

## 📋 Übersicht

```
.github/workflows/
├── ci-cd.yml        → Production Workflow (main Branch)
└── ci-cd-test.yml   → Test Workflow (alle anderen Branches)
```

## 🚀 Production Workflow (`ci-cd.yml`)

**Trigger:** Push zu `main` Branch oder manuell

**Was passiert:**
1. ✅ **Build & Test** - Gradle Build + Unit Tests
2. 🐳 **Docker Build & Push** - Image wird gebaut und zu `ghcr.io` gepusht (Tag: `latest`)
3. 🚀 **Deploy** - Automatisches Deployment auf Production Server
4. 🏥 **Health Check** - Verifiziert dass Deployment erfolgreich war

**Umgebung:** `production`
- URL: https://api.playmations.com
- Server wird über SSH erreicht (benötigt Secrets)

### Benötigte Secrets:
- `SERVER_HOST` - Server IP oder Domain
- `SERVER_USER` - SSH Username (meist `root`)
- `SERVER_SSH_KEY` - Privater SSH Key
- `SERVER_PORT` - SSH Port (optional, default: 22)

### Manueller Trigger:
```
GitHub → Actions → Production CI/CD → Run workflow
```

---

## 🧪 Test Workflow (`ci-cd-test.yml`)

**Trigger:** Push zu Test-Branches oder Pull Requests

**Branches:**
- `develop`
- `linux-docker-solution`
- `feature/**`
- `test/**`

**Was passiert:**
1. ✅ **Build & Test** - Gradle Build + Unit Tests
2. 🐳 **Docker Build & Push** - Image wird gebaut (Tag: Branch-Name)
3. 📊 **Code Quality** - Optionale Quality Checks
4. 📝 **Summary** - Zeigt Test-Anweisungen

**KEIN automatisches Deployment!** Nur Build & Push für manuelle Tests.

### Nach erfolgreichem Build:

Das Image ist verfügbar als:
```
ghcr.io/playmations/motionlabs-backend:BRANCH_NAME
```

Zum Testen auf dem Server:

```bash
# SSH zum Server
ssh root@YOUR_SERVER

# Branch auschecken
cd /opt/motionlabs
git fetch origin
git checkout BRANCH_NAME
git pull origin BRANCH_NAME

# Image pullen
docker pull ghcr.io/playmations/motionlabs-backend:BRANCH_NAME

# In docker-compose.yml ändern:
nano docker-compose.yml
# Zeile ~26: image: ghcr.io/playmations/motionlabs-backend:BRANCH_NAME

# Container starten
docker compose up -d postgres backend

# Logs prüfen
docker compose logs -f backend

# Health Check
curl http://localhost:8080/actuator/health
```

---

## 🔄 Workflow Übersicht

### Push zu Test-Branch (z.B. `linux-docker-solution`)

```
1. Push → GitHub
   ↓
2. ci-cd-test.yml triggered
   ↓
3. Build ✅ → Test ✅ → Docker Push ✅
   ↓
4. Image verfügbar: ghcr.io/.../backend:linux-docker-solution
   ↓
5. Manuelle Tests auf Server möglich
```

### Push zu `main` Branch

```
1. Push → GitHub
   ↓
2. ci-cd.yml triggered
   ↓
3. Build ✅ → Test ✅ → Docker Push ✅ (Tag: latest)
   ↓
4. Auto-Deploy auf Production Server ✅
   ↓
5. Health Check ✅
   ↓
6. Live: https://api.playmations.com
```

---

## 🎯 Typischer Workflow

### 1. Feature Development

```bash
# Neuer Feature-Branch
git checkout -b feature/new-awesome-feature

# Entwicklung...
git add .
git commit -m "feat: Add awesome feature"

# Push → ci-cd-test.yml läuft
git push origin feature/new-awesome-feature

# GitHub Actions beobachten
# https://github.com/Playmations/MotionLabs-Backend/actions
```

### 2. Testing auf Server

```bash
# Nach erfolgreichem Build auf Server testen
ssh root@YOUR_SERVER

cd /opt/motionlabs
git checkout feature/new-awesome-feature
# ... (siehe Test-Anweisungen oben)
```

### 3. Pull Request zu main

```bash
# PR erstellen
gh pr create --base main --head feature/new-awesome-feature

# Code Review...
# Tests laufen automatisch (ci-cd-test.yml)
```

### 4. Merge zu main = Production Deploy

```bash
# Nach Approval: Merge PR
# Oder lokal:
git checkout main
git merge feature/new-awesome-feature
git push origin main

# → ci-cd.yml läuft automatisch
# → Production Deployment!
```

---

## 📊 Status Badges

Füge diese zu deinem README.md hinzu:

```markdown
![Production CI/CD](https://github.com/Playmations/MotionLabs-Backend/actions/workflows/ci-cd.yml/badge.svg?branch=main)
![Test CI/CD](https://github.com/Playmations/MotionLabs-Backend/actions/workflows/ci-cd-test.yml/badge.svg)
```

---

## 🔧 Workflow Konfiguration

### Production Deploy anpassen:

Editiere `.github/workflows/ci-cd.yml`:

```yaml
environment:
  name: production
  url: https://api.playmations.com  # Deine Domain
```

### Test-Branches hinzufügen:

Editiere `.github/workflows/ci-cd-test.yml`:

```yaml
on:
  push:
    branches:
      - develop
      - linux-docker-solution
      - 'feature/**'
      - 'test/**'
      - 'YOUR-NEW-PATTERN/**'  # Hier hinzufügen
```

---

## 🐛 Troubleshooting

### Docker Build schlägt fehl:
```bash
# Lokal testen:
docker build -t test .
```

### Tests schlagen fehl:
```bash
# Lokal testen:
./gradlew clean test
```

### Deployment schlägt fehl:
```bash
# SSH Verbindung prüfen:
ssh -i /path/to/key root@SERVER_HOST

# Logs auf Server:
cd /opt/motionlabs
docker compose logs backend
```

### Image kann nicht gepullt werden:
```bash
# GitHub Package Visibility prüfen
# Repository → Packages → motionlabs-backend → Package settings
# → Danger Zone → Change visibility → Public
```

---

## 📚 Weitere Ressourcen

- [GitHub Actions Docs](https://docs.github.com/en/actions)
- [Docker Build Push Action](https://github.com/docker/build-push-action)
- [SSH Action](https://github.com/appleboy/ssh-action)

---

## ✅ Setup Checklist

### Erstes Setup:

- [ ] Repository Settings → Actions → "Read and write permissions"
- [ ] Secrets hinzufügen (SERVER_HOST, SERVER_USER, SERVER_SSH_KEY)
- [ ] `.github/workflows/ci-cd.yml` im main Branch
- [ ] `.github/workflows/ci-cd-test.yml` im main Branch
- [ ] Test-Push zu Feature-Branch
- [ ] Beobachte GitHub Actions
- [ ] Manueller Test auf Server
- [ ] Push zu main → Production Deploy

### Bei Problemen:

1. GitHub Actions Logs prüfen
2. Lokal testen (Build, Docker)
3. Server-Logs prüfen
4. Secrets verifizieren