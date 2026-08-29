#!/usr/bin/env bash
set -euo pipefail

test -s FINAL.mp4 || { echo "FINAL.mp4 missing before Android verification"; exit 20; }

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put global stay_on_while_plugged_in 3 || true

gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace

APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$APP_APK"
test -s "$TEST_APK"

adb install -r "$APP_APK"
adb install -r "$TEST_APK"

TARGET_DIR="/sdcard/Android/data/com.videofabrikasi.app/files"
adb shell mkdir -p "$TARGET_DIR"
adb push FINAL.mp4 "$TARGET_DIR/FINAL.mp4"
adb shell ls -l "$TARGET_DIR/FINAL.mp4"

set +e
adb shell am instrument -w -r \
  -e class com.videofabrikasi.app.LiveFinalArtifactTest \
  com.videofabrikasi.app.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee android-live-media-instrumentation.txt
rc=${PIPESTATUS[0]}
set -e

if [ "$rc" -ne 0 ]; then
  echo "Real Android media verification failed."
  exit "$rc"
fi

grep -q "OK (1 test)" android-live-media-instrumentation.txt || {
  echo "Instrumentation did not report exactly one passing live-media test."
  exit 21
}

api_level="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
bytes="$(stat -c%s FINAL.mp4)"
cat > android_media_certificate.json <<JSON
{
  "success": true,
  "framework": "Android MediaMetadataRetriever + MediaExtractor",
  "api_level": ${api_level:-0},
  "source": "exact Kaggle FINAL.mp4 downloaded by live E2E job",
  "bytes": ${bytes},
  "expected_width": 1080,
  "expected_height": 1920,
  "expected_video_mime": "video/avc",
  "expected_audio_mime": "audio/mp4a-latm",
  "instrumentation": "com.videofabrikasi.app.LiveFinalArtifactTest",
  "tests_passed": 1
}
JSON

echo "ANDROID_LIVE_FINAL_MEDIA_PASS API=$api_level bytes=$bytes"
