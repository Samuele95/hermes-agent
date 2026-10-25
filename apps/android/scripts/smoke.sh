#!/usr/bin/env bash
# Smoke test on a device: a clean install, a custom endpoint set up from the app's own screen, "ping"
# sent, a real reply back (SmokeTest), a reply being written stopped on Stop (StopSmokeTest), a past
# conversation continued from History (HistorySmokeTest), a question asked while the app is in the background
# notified and answered (BackgroundQuestionSmokeTest), then the logs and the app's files checked for leaks.
#
# Build first:  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Defaults reach an Ollama on this host through `adb reverse`; override with
#   SMOKE_BASE_URL  SMOKE_MODEL  SMOKE_REVERSE_PORT (empty: the phone reaches the URL itself)
#   SMOKE_KEY (a dummy by default: it travels the keystore path, Ollama ignores it)  ANDROID_SERIAL
set -euo pipefail

base_url=${SMOKE_BASE_URL:-http://127.0.0.1:11434/v1}
model=${SMOKE_MODEL:-gemma4:12b-it-q8_0}
key=${SMOKE_KEY:-sk-hermes-smoke-not-a-secret}
reverse_port=${SMOKE_REVERSE_PORT-11434}
app=com.nousresearch.hermes.debug
root=$(cd "$(dirname "$0")/.." && pwd)
apk=${SMOKE_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${SMOKE_TEST_APK:-$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}

failed=0
check() {  # check <description> <command...>: report, and remember a failure
    local what=$1; shift
    if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failed=1; fi
}

adb install -r "$apk" >/dev/null
adb install -r "$test_apk" >/dev/null
adb shell pm clear "$app" >/dev/null
if [ -n "$reverse_port" ]; then adb reverse "tcp:$reverse_port" "tcp:$reverse_port" >/dev/null; fi
adb logcat -c

echo "smoke: $model at $base_url on device $(adb get-serialno)"
began=$(date +%s)
result=$(adb shell am instrument -w -r -e class com.nousresearch.hermes.android.SmokeTest,com.nousresearch.hermes.android.StopSmokeTest,com.nousresearch.hermes.android.HistorySmokeTest,com.nousresearch.hermes.android.BackgroundQuestionSmokeTest \
    -e baseUrl "$base_url" -e model "$model" -e key "$key" "$app.test/androidx.test.runner.AndroidJUnitRunner")
echo "instrumentation finished in $(( $(date +%s) - began )) s"
adb logcat -d -s HermesSmoke:I | grep -E "reply after|stopped turn|resumed reply|notified" | sed 's/^.*HermesSmoke: /  /' | cut -c1-300 || true

# OK (4 tests) also follows a test that did not really pass: -1 error, -2 failure, -3 ignored, -4 assumption.
check "the smoke test ran and passed" \
    sh -c 'printf "%s" "$1" | grep -q "OK (4 tests)" && ! printf "%s" "$1" | grep -q "INSTRUMENTATION_STATUS_CODE: -[1234]"' _ "$result"
[ "$failed" = 0 ] || printf '%s\n' "$result" | grep -E "INSTRUMENTATION_STATUS: stack=|^\s+at |Error" | head -20

log=$(adb logcat -d)
check "nothing on the core's stdout (python.stdout)" test "$(printf '%s\n' "$log" | grep -c 'python.stdout' || true)" = 0
check "no crash" test -z "$(adb logcat -d -b crash)"
check "the key is not in the log" test "$(printf '%s\n' "$log" | grep -cF "$key" || true)" = 0
# run-as must reach the app's files first, or the search below would find nothing and pass.
check "the app's files are readable (run-as)" sh -c 'adb shell run-as "$1" ls files >/dev/null' _ "$app"
check "the key is in no file of the app" \
    test -z "$(adb shell run-as "$app" grep -rlF "$key" files cache no_backup/secrets 2>/dev/null || true)"

exit "$failed"
