#!/bin/bash
# CodeNDrive — reproducible build (manual aapt2/javac/d8 pipeline, no Gradle).
# Usage: ./build.sh [versionName]   (APK lands in ./out/)
#
# Prerequisites (see README.md): JDK 17 and the Android SDK (platform
# android-36 + build-tools 36.0.0). Point JAVA_HOME / ANDROID_SDK at your
# installs if they are not in the default spots below.
#
# Signing: create your own keystore once, e.g.
#   keytool -genkeypair -keystore release.keystore -alias codendrive \
#       -keyalg RSA -keysize 2048 -validity 10000
# then export KEYSTORE_PASS (and KEY_PASS if it differs) before building.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH=$JAVA_HOME/bin:$PATH
ANDROID_SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
BT=$ANDROID_SDK/build-tools/36.0.0
AJ=$ANDROID_SDK/platforms/android-36/android.jar

VERSION="${1:-1.0.0}"
OUT=out
rm -rf $OUT build-tmp
mkdir -p $OUT build-tmp/classes build-tmp/dex build-tmp/gen

# 1. Resources -> compiled + R.java
$BT/aapt2 compile --dir app/res -o build-tmp/res.zip
$BT/aapt2 link -o build-tmp/base.apk -I "$AJ" \
    --manifest app/AndroidManifest.xml \
    build-tmp/res.zip --java build-tmp/gen \
    --version-name "$VERSION" --version-code 100

# 2. Java: engine (single source of truth in engine/) + app + generated R
find engine app/src build-tmp/gen -name '*.java' > build-tmp/sources.txt
# --release 8 pins the Java 8 API surface (catches post-8 APIs at compile time)
# while letting lambdas compile; d8 desugars them for the device.
javac -encoding UTF-8 --release 8 -cp "$AJ" \
    -d build-tmp/classes @build-tmp/sources.txt

# 3. Dex
$BT/d8 --lib "$AJ" --min-api 26 --output build-tmp/dex \
    $(find build-tmp/classes -name '*.class')

# 4. Pack dex, align, sign with YOUR keystore (never commit it — see .gitignore)
(cd build-tmp/dex && jar uf ../base.apk classes.dex)
$BT/zipalign -f 4 build-tmp/base.apk build-tmp/aligned.apk
KEYSTORE="${KEYSTORE:-release.keystore}"
KEY_ALIAS="${KEY_ALIAS:-codendrive}"
$BT/apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:${KEYSTORE_PASS:?set KEYSTORE_PASS to your keystore password}" \
    --key-pass "pass:${KEY_PASS:-${KEYSTORE_PASS:-}}" \
    --out $OUT/codendrive-$VERSION.apk build-tmp/aligned.apk

$BT/apksigner verify $OUT/codendrive-$VERSION.apk
echo "BUILT: $OUT/codendrive-$VERSION.apk ($(du -h $OUT/codendrive-$VERSION.apk | cut -f1))"
