#!/usr/bin/env bash
# Installs the Android SDK pieces this project needs into ~/Android/Sdk (no sudo).
# Running this installs Google SDK packages, which are covered by the
# Android Software Development Kit License Agreement
# (https://developer.android.com/studio/terms). Run it yourself, or tell
# Claude it may run it for you.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.local/share/jdks/jdk-21* | head -1)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
A="$ANDROID_HOME/cmdline-tools/latest/bin/android"

"$A" sdk install \
  platform-tools \
  emulator \
  "platforms/android-36" \
  "build-tools/36.0.0" \
  "system-images/android-34-ext9/android-automotive/x86_64" \
  "system-images/android-35-ext15/android-automotive/x86_64"

# Android 12L (API 32) matches trucks still on the SQBR builds.
"$A" sdk install "system-images/android-32/android-automotive-playstore/x86_64" || \
  echo "note: no API 32 automotive image in this channel; API 34/35 images are enough to start"

echo
echo "Installed. Add to your shell config (fish):"
echo "  set -gx JAVA_HOME $JAVA_HOME"
echo "  set -gx ANDROID_HOME $ANDROID_HOME"
echo "  fish_add_path \$ANDROID_HOME/platform-tools \$ANDROID_HOME/emulator"
