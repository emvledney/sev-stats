#!/usr/bin/env bash
# Runs Gradle in a container, for a machine with Docker but no JDK or Android SDK (the Unraid server).
# No emulator: screenshots still come from a machine that has one.
#
#   android/scripts/docker-test.sh                       # unit tests + debug APK
#   android/scripts/docker-test.sh :app:testDebugUnitTest --tests '*UploadModelTest'
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ $# -gt 0 ]] || set -- :app:testDebugUnitTest :app:assembleDebug
docker volume create sev-gradle-cache >/dev/null
exec docker run --rm -v sev-gradle-cache:/root/.gradle -v "$here":/work -w /work ghcr.io/cirruslabs/android-sdk:36 \
  sh -c 'yes | sdkmanager --licenses >/dev/null 2>&1; exec ./gradlew --no-daemon --console=plain "$@"' gradle "$@"
