#!/usr/bin/env bash
# Builds a signed APK without the Android SDK, using only a JDK, Python 3 and
# three jars from Maven Central (downloaded on first run into tools/cache):
#   - org.robolectric:android-all  (Android 14 framework classes, to compile against)
#   - com.jakewharton.android.repackaged:dalvik-dx  (class files -> classes.dex)
#   - com.android.tools.build:apksig  (APK signing, scheme v2)
# Usage: tools/build_apk.sh [output.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
CACHE="$TOOLS/cache"
OUT="${1:-$ROOT/clip-merger.apk}"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT

PACKAGE=com.example.clipmerger
VERSION_CODE=1
VERSION_NAME=1.0
MIN_SDK=26
TARGET_SDK=34

M2=https://repo.maven.apache.org/maven2
ANDROID_JAR="$CACHE/android-all-14-robolectric-10818077.jar"
DX_JAR="$CACHE/dalvik-dx-16.0.1.jar"
APKSIG_JAR="$CACHE/apksig-2.3.0.jar"

fetch() {
  [ -f "$2" ] && return 0
  mkdir -p "$CACHE"
  echo "Downloading $(basename "$2")"
  for i in 1 2 3 4 5; do
    curl -sSfL -o "$2.part" "$1" && mv "$2.part" "$2" && return 0
    sleep $((i * 3))
  done
  echo "Download failed: $1" >&2
  return 1
}
fetch "$M2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar" "$ANDROID_JAR"
fetch "$M2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar" "$DX_JAR"
fetch "$M2/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar" "$APKSIG_JAR"

echo "Compiling Java"
mkdir -p "$BUILD/classes"
find "$ROOT/app/src/main/java" -name '*.java' > "$BUILD/sources.txt"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "Dexing"
java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=$MIN_SDK \
  --output="$BUILD/classes.dex" "$BUILD/classes"

echo "Compiling manifest"
python3 "$TOOLS/axml.py" "$ROOT/app/src/main/AndroidManifest.xml" "$BUILD/AndroidManifest.xml" \
  --package $PACKAGE --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --min-sdk $MIN_SDK --target-sdk $TARGET_SDK

echo "Packaging"
python3 - "$BUILD" <<'PY'
import sys, zipfile
b = sys.argv[1]
with zipfile.ZipFile(b + "/unsigned.apk", "w", zipfile.ZIP_DEFLATED) as z:
    for name in ("AndroidManifest.xml", "classes.dex"):
        info = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, open(b + "/" + name, "rb").read())
PY

echo "Signing"
mkdir -p "$BUILD/signer"
javac -nowarn -cp "$APKSIG_JAR" -d "$BUILD/signer" "$TOOLS/SignApk.java"
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED \
  -cp "$BUILD/signer:$APKSIG_JAR" SignApk "$TOOLS/debug.keystore" android debug "$BUILD/unsigned.apk" "$BUILD/signed.apk"
mv "$BUILD/signed.apk" "$OUT"
echo "Built $OUT"
