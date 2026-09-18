#!/usr/bin/env bash

###############################################################################
# MotionLabs Backend SSL Initialization Script (Let's Encrypt)
#
# Zweck:
#   Erstellt einmalig ein TLS/SSL-Zertifikat über Let's Encrypt
#   mittels HTTP-01 (Webroot) Challenge.
#
# Architektur:
#   - Nginx ist der einzige öffentlich erreichbare Service (Port 80)
#   - Certbot legt Challenge-Dateien im Webroot ab
#   - Nginx liefert diese Dateien aus
#
# Pfadstruktur (Best Practice):
#   - Code / Docker Compose:   /srv/motionlabs
#   - Nginx Runtime Config:    /var/lib/motionlabs/nginx
#   - Certbot Runtime Daten:   /var/lib/motionlabs/certbot
#
# Wann ausführen:
#   - Nach erstem Server-Setup
#   - Bevor HTTPS aktiviert wird
#
###############################################################################

set -e

# --------------------------------------------------------------------
# Konfiguration
# --------------------------------------------------------------------
DOMAIN="api.playmations.com"
EMAIL="ssl-certificate@playmations.com" 

CODE_DIR="/srv/motionlabs"
RUNTIME_DIR="/var/lib/motionlabs"

NGINX_CONF_DIR="${RUNTIME_DIR}/nginx/conf.d"
CERTBOT_CONF_DIR="${RUNTIME_DIR}/certbot/conf"
CERTBOT_WEBROOT="${RUNTIME_DIR}/certbot/www"

# --------------------------------------------------------------------
# Bestätigung
# --------------------------------------------------------------------
echo "================================================"
echo " SSL Certificate Setup (Let's Encrypt)"
echo "================================================"
echo ""
echo "Domain : ${DOMAIN}"
echo "Email  : ${EMAIL}"
echo ""
read -p "Is this correct? (y/n) " -n 1 -r
echo
[[ ! $REPLY =~ ^[Yy]$ ]] && exit 1

cd "${CODE_DIR}"

# --------------------------------------------------------------------
# [1/5] Temporäre Nginx-Konfiguration für ACME
# --------------------------------------------------------------------
echo "[1/5] Creating temporary Nginx ACME config..."

cat > "${NGINX_CONF_DIR}/00-acme-temp.conf" << EOF
server {
    listen 80;
    server_name ${DOMAIN};

    # Liefert ACME Challenge Dateien für Let's Encrypt aus
    location /.well-known/acme-challenge/ {
        alias /var/www/certbot/.well-known/acme-challenge/;
        try_files \$uri =404;
    }

    # Fallback (nur für Validierung)
    location / {
        return 200 "Let's Encrypt validation";
    }
}
EOF

# --------------------------------------------------------------------
# [2/5] Nginx starten (nur HTTP)
# --------------------------------------------------------------------
echo "[2/5] Starting Nginx..."
docker compose up -d nginx
sleep 5

# --------------------------------------------------------------------
# [3/5] Zertifikat anfordern
# --------------------------------------------------------------------
echo "[3/5] Requesting certificate from Let's Encrypt..."

docker compose run --rm certbot certonly \
  --webroot \
  --webroot-path /var/www/certbot \
  --email "${EMAIL}" \
  --agree-tos \
  --no-eff-email \
  -d "${DOMAIN}"

# Prüfen ob Zertifikat existiert
if [ ! -f "${CERTBOT_CONF_DIR}/live/${DOMAIN}/fullchain.pem" ]; then
  echo "? Certificate generation failed"
  exit 1
fi

echo "? Certificate obtained successfully"

# --------------------------------------------------------------------
# [4/5] Temporäre Config entfernen
# --------------------------------------------------------------------
echo "[4/5] Cleaning up temporary config..."
rm -f "${NGINX_CONF_DIR}/00-acme-temp.conf"

# --------------------------------------------------------------------
# [5/5] Stack neu starten
# --------------------------------------------------------------------
echo "[5/5] Restarting full stack..."
docker compose down
docker compose up -d

echo ""
echo "================================================"
echo " SSL ready!"
echo " https://${DOMAIN}"
echo "================================================"