#!/usr/bin/env bash
###############################################################################
# Sicherung des Community-Portals.
#
# Zwei Teile, die nur zusammen etwas wert sind:
#   - die Datenbank (Konten, Pakete, Meldungen, Protokoll)
#   - die Blobs (die hochgeladenen .awclip-Dateien und Vorschauen)
# Die Datenbank allein stellt nichts wieder her, sie kennt nur die Dateinamen.
#
# Aufruf auf dem Server:
#   /srv/aw-community/backup.sh              -> /var/backups/aw-community
#   /srv/aw-community/backup.sh /pfad/woanders
#
# Taeglich um 4 Uhr:
#   crontab -e
#   0 4 * * * /srv/aw-community/backup.sh >> /var/log/aw-community-backup.log 2>&1
###############################################################################
set -euo pipefail

DEST="${1:-/var/backups/aw-community}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%F-%H%M)"

DB_CONTAINER="aw_community_db"
BLOB_VOLUME="aw-community_blob_data"

mkdir -p "$DEST"

echo "[$(date +%F' '%T)] Sicherung nach $DEST"

# --- Datenbank -------------------------------------------------------------
# In eine Datei mit .part und erst danach umbenennen: ein abgebrochener Lauf
# soll keine halbe Datei hinterlassen, die wie eine Sicherung aussieht.
docker exec "$DB_CONTAINER" pg_dump -U awcommunity awcommunity \
  | gzip > "$DEST/db-$STAMP.sql.gz.part"
mv "$DEST/db-$STAMP.sql.gz.part" "$DEST/db-$STAMP.sql.gz"
echo "  Datenbank: $(du -h "$DEST/db-$STAMP.sql.gz" | cut -f1)"

# --- Blobs -----------------------------------------------------------------
# Das Volume gehoert einem Container mit eigenem Benutzer; ein Wegwerf-Container
# kommt ohne Rechtefrage dran.
docker run --rm \
  -v "$BLOB_VOLUME":/data:ro \
  -v "$DEST":/backup \
  alpine tar czf "/backup/blobs-$STAMP.tar.gz.part" -C /data .
mv "$DEST/blobs-$STAMP.tar.gz.part" "$DEST/blobs-$STAMP.tar.gz"
echo "  Blobs:     $(du -h "$DEST/blobs-$STAMP.tar.gz" | cut -f1)"

# --- Aufraeumen ------------------------------------------------------------
find "$DEST" -name 'db-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
find "$DEST" -name 'blobs-*.tar.gz' -mtime "+$KEEP_DAYS" -delete
find "$DEST" -name '*.part' -mtime +1 -delete

echo "[$(date +%F' '%T)] fertig, $(ls -1 "$DEST"/db-*.sql.gz | wc -l) Staende vorhanden"
