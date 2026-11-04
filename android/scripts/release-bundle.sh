#!/usr/bin/env bash
# Build the release App Bundle and sign it with the upload key for Play Console.
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
./gradlew --quiet :app:testDebugUnitTest :app:bundleRelease
in="app/build/outputs/bundle/release/app-release.aab"
out="app/build/outputs/bundle/release/silverado-stats-signed.aab"
cp "$in" "$out"
"$JAVA_HOME/bin/jarsigner" -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore "$store" -storepass:env STOREPASS -keypass:env KEYPASS "$out" "$alias" >/dev/null
"$JAVA_HOME/bin/jarsigner" -verify "$out" >/dev/null && echo "signed: $here/$out"
grep -E "applicationId|versionCode|versionName" -m3 app/build.gradle.kts gradle.properties | sed 's/^/  /'
