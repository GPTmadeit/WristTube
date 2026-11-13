#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub release, with notes from the matching
# CHANGELOG.md section. Bump versionCode/versionName in wear/build.gradle.kts first.
#   tools/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
v=$(grep -oP 'versionName = "\K[^"]+' wear/build.gradle.kts)
grep -q "^## $v " CHANGELOG.md || { echo "CHANGELOG.md has no '## $v' section" >&2; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties is missing: releases must be signed with the release key" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit your changes first" >&2; exit 1; }
gh release view "v$v" >/dev/null 2>&1 && { echo "v$v is already released" >&2; exit 1; }

./gradlew -q --console=plain :wear:assembleRelease
rm -rf dist && mkdir dist
apk="dist/WristTube-watch-v$v.apk"
cp wear/build/outputs/apk/release/wear-release.apk "$apk"

# The updater refuses an APK signed by another key, so check before anyone downloads it.
want=$(keytool -list -v -keystore "$(grep -oP 'storeFile=\K.*' keystore.properties)" \
  -storepass "$(grep -oP 'storePassword=\K.*' keystore.properties)" 2>/dev/null | grep -oP 'SHA256: \K.*' | tr -d ':' | tr 'A-F' 'a-f')
got=$("$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs "$apk" | grep -oP 'SHA-256 digest: \K.*')
[ "$want" = "$got" ] || { echo "APK isn't signed with the release key ($got)" >&2; exit 1; }

(cd dist && sha256sum -- *.apk > SHA256SUMS.txt)
python3 tools/release_notes.py "$v" "$apk" > dist/notes.md
git push -q origin main
pre=""; [[ $v == *-* ]] && pre="--prerelease"
gh release create "v$v" "$apk" dist/SHA256SUMS.txt --target "$(git rev-parse HEAD)" \
  --title "WristTube v$v" --notes-file dist/notes.md $pre
gh release view "v$v" --json assets --jq '.assets[] | "\(.name) \(.size)"'
