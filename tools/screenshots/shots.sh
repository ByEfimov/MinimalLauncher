#!/bin/bash
# Screenshots of the launcher without a head unit (Robolectric + Roborazzi), starting from a ready-configured state
# (see Seed in Shots.kt — no first-run screens). Usage: tools/screenshots/shots.sh [Tiles Settings …]
# Output: $SHOTS_DIR (default /tmp/shots)/<Name>.png
set -e
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${SHOT_WORK:-/tmp/minimal-drive-shot}"
export SHOTS_DIR="${SHOTS_DIR:-/tmp/shots}"; mkdir -p "$SHOTS_DIR"
mkdir -p "$WORK"
rsync -a --delete --exclude build --exclude .gradle --exclude .git "$REPO/" "$WORK/" 2>/dev/null || { rm -rf "$WORK/app/src"; cp -r "$REPO/." "$WORK/"; }
cd "$WORK"
sed -i 's/^plugins {/plugins {\n    id("io.github.takahirom.roborazzi") version "1.32.2"/' app/build.gradle.kts
cat tools/screenshots/gradle-test.kts >> app/build.gradle.kts
mkdir -p app/src/test/java/com/ravium/teyeslauncher && cp tools/screenshots/Shots.kt app/src/test/java/com/ravium/teyeslauncher/
F=""; for n in "$@"; do F="$F --tests com.ravium.teyeslauncher.S$n"; done
${GRADLE:-./gradlew} testDebugUnitTest $F --rerun -Proborazzi.test.record=true -q 2>&1 | grep -E "^e: |FAILED|What went wrong" -A3 | head -30 || true
ls "$SHOTS_DIR"
