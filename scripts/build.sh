#!/usr/bin/env bash
# Build helper for the PAM Android project.
#
# The project keeps its own JDK and Gradle home inside the checkout so builds do not depend on
# the machine-wide setup (~/.gradle is not writable for this workspace).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# JDK 21 (Temurin). The Android Studio JBR shipped on this machine cannot load Gradle's
# native-platform library, so the project pins its own JDK.
JDK_HOME="${PAM_JDK_HOME:-$ROOT/.toolchain/jdk-21.0.13.1+2/Contents/Home}"
if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "JDK not found at $JDK_HOME" >&2
  echo "Download one with:" >&2
  echo "  mkdir -p '$ROOT/.toolchain' && cd '$ROOT/.toolchain'" >&2
  echo "  curl -L -o jdk21.tar.gz 'https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse'" >&2
  echo "  tar xzf jdk21.tar.gz" >&2
  exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

export GRADLE_USER_HOME="${PAM_GRADLE_USER_HOME:-$ROOT/.gradle-home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

exec "$ROOT/gradlew" \
  --project-dir "$ROOT" \
  --console=plain \
  "$@"
