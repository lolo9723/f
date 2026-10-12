#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Android SDK yolunu ANDROID_HOME ile belirtin}"
: "${JAVA_HOME:?JDK 17 yolunu JAVA_HOME ile belirtin}"
: "${RADAR_KEYSTORE:?Oran Radar imzalama yedeğindeki keystore yolunu belirtin}"
: "${RADAR_PASSWORD_FILE:?Keystore parola dosyasının yolunu belirtin}"
RADAR_BUILD_TOOLS="${RADAR_BUILD_TOOLS:-36.0.0}"
RADAR_OUTPUT="${RADAR_OUTPUT:-$PWD/Oran_Radar.apk}"
npm ci --no-audit --no-fund
npx tsc --noEmit
npx expo lint
CI=1 npx expo prebuild --platform android --no-install
(
  cd android
  ./gradlew :app:assembleRelease --max-workers=2 \
    -PreactNativeArchitectures=arm64-v8a,armeabi-v7a,x86_64
)
python3 scripts/verify_apk.py android/app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/$RADAR_BUILD_TOOLS/apksigner" sign \
  --ks "$RADAR_KEYSTORE" --ks-key-alias oranradar \
  --ks-pass "file:$RADAR_PASSWORD_FILE" \
  --out "$RADAR_OUTPUT" android/app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/$RADAR_BUILD_TOOLS/apksigner" verify --verbose "$RADAR_OUTPUT"
"$ANDROID_HOME/build-tools/$RADAR_BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$RADAR_OUTPUT"
printf 'APK: %s\n' "$RADAR_OUTPUT"
