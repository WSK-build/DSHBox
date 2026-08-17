#!/usr/bin/env bash
# deploy_to_device.sh - one-shot deploy of the DSH runtime to an Android device.
#
# Usage:
#   tools/deploy_to_device.sh [--bundle <file.tar.gz>]
#
#   --bundle <file>   use an explicit Runtime Bundle (must contain the DSHapp
#                     fixes: start_dsh.sh with DSH_PERMISSION_MODE). Defaults to
#                     snapshotting the device's current runtime-current rootfs,
#                     which always carries the deployed fixes.
#
# Flow (all host-side; DSH source is never touched):
#   1. locate/verify the bundle (fix-marker check) and compute its SHA-256
#   2. transfer it into the app's files/updates (adb + run-as)
#   3. trigger the app's install flow:
#        debug build -> DevInstallReceiver broadcast (stop/install/promote/start)
#        release build -> instruct the user to use the Settings > 更新导入 UI
#   4. poll until runtime-new is promoted and the new DSH node is up with the
#      expected environment (Debian PATH + DSH_PERMISSION_MODE)
#
# Requirements: adb on PATH, device online, com.dshbox.app installed.
# Idempotent: re-running re-deploys the bundle (previous slot is preserved for
# rollback).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
    for candidate in adb "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb"; do
        if command -v "$candidate" >/dev/null 2>&1; then ADB="$candidate"; break; fi
    done
fi
[ -n "$ADB" ] || die "adb not found (install platform-tools or set ADB=/path/to/adb)"
PKG="com.dshbox.app"
BUNDLE=""
BUNDLE_SHA=""

log() { echo "[deploy] $*"; }
die() { echo "[deploy] ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --bundle) BUNDLE="$2"; shift 2 ;;
        *) die "unknown argument: $1 (usage: deploy_to_device.sh [--bundle <file.tar.gz>])" ;;
    esac
done

"$ADB" get-state >/dev/null 2>&1 || die "no adb device"

# ---------- 1. bundle source: explicit file or device snapshot ----------

if [ -n "$BUNDLE" ]; then
    [ -f "$BUNDLE" ] || die "bundle not found: $BUNDLE"
else
    log "no --bundle given; snapshotting the device's current runtime rootfs"
    BUNDLE="$ROOT_DIR/build/dshapp-runtime-debian-arm64-snapshot.tar.gz"
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/user-data'" >/dev/null 2>&1 || die "run-as failed (app installed?)"
    # proot binary path from the running instance (or the APK native dir)
    PROOT_PID=$("$ADB" shell "ps -A -o PID,NAME | grep libproot | head -1" | awk '{print $1}')
    if [ -n "$PROOT_PID" ]; then
        PROOT_BIN=$("$ADB" shell "cat /proc/$PROOT_PID/cmdline | tr '\\0' ' '" | awk '{print $1}')
    else
        PROOT_BIN=$("$ADB" shell "run-as $PKG sh -c 'ls /data/app/*/*/lib/arm64/libproot.so 2>/dev/null | head -1'" | tr -d '\r')
        [ -n "$PROOT_BIN" ] || die "cannot locate libproot.so (start the sandbox once first)"
    fi
    AD="$(dirname "$PROOT_BIN")"
    RT="/data/user/0/$PKG/files/runtime/runtime-current"
    UD="/data/user/0/$PKG/files/user-data"
    log "packing rootfs in guest (a few minutes)..."
    # NOTE: the guest's linker warnings go to stderr; pipefail + an empty
    # stdout would make `grep -v` exit 1 and kill the script, so merge stderr
    # into the pipe and tolerate grep's no-match exit (the mv below is the
    # real success check).
    "$ADB" shell "run-as $PKG sh -c 'LD_LIBRARY_PATH=$AD PROOT_LOADER=$AD/libproot-loader.so PROOT_TMP_DIR=$RT/tmp $AD/libproot.so --rootfs=$RT/debian --bind=/system --bind=/apex --bind=/proc --bind=/dev --bind=$UD:/root/projects --cwd=/root --kill-on-exit /system/bin/sh -c \"exec /usr/bin/bash -c '\''set -e; cd /; tar --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./system --exclude=./apex --exclude=./tmp --exclude=./root/projects --exclude=./root/.dsh --exclude=./root/.npm \
			--exclude=./root/.cache --exclude=./root/.local --exclude=./root/.bash_history --exclude=./root/.dsh_bashrc \
			-czf /root/projects/deploy-snapshot.tar.gz .'\''\"'" \
        2>&1 | grep -v "WARNING: linker" >/dev/null || true
    # Compute the hash WHILE the file is still visible in the guest
    # (files/updates is outside the proot bind, so hash before moving).
    BUNDLE_SHA=$("$ADB" shell "run-as $PKG sh -c 'LD_LIBRARY_PATH=$AD PROOT_LOADER=$AD/libproot-loader.so PROOT_TMP_DIR=$RT/tmp $AD/libproot.so --rootfs=$RT/debian --bind=/system --bind=/apex --bind=/proc --bind=/dev --bind=$UD:/root/projects --cwd=/root --kill-on-exit /system/bin/sh -c \"exec /usr/bin/bash -c '\''sha256sum /root/projects/deploy-snapshot.tar.gz'\''\"'" 2>&1 \
        | grep -v "WARNING: linker" | awk '{print $1}' || true)
    [ -n "$BUNDLE_SHA" ] || die "sha256 computation failed"
    "$ADB" shell "run-as $PKG sh -c 'mv files/user-data/deploy-snapshot.tar.gz files/updates/ && ls -la files/updates/deploy-snapshot.tar.gz'" >/dev/null \
        || die "moving snapshot into files/updates failed"
    log "snapshot sha256: $BUNDLE_SHA"
