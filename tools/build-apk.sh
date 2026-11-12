#!/usr/bin/env bash
# Builds app/build/toptv-feeder.apk with the local Android SDK.
# No Gradle, no network: aapt2, javac, d8, zip, zipalign, apksigner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_TOOLS="${TVFEEDER_BUILD_TOOLS:-/home/alberto/.local/android/build-tools/34.0.0}"
ANDROID_JAR="${TVFEEDER_ANDROID_JAR:-/home/alberto/.local/android/platforms/android-34/android.jar}"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

APP="$ROOT/app"
BUILD="$APP/build"
OUT="$BUILD/toptv-feeder.apk"
# Outside $BUILD on purpose: app/build is disposable, and a rebuild with a new key
# cannot upgrade the copy already installed on the TV.
KEYSTORE="$ROOT/.keystore/debug.keystore"

die() {
    echo "error: $*" >&2
    exit 1
}

for path in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
    [ -e "$path" ] || die "missing $path"
done
for tool in javac keytool zip; do
    command -v "$tool" >/dev/null 2>&1 || die "missing $tool on PATH"
done

# Intermediates live outside the worktree so the tree only gains the APK.
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
GEN="$SCRATCH/gen"
CLASSES="$SCRATCH/classes"
DEX="$SCRATCH/dex"
mkdir -p "$BUILD" "$GEN" "$CLASSES" "$DEX" "$(dirname "$KEYSTORE")"

echo "==> aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$SCRATCH/res.zip"

echo "==> aapt2 link"
"$AAPT2" link \
    -o "$SCRATCH/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --min-sdk-version 28 \
    --target-sdk-version 28 \
    --java "$GEN" \
    "$SCRATCH/res.zip"

echo "==> javac"
find "$APP/src" "$GEN" -name '*.java' | sort > "$SCRATCH/sources.txt"
javac -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -d "$CLASSES" \
    @"$SCRATCH/sources.txt"

echo "==> d8"
mapfile -t CLASS_FILES < <(find "$CLASSES" -name '*.class' | sort)
[ "${#CLASS_FILES[@]}" -gt 0 ] || die "javac produced no classes"
"$D8" --release --min-api 28 --lib "$ANDROID_JAR" --output "$DEX" "${CLASS_FILES[@]}"
[ -f "$DEX/classes.dex" ] || die "d8 produced no classes.dex"

echo "==> package"
cp "$SCRATCH/base.apk" "$SCRATCH/unsigned.apk"
(cd "$DEX" && zip -q "$SCRATCH/unsigned.apk" classes.dex)

echo "==> zipalign"
"$ZIPALIGN" -f -p 4 "$SCRATCH/unsigned.apk" "$SCRATCH/aligned.apk"

echo "==> keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -alias tvfeeder \
        -storepass tvfeeder \
        -keypass tvfeeder \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=TV Feeder" \
        >/dev/null
    echo "    created $KEYSTORE"
fi

echo "==> apksigner"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias tvfeeder \
    --ks-pass pass:tvfeeder \
    --key-pass pass:tvfeeder \
    --out "$OUT" \
    "$SCRATCH/aligned.apk"
"$APKSIGNER" verify "$OUT"

echo "==> verify"
BADGING="$("$AAPT2" dump badging "$OUT")"
for expected in \
    "package: name='com.alberto.tvfeeder'" \
    "sdkVersion:'28'" \
    "targetSdkVersion:'28'" \
    "uses-permission: name='android.permission.INTERNET'" \
    "uses-permission: name='android.permission.READ_TV_LISTINGS'" \
    "uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'" \
    "uses-permission: name='com.android.providers.tv.permission.READ_EPG_DATA'" \
    "uses-permission: name='com.android.providers.tv.permission.WRITE_EPG_DATA'" \
    "launchable-activity: name='com.alberto.tvfeeder.MainActivity'" \
    "leanback-launchable-activity: name='com.alberto.tvfeeder.MainActivity'"; do
    case "$BADGING" in
        *"$expected"*) ;;
        *) die "APK is missing: $expected" ;;
    esac
done
case "$BADGING" in
    *ACCESS_ALL_EPG_DATA*) die "APK requests ACCESS_ALL_EPG_DATA (S4)" ;;
esac
MANIFEST="$("$AAPT2" dump xmltree "$OUT" --file AndroidManifest.xml)"
for expected in \
    'android:name(0x01010003)=".BootReceiver"' \
    'android:name(0x01010003)="android.intent.action.BOOT_COMPLETED"' \
    'android:name(0x01010003)="android.intent.action.QUICKBOOT_POWERON"'; do
    case "$MANIFEST" in
        *"$expected"*) ;;
        *) die "manifest is missing: $expected" ;;
    esac
done

echo "==> done"
ls -l "$OUT"
sha256sum "$OUT"
