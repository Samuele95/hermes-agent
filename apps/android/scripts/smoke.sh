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
source "$(cd "$(dirname "$0")" && pwd)/smoke_lib.sh"

install_clean

echo "smoke at $(revision): $model at $base_url on device $(adb get-serialno)"
began=$(date +%s)
result=$(adb shell am instrument -w -r -e class com.nousresearch.hermes.android.SmokeTest,com.nousresearch.hermes.android.StopSmokeTest,com.nousresearch.hermes.android.HistorySmokeTest,com.nousresearch.hermes.android.BackgroundQuestionSmokeTest \
    -e baseUrl "$base_url" -e model "$model" -e key "$key" "$runner") || true  # a failed run is the check's to report
echo "instrumentation finished in $(( $(date +%s) - began )) s"
adb logcat -d -s HermesSmoke:I | grep -E "reply after|stopped turn|resumed reply|notified" | sed 's/^.*HermesSmoke: /  /' | cut -c1-300 || true

# OK (4 tests) also follows a test that did not really pass: -1 error, -2 failure, -3 ignored, -4 assumption.
check "the smoke test ran and passed" passed 4 "$result"
[ "$failed" = 0 ] || excerpt 20 "INSTRUMENTATION_STATUS: stack=|^\s+at |Error" "$result"

log=$(adb logcat -d)
check_core_stdout "$log"
check_no_crash
check_key_not_logged "$log"
check_files_readable
check_key_in_no_file

exit "$failed"
