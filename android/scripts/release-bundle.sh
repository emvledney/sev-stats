#!/usr/bin/env bash
# Build the release App Bundle and sign it with the upload key for Play Console.
# Prints the version it built and writes release/silverado-ev-v<versionCode>.aab (git-ignored).
#
#   android/scripts/release-bundle.sh
#
# The upload key lives outside the repo in ~/.android-keys (back it up):
#   ~/.android-keys/silveradostats-upload.jks
#   ~/.android-keys/silveradostats-upload.properties  (storeFile/storePassword/keyAlias/keyPassword)
# Every upload needs a higher versionCode than the last one (android/app/build.gradle.kts).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
props="${SILVERADO_KEY_PROPS:-$HOME/.android-keys/silveradostats-upload.properties}"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.local/share/jdks/jdk-21* | head -1)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

prop() { grep -E "^$1=" "$props" | head -1 | cut -d= -f2-; }
store="$(prop storeFile)"; alias="$(prop keyAlias)"
STOREPASS="$(prop storePassword)"; KEYPASS="$(prop keyPassword)"
export STOREPASS KEYPASS

cd "$here"
code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)"
name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)"
app_id="$(sed -nE 's/^silverado\.applicationId=//p' gradle.properties)"
[[ -n "$code" && -n "$name" && -n "$app_id" ]] || { echo "can't read the version from app/build.gradle.kts or gradle.properties" >&2; exit 1; }
final="$here/../release/silverado-ev-v$code.aab"
[[ ! -e "$final" ]] || { echo "$final already exists; raise versionCode in app/build.gradle.kts" >&2; exit 1; }

./gradlew --quiet :app:testDebugUnitTest :app:bundleRelease
in="app/build/outputs/bundle/release/app-release.aab"
out="app/build/outputs/bundle/release/silverado-stats-signed.aab"
cp "$in" "$out"
# jarsigner prints its errors to stdout, so a failure must show what it captured.
sign_log="$("$JAVA_HOME/bin/jarsigner" -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore "$store" -storepass:env STOREPASS -keypass:env KEYPASS "$out" "$alias" 2>&1)" \
  || { echo "$sign_log" >&2; echo "signing failed" >&2; exit 1; }
# jarsigner -verify exits 0 on an unsigned file, and -strict rejects a self-signed upload key: read the verdict.
verify="$("$JAVA_HOME/bin/jarsigner" -verify "$out")" || true
grep -q '^jar verified\.' <<<"$verify" || { echo "$verify" >&2; echo "$out is not signed" >&2; exit 1; }
mkdir -p "$here/../release"
cp "$out" "$final"
echo "signed: $(realpath "$final")"
echo "  $app_id  versionCode $code  versionName $name"
