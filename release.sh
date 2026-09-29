#!/bin/sh
# Выпустить новую версию: ./release.sh 1.2.0
# Меняет appVersion, коммитит, ставит тег и отправляет на GitHub — дальше APK собирает GitHub Actions,
# а магнитолы получают обновление сами.
set -e
V="$1"
if [ -z "$V" ]; then echo "Использование: ./release.sh 1.2.0"; exit 1; fi
if [ "$(uname)" = "Darwin" ]; then sed -i '' "s/^appVersion=.*/appVersion=$V/" gradle.properties
else sed -i "s/^appVersion=.*/appVersion=$V/" gradle.properties; fi
git add -A
git commit -m "Release v$V" || true
git tag "v$V"
git push origin HEAD
git push origin "v$V"
echo "Готово: https://github.com/ByEfimov/MinimalLauncher/actions — через ~5 минут релиз v$V появится в Releases."
