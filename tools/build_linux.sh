#!/usr/bin/env bash
# Linux 环境下的构建脚本（不使用 Android Studio）。
# 依赖：JDK 21、Node.js 24、Gradle 9.5.0、Android SDK（platforms;android-36/android-37.2, build-tools;36.0.0, platform-tools）。
# 用法：ANDROID_HOME=$HOME/dev/android-sdk GRADLE_BIN=$HOME/dev/tools/gradle-9.5.0/bin/gradle tools/build_linux.sh
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
    echo "set GRADLE_BIN to a Linux-installed Gradle (e.g. ~/dev/tools/gradle-9.5.0/bin/gradle)." >&2
    exit 1
    ;;
esac

if [ ! -f local.properties ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

GRADLE_ARGS=(
    --console=plain
    --no-daemon
    --max-workers=2
    -Dorg.gradle.jvmargs=-Xmx3584m
    -Pkotlin.compiler.execution.strategy=in-process
)

"$GRADLE_BIN" :app:assembleSystemWebViewDebug :app:assembleGeckoDebug "${GRADLE_ARGS[@]}"
"$GRADLE_BIN" testDebugUnitTest "${GRADLE_ARGS[@]}"
"$GRADLE_BIN" :app:lintSystemWebViewDebug "${GRADLE_ARGS[@]}"
"$GRADLE_BIN" :app:lintGeckoDebug "${GRADLE_ARGS[@]}"
"$GRADLE_BIN" :app:assembleSystemWebViewRelease "${GRADLE_ARGS[@]}"
"$GRADLE_BIN" :app:assembleGeckoRelease "${GRADLE_ARGS[@]}"
