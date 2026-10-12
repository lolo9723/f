#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Android SDK yolunu belirtin}"
: "${JAVA_HOME:?JDK 21 yolunu belirtin}"
: "${RADAR_KEYSTORE:?Lab uygulamasının keystore yolunu belirtin}"
: "${RADAR_PASSWORD_FILE:?Keystore parola dosyasının yolunu belirtin}"
: "${RADAR_BUILD_PYTHON:?Python 3.13 çalıştırılabilir dosyasının yolunu belirtin}"
test -x "$JAVA_HOME/bin/javac"
RADAR_BUILD_TOOLS="${RADAR_BUILD_TOOLS:-36.0.0}"
RADAR_KEY_ALIAS="${RADAR_KEY_ALIAS:-oranradarlab}"
RADAR_OUTPUT="${RADAR_OUTPUT:-$PWD/Oran-Radar-Lab.apk}"
RADAR_GRADLE_JVM_ARGS="${RADAR_GRADLE_JVM_ARGS:--Xmx2048m -XX:MaxMetaspaceSize=1024m}"
npm ci --no-audit --no-fund
npx tsc --noEmit
npx expo lint
node scripts/test_paper.cjs
node scripts/test_backend_control.cjs
node scripts/test_phone_bridge.cjs
node scripts/test_trade_plan.cjs
node scripts/test_score_refresh.cjs
CI=1 npx expo prebuild --platform android --no-install
(
  cd android
  ./gradlew :app:assembleRelease --no-daemon --max-workers=2 \
    "-Dorg.gradle.jvmargs=$RADAR_GRADLE_JVM_ARGS" \
    -PreactNativeArchitectures=arm64-v8a
)
python3 scripts/verify_apk.py android/app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/$RADAR_BUILD_TOOLS/apksigner" sign \
  --ks "$RADAR_KEYSTORE" --ks-key-alias "$RADAR_KEY_ALIAS" \
  --ks-pass "file:$RADAR_PASSWORD_FILE" \
  --out "$RADAR_OUTPUT" android/app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/$RADAR_BUILD_TOOLS/apksigner" verify --verbose "$RADAR_OUTPUT"
"$ANDROID_HOME/build-tools/$RADAR_BUILD_TOOLS/zipalign" -c -P 16 4 "$RADAR_OUTPUT"
python3 scripts/verify_apk.py "$RADAR_OUTPUT"
"$RADAR_BUILD_PYTHON" scripts/verify_phone_apk.py "$RADAR_OUTPUT"
printf 'APK: %s\n' "$RADAR_OUTPUT"
