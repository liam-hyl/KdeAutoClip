#!/usr/bin/env bash
# Build, sign and verify the KdeAutoClip LSPosed module (no Gradle).
# Requires tools/bootstrap.sh to have been run once.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
VERSION="1.0.0"

# shellcheck disable=SC1091
. "$ROOT/.toolchain/env.sh"

BUILD="$ROOT/build"
DIST="$ROOT/dist"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> compile"
mapfile -t SOURCES < <(find "$ROOT/src/main/java" -name '*.java' | sort)
"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$XPOSED_API_JAR" \
  -d "$BUILD/classes" \
  "${SOURCES[@]}"

echo "==> dex (d8, min-api 29)"
"$JAVA_HOME/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BUILD_TOOLS/d8" --min-api 29 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" "$BUILD/classes.jar"
ls -l "$BUILD/dex/classes.dex"

echo "==> aapt2 link (manifest + assets)"
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -A "$ROOT/assets"

echo "==> add classes.dex"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import shutil
import sys
import zipfile

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write(dex, "classes.dex")
PY

echo "==> zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> sign"
KEYSTORE="$ROOT/tools/kdeautoclip.keystore"
if [ ! -f "$KEYSTORE" ]; then
  echo "    generating debug keystore $KEYSTORE"
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" \
    -alias kdeautoclip \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass kdeautoclip -keypass kdeautoclip \
    -dname "CN=KdeAutoClip, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi
"$BUILD_TOOLS/apksigner" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --ks "$KEYSTORE" \
  --ks-key-alias kdeautoclip \
  --ks-pass pass:kdeautoclip \
  --key-pass pass:kdeautoclip \
  --out "$DIST/KdeAutoClip-$VERSION.apk" \
  "$BUILD/aligned.apk"

echo "==> verify"
"$BUILD_TOOLS/apksigner" verify --verbose "$DIST/KdeAutoClip-$VERSION.apk"
"$BUILD_TOOLS/aapt2" dump badging "$DIST/KdeAutoClip-$VERSION.apk" | head -6
python3 - "$DIST/KdeAutoClip-$VERSION.apk" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    names = apk.namelist()
    for required in ("AndroidManifest.xml", "classes.dex", "assets/xposed_init"):
        assert required in names, "missing " + required
    print("zip entries:", len(names))
    print("xposed_init ->", apk.read("assets/xposed_init").decode().strip())
PY
echo "OK: $DIST/KdeAutoClip-$VERSION.apk"
