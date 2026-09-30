#!/bin/bash
# Офлайн-выдача кода активации с Mac, без сервера.
# Использование:  tools/license/sign.sh 8B46-EA8A-08F4
# Приватный ключ лежит ВНЕ репозитория: ~/Проекты/MinimalLauncher-secret/private.pem
set -e
KEY="${LICENSE_KEY:-$HOME/Проекты/MinimalLauncher-secret/private.pem}"
[ -f "$KEY" ] || { echo "Нет приватного ключа: $KEY"; exit 1; }
DEV=$(echo "$1" | tr 'a-f' 'A-F' | tr -cd '0-9A-F')
[ ${#DEV} -eq 12 ] || { echo "Код магнитолы должен быть 12 символов (0-9, A-F): например 8B46-EA8A-08F4"; exit 1; }
printf '%s' "$DEV" | openssl dgst -sha256 -sign "$KEY" | base64 | tr -d '\n'
echo
echo "^ код активации для $DEV — вставьте его на магнитоле" >&2
