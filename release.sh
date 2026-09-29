#!/bin/sh
# Релизы выходят сами: любой push в main → GitHub Actions собирает APK и публикует релиз.
# Этот скрипт — просто «закоммитить всё и отправить»:  ./release.sh "что изменилось"
set -e
MSG="${1:-Обновление}"
git add -A
git commit -m "$MSG" || true
git push origin HEAD
echo "Готово: https://github.com/ByEfimov/MinimalLauncher/actions — через ~5 минут новый релиз появится в Releases."
