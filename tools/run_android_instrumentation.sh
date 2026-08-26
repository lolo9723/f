#!/usr/bin/env bash
set -u

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put global stay_on_while_plugged_in 3 || true
adb shell input keyevent KEYCODE_HOME || true
sleep 2

set +e
gradle connectedDebugAndroidTest --stacktrace 2>&1 | tee /tmp/vf-instrumentation-first.log
first_rc=${PIPESTATUS[0]}
set -e

if [ "$first_rc" -eq 0 ]; then
  echo "Instrumentation PASS on first attempt."
  exit 0
fi

test_failures=$(grep -Ec 'com\.videofabrikasi\.app\..*> .*FAILED' /tmp/vf-instrumentation-first.log || true)
focus_failures=$(grep -c 'RootViewWithoutFocusException' /tmp/vf-instrumentation-first.log || true)
echo "First instrumentation attempt: test_failures=${test_failures}, focus_failures=${focus_failures}"

if [ "$test_failures" -le 0 ] || [ "$test_failures" -ne "$focus_failures" ]; then
  echo "Failure is not a pure emulator window-focus infrastructure flake; refusing retry."
  exit "$first_rc"
fi

echo "Pure RootViewWithoutFocus infrastructure flake detected; preserving attempt 1 and retrying once."
mkdir -p app/build/infra-flake-attempt1
cp /tmp/vf-instrumentation-first.log app/build/infra-flake-attempt1/console.log
cp -R app/build/reports/androidTests/connected app/build/infra-flake-attempt1/reports 2>/dev/null || true
cp -R app/build/outputs/androidTest-results/connected app/build/infra-flake-attempt1/results 2>/dev/null || true

adb shell am force-stop com.videofabrikasi.app || true
adb shell am force-stop com.videofabrikasi.app.test || true
adb shell input keyevent KEYCODE_HOME || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
sleep 2

gradle connectedDebugAndroidTest --stacktrace
