#!/usr/bin/env bash
# Generate a dev release keystore and keystore.properties for local signed builds.
# Production keystore must be created and backed up offline by the project owner.
set -euo pipefail

KEYSTORE_FILE="${KEYSTORE_FILE:-$HOME/.android/dshapp-release.jks}"
ALIAS="${ALIAS:-dshapp}"
STORE_PASS="${STORE_PASS:-CHANGE-ME-ON-PUBLISH}"
KEY_PASS="${KEY_PASS:-$STORE_PASS}"
PROPS_FILE="$(dirname "$0")/../keystore.properties"

mkdir -p "$(dirname "$KEYSTORE_FILE")"
if [ ! -f "$KEYSTORE_FILE" ]; then
    keytool -genkeypair -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=DSHapp Dev, OU=Dev, O=DSHapp, L=Local, S=Local, C=CN"
fi

cat > "$PROPS_FILE" <<EOF
storeFile=$KEYSTORE_FILE
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
echo "keystore: $KEYSTORE_FILE"
echo "properties: $PROPS_FILE"
