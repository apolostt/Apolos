#!/usr/bin/env bash
# Generates a release keystore and the matching app/keystore.properties.
# Run from the android/ directory:  ./tools/generate-keystore.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE="$ROOT/apolos-release.jks"
PROPS="$ROOT/app/keystore.properties"
ALIAS="apolos"

if [ -f "$KEYSTORE" ]; then
  echo "Keystore already exists: $KEYSTORE"; exit 0
fi

read -r -s -p "Choose a keystore password: " STOREPASS; echo
KEYPASS="$STOREPASS"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$STOREPASS" -keypass "$KEYPASS" \
  -dname "CN=Apolos Shield, OU=Security, O=Apolos, C=CZ"

cat > "$PROPS" <<EOF
storeFile=apolos-release.jks
storePassword=$STOREPASS
keyAlias=$ALIAS
keyPassword=$KEYPASS
EOF

echo "Created $KEYSTORE and $PROPS"
echo "Now build:  ./gradlew assembleRelease"
echo "APK signing scheme v1+v2+v3+v4 is enabled in app/build.gradle.kts"
