#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
BUILD="$ROOT/.build"
DIST="$ROOT/dist"
SDK=${ANDROID_HOME:-"$HOME/Android"}
TOOLS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -1)
ANDROID_JAR=$(find "$SDK/platforms" -mindepth 2 -maxdepth 2 -name android.jar 2>/dev/null | sort | tail -1)

[ -n "$TOOLS" ] && [ -x "$TOOLS/aapt2" ] && [ -x "$TOOLS/d8" ] && [ -x "$TOOLS/apksigner" ] || {
    echo "Android SDK build-tools were not found under $SDK" >&2
    exit 1
}
[ -f "$ANDROID_JAR" ] || { echo "Android SDK platform android.jar was not found" >&2; exit 1; }

VERSION=$(sed -n 's/package-version=//p' "$ROOT/nemesis/NEMESIS.data")
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/package/app" "$DIST"

javac --release 8 -cp "$ANDROID_JAR" -d "$BUILD/classes" $(find "$ROOT/app/src" -name '*.java' | sort)
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --min-api 19 --lib "$ANDROID_JAR" --output "$BUILD/dex" "$BUILD/classes.jar"
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$ROOT/app/AndroidManifest.xml" \
    --min-sdk-version 19 --target-sdk-version 19 -o "$BUILD/base.apk"
cp "$BUILD/base.apk" "$BUILD/app-unsigned.apk"
(cd "$BUILD/dex" && zip -q -X -j "$BUILD/app-unsigned.apk" classes.dex)

KEY=${APP_KEY:-"$BUILD/development-key.p12"}
PASS=${APP_KEY_PASS:-development}
if [ ! -f "$KEY" ]; then
    keytool -genkeypair -keystore "$KEY" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" \
        -alias iphone-pan -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=iPhone Bluetooth PAN development" >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$KEY" --ks-pass "pass:$PASS" --min-sdk-version 19 \
    --v2-signing-enabled false --v3-signing-enabled false \
    --out "$BUILD/package/app/IPhoneBluetoothPan.apk" "$BUILD/app-unsigned.apk"

cp "$ROOT/nemesis/NEMESIS.data" "$BUILD/package/"
cp -R "$ROOT/nemesis/scripts" "$ROOT/nemesis/svc" "$BUILD/package/"
python3 "$ROOT/tools/make_package.py" "$BUILD/package" "$DIST/iphone.bluetooth.pan-$VERSION.zip"

mkdir -p "$BUILD/usb/packages"
cp "$ROOT/usb/README.txt" "$BUILD/usb/README.txt"
printf '[nemesis]\nversion=1.0\ntype=install\npath=packages/iphone.bluetooth.pan-%s.zip\n' "$VERSION" > "$BUILD/usb/NEMESIS.data"
cp "$DIST/iphone.bluetooth.pan-$VERSION.zip" "$BUILD/usb/packages/"
python3 - "$BUILD/usb" "$DIST/iphone.bluetooth.pan-$VERSION-usb.zip" <<'PY'
import pathlib, sys, zipfile
source, output = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(source.rglob("*")):
        if p.is_file(): z.write(p, p.relative_to(source).as_posix())
PY

shasum -a 256 "$DIST/iphone.bluetooth.pan-$VERSION.zip" "$DIST/iphone.bluetooth.pan-$VERSION-usb.zip"
