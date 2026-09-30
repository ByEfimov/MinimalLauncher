#!/bin/bash
# Выдать 6-значный код активации по коду магнитолы (офлайн, с Mac).
# Использование:  tools/license/sign.sh 373039
# Секрет лежит ВНЕ репозитория: ~/Проекты/MinimalLauncher-secret/hmac_secret.hex
set -e
SECRET_FILE="${LICENSE_SECRET:-$HOME/Проекты/MinimalLauncher-secret/hmac_secret.hex}"
[ -f "$SECRET_FILE" ] || { echo "Нет секрета: $SECRET_FILE"; exit 1; }
SECRET=$(tr -d '[:space:]' < "$SECRET_FILE")
DEV=$(echo "$1" | tr -cd '0-9')
[ ${#DEV} -eq 6 ] || { echo "Код магнитолы — 6 цифр, например 373039"; exit 1; }
# HMAC-SHA256(secret, device) → первые 8 байт → mod 1_000_000
MAC=$(printf '%s' "$DEV" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$SECRET" -binary | xxd -p -c256)
python3 - "$MAC" "$DEV" <<'PY'
import sys
mac=sys.argv[1]; dev=sys.argv[2]
n=int(mac[:16],16) % 1000000
print("%06d" % n)
print("^ код активации для магнитолы %s" % dev, file=sys.stderr)
PY