fi

if [ -z "$BUNDLE_SHA" ]; then
    # --bundle mode: local file -> transfer into files/updates
    BUNDLE_SHA="$(sha256sum "$BUNDLE" | awk '{print $1}')"
    # fix-marker check: the bundle must contain the DSHapp fixes. Member names
    # may carry a "./" prefix (tar -C / .), so resolve the member first.
    START_DSH_MEMBER=$(tar -tzf "$BUNDLE" | grep -m1 "opt/dshapp/start_dsh.sh$" || true)
    if [ -z "$START_DSH_MEMBER" ]; then
        die "bundle lacks opt/dshapp/start_dsh.sh; not a DSHapp runtime bundle"
    fi
    FIX_MARKER_OK=$(tar -xOzf "$BUNDLE" "$START_DSH_MEMBER" | grep -c "DSH_PERMISSION_MODE" || true)
    [ "$FIX_MARKER_OK" -ge 1 ] || die "bundle lacks the DSHapp fixes (start_dsh.sh without DSH_PERMISSION_MODE); use a freshly built bundle or omit --bundle to snapshot the device"
    log "transferring bundle ($(du -h "$BUNDLE" | awk '{print $1'})) into $PKG files/updates (can take minutes over adb)..."
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/updates'" >/dev/null 2>&1 || die "run-as failed"
    "$ADB" shell "run-as $PKG sh -c 'cat > files/updates/deploy-bundle.tar.gz'" < "$BUNDLE"
    DEV_BUNDLE="/data/user/0/$PKG/files/updates/deploy-bundle.tar.gz"
    log "transferred to $DEV_BUNDLE"
else
    # snapshot mode: the bundle is already on the device in files/updates
    DEV_BUNDLE="/data/user/0/$PKG/files/updates/deploy-snapshot.tar.gz"
fi

# ---------- 3. trigger install ----------

DEBUGGABLE=$("$ADB" shell "dumpsys package $PKG | grep -c DEBUGGABLE")
if [ "$DEBUGGABLE" -ge 1 ]; then
    # make sure the app process is up so the broadcast is delivered
    "$ADB" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1 || true
    log "broadcasting install (stop/install/promote/start)..."
    "$ADB" shell "am broadcast -n $PKG/.dev.DevInstallReceiver -a com.dshbox.app.dev.action.INSTALL_RUNTIME \
        --es bundle $DEV_BUNDLE \
        --es sha256 $BUNDLE_SHA --ez autostart true" | grep -q "result=0" || die "broadcast rejected"
else
    log "release build: open the app and use Settings > 更新导入, selecting:"
    log "  $DEV_BUNDLE  (sha256 $BUNDLE_SHA)"
fi

# ---------- 4. poll for promotion + verified DSH startup ----------

log "waiting for promotion (extraction takes minutes)..."
for i in $(seq 1 60); do
    SLOTS=$("$ADB" shell "run-as $PKG sh -c 'ls files/runtime/ 2>/dev/null | tr \"\n\" \" \"'" 2>/dev/null)
    NODE=$("$ADB" shell "ps -A -o PID,NAME | grep -w node | head -1" | awk '{print $1}')
    if ! echo "$SLOTS" | grep -q "runtime-new" && [ -n "$NODE" ]; then
        log "promoted after ~$((i * 10))s"
        break
    fi
    [ "$i" -eq 60 ] && die "timeout: promotion did not complete (see logcat DevInstallReceiver)"
    sleep 10
done

sleep 10
NODE=$("$ADB" shell "ps -A -o PID,NAME | grep -w node | head -1" | awk '{print $1}')
ENV_OK=$("$ADB" shell "run-as $PKG sh -c 'cat /proc/$NODE/environ | tr \"\\0\" \"\\n\"'" 2>/dev/null \
    | grep -c "DSH_PERMISSION_MODE=danger-full-access")
PATH_OK=$("$ADB" shell "run-as $PKG sh -c 'cat /proc/$NODE/environ | tr \"\\0\" \"\\n\"'" 2>/dev/null \
    | grep -c "PATH=/usr/local/sbin")
[ "$ENV_OK" -ge 1 ] || die "DSH node env missing DSH_PERMISSION_MODE (check logcat)"
[ "$PATH_OK" -ge 1 ] || die "DSH node env missing Debian PATH (check logcat)"

log "DEPLOY-OK: node=$NODE, DSH_PERMISSION_MODE + Debian PATH verified"
log "slots: $SLOTS (runtime-previous kept for rollback)"
