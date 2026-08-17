#!/usr/bin/env bash
# Linux 环境下的构建脚本（不使用 Android Studio）。
# 依赖：JDK 17+、Gradle 8.11.1+、Android SDK（platforms;android-36, build-tools;36.0.0, platform-tools）。
# 用法：ANDROID_HOME=$HOME/dev/android-sdk GRADLE_BIN=$HOME/dev/tools/gradle-8.11.1/bin/gradle tools/build_linux.sh
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/dev/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ -n "${GRADLE_BIN:-}" ]; then
  GRADLE_BIN="${GRADLE_BIN}"
else
  GRADLE_BIN="$(command -v gradle || true)"
  if [ -x ./gradlew ]; then
    GRADLE_BIN="./gradlew"
  fi
fi

case "$GRADLE_BIN" in
  /mnt/*)
    echo "refusing to use Windows-host Gradle: $GRADLE_BIN" >&2
    echo "set GRADLE_BIN to a Linux-installed Gradle (e.g. ~/dev/tools/gradle-8.11.1/bin/gradle)." >&2
    exit 1
    ;;
esac

if [ ! -f local.properties ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

"$GRADLE_BIN" \
    :app:assembleDebug \
    :app:assembleRelease \
    testDebugUnitTest \
    lintDebug \
    --no-daemon
