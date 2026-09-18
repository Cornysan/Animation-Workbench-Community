#!/usr/bin/env bash

###############################################################################
# MotionLabs Backend  Server Bootstrap Script
#
# Zweck:
#   Einmaliges Initial-Setup eines frischen Linux-Servers für das
#   MotionLabs Backend. Bereitet den Server für Docker-basierte
#   Deployments mit sauberer Trennung von Code und Runtime-Daten vor.
#
# Zielstruktur:
#   - Git / Compose / Configs:   /srv/motionlabs
#   - Laufzeitdaten (nginx, SSL): /var/lib/motionlabs
#
# Was das Script macht:
#   1. Installiert grundlegende Systempakete
#   2. Konfiguriert Git + SSH (GitHub Deploy Key)
#   3. Installiert Docker Engine
#   4. Installiert Docker Compose Plugin
#   5. Klont das Backend-Repository nach /srv/motionlabs
#   6. Erstellt eine .env Datei (falls nicht vorhanden)
#   7. Legt Runtime-Verzeichnisse unter /var/lib/motionlabs an
#
# Voraussetzungen:
#   - Ubuntu 24.04 (oder kompatibel)
#   - Root-Zugriff
#   - SSH Deploy Key unter:
#       /root/.ssh/motionlabs_key
#
# Wann ausführen:
#   - Neuer Server
#   - Frische VM / Bare-Metal Installation
#
###############################################################################

set -e

echo "================================================"
echo " MotionLabs Backend - Server Bootstrap"
echo "================================================"
echo ""

# Farben (nur Ausgabe)
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# --------------------------------------------------------------------
# Konfiguration
# --------------------------------------------------------------------
GITHUB_REPOSITORY_OWNER="playmations"
GITHUB_REPO="MotionLabs-Backend"
GITHUB_SSH="git@github.com:${GITHUB_REPOSITORY_OWNER}/${GITHUB_REPO}.git"

# Pfade (Best Practice)
CODE_DIR="/srv/motionlabs"
RUNTIME_DIR="/var/lib/motionlabs"

SSH_KEY="/root/.ssh/motionlabs_key"

# --------------------------------------------------------------------
# Root-Check
# --------------------------------------------------------------------
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}Please run this script as root${NC}"
  exit 1
fi

# --------------------------------------------------------------------
# [1/7] Base Packages
# --------------------------------------------------------------------
echo -e "${GREEN}[1/7] Installing base packages...${NC}"
apt update
apt install -y \
  ca-certificates \
  curl \
  gnupg \
  git \
  openssh-client

# --------------------------------------------------------------------
# [2/7] SSH Configuration (existing Deploy Key)
# --------------------------------------------------------------------
echo -e "${GREEN}[2/7] Configuring SSH...${NC}"

if [ ! -f "$SSH_KEY" ]; then
  echo -e "${RED}ERROR: SSH key not found at ${SSH_KEY}${NC}"
  exit 1
fi

chmod 600 "$SSH_KEY"

cat > /root/.ssh/config << EOF
Host github.com
    HostName github.com
    User git
    IdentityFile ${SSH_KEY}
    IdentitiesOnly yes
EOF

chmod 600 /root/.ssh/config
ssh-keyscan github.com >> /root/.ssh/known_hosts 2>/dev/null

# Git immer über SSH nutzen
git config --global url."git@github.com:".insteadOf "https://github.com/"

# --------------------------------------------------------------------
# [3/7] Docker Engine
# --------------------------------------------------------------------
echo -e "${GREEN}[3/7] Installing Docker...${NC}"
if ! command -v docker &>/dev/null; then
  curl -fsSL https://get.docker.com | sh
else
  echo -e "${YELLOW}Docker already installed${NC}"
fi

systemctl enable docker
systemctl start docker

# --------------------------------------------------------------------
# [4/7] Docker Compose Plugin
# --------------------------------------------------------------------
echo -e "${GREEN}[4/7] Installing Docker Compose plugin...${NC}"
if ! docker compose version &>/dev/null; then
  apt install -y docker-compose-plugin
fi

# --------------------------------------------------------------------
# [5/7] Clone Repository to /srv
# --------------------------------------------------------------------
echo -e "${GREEN}[5/7] Preparing code directory...${NC}"
mkdir -p "$CODE_DIR"
cd "$CODE_DIR"

if [ -z "$(ls -A "$CODE_DIR")" ]; then
  echo -e "${GREEN}Cloning MotionLabs Backend repository...${NC}"
  git clone "$GITHUB_SSH" .
else
  echo -e "${YELLOW}Directory not empty  skipping clone${NC}"
fi

# --------------------------------------------------------------------
# [6/7] Environment File
# --------------------------------------------------------------------
echo -e "${GREEN}[6/7] Creating .env file (if missing)...${NC}"
if [ ! -f .env ]; then
cat > .env << EOF
# MotionLabs Backend Environment

DB_PASSWORD=CHANGE_ME

GITHUB_REPOSITORY_OWNER=${GITHUB_REPOSITORY_OWNER}
GITHUB_REPOSITORY=${GITHUB_REPO}

# SPRING_PROFILES_ACTIVE=production
EOF
else
  echo -e "${YELLOW}.env already exists${NC}"
fi

# --------------------------------------------------------------------
# [7/7] Runtime Directories (/var/lib)
# --------------------------------------------------------------------
echo -e "${GREEN}[7/7] Creating runtime directories...${NC}"

mkdir -p \
  ${RUNTIME_DIR}/nginx/conf.d \
  ${RUNTIME_DIR}/certbot/conf \
  ${RUNTIME_DIR}/certbot/www

echo ""
echo -e "${GREEN}================================================${NC}"
echo -e "${GREEN} Bootstrap completed successfully!${NC}"
echo -e "${GREEN}================================================${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Edit ${CODE_DIR}/.env"
echo "2. Review docker-compose.yml (paths ? /var/lib/motionlabs)"
echo "3. Configure Nginx configs under ${RUNTIME_DIR}/nginx"
echo "4. Run init-ssl.sh"
echo "5. docker compose up -d"
echo ""
echo -e "${GREEN}Code directory:${NC} ${CODE_DIR}"
echo -e "${GREEN}Runtime directory:${NC} ${RUNTIME_DIR}"
echo -e "${GREEN}SSH key used:${NC} ${SSH_KEY}"
echo -e "${GREEN}Repo:${NC} https://github.com/${GITHUB_REPOSITORY_OWNER}/${GITHUB_REPO}"
echo ""
echo -e "${GREEN}All done.${NC}"