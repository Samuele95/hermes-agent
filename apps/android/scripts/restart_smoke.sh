#!/usr/bin/env bash
# Restart smoke on a device: on a clean install, turns make a memory, a skill and a conversation
# (RestartSmokeTest phase A); the app is killed; in a new process the core finds all three (phase B).
#
# Build first:  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Same endpoint settings as scripts/smoke.sh: SMOKE_BASE_URL  SMOKE_MODEL  SMOKE_REVERSE_PORT  SMOKE_KEY  ANDROID_SERIAL
set -euo pipefail

base_url=${SMOKE_BASE_URL:-http://127.0.0.1:11434/v1}
model=${SMOKE_MODEL:-gemma4:12b-it-q8_0}
key=${SMOKE_KEY:-sk-hermes-smoke-not-a-secret}
reverse_port=${SMOKE_REVERSE_PORT-11434}
token=${SMOKE_TOKEN:-$(date +%s)}
app=com.nousresearch.hermes.debug
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
root=$(cd "$(dirname "$0")/.." && pwd)
apk=${SMOKE_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${SMOKE_TEST_APK:-$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}

failed=0
check() {  # check <description> <command...>: report, and remember a failure
    local what=$1; shift
    if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failed=1; fi
}
passed() {  # passed <instrumentation output>: OK (1 test) with no error/failure/ignored/assumption status
    printf "%s" "$1" | grep -q "OK (1 test)" && ! printf "%s" "$1" | grep -q "INSTRUMENTATION_STATUS_CODE: -[1234]"
}
phase() {  # phase <test method>: run one RestartSmokeTest method in a fresh app process
    adb shell am instrument -w -r -e class "com.nousresearch.hermes.android.RestartSmokeTest#$1" \
        -e baseUrl "$base_url" -e model "$model" -e key "$key" -e token "$token" "$runner"
}

adb install -r "$apk" >/dev/null
adb install -r "$test_apk" >/dev/null
adb shell pm clear "$app" >/dev/null
if [ -n "$reverse_port" ]; then adb reverse "tcp:$reverse_port" "tcp:$reverse_port" >/dev/null; fi
adb logcat -c

echo "restart smoke at $(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown): $model at $base_url on device $(adb get-serialno), token $token"
began=$(date +%s)
made=$(phase aMemoryASkillAndAConversationAreMadeInTurns)
check "phase A: a memory, a skill and a conversation made in turns" passed "$made"
[ "$failed" = 0 ] || { printf '%s\n' "$made" | grep -E "INSTRUMENTATION_STATUS: stack=|AssertionError" | head -5; exit 1; }

adb shell am force-stop "$app"
check "the app's process is gone" test -z "$(adb shell pidof "$app" || true)"

found=$(phase afterAProcessKillTheCoreStillFindsThem)
check "phase B: after the kill the core finds the memory, the skill and the conversation" passed "$found"
[ "$failed" = 0 ] || printf '%s\n' "$found" | grep -E "INSTRUMENTATION_STATUS: stack=|AssertionError" | head -5
echo "both phases in $(( $(date +%s) - began )) s"
adb logcat -d -s HermesSmoke:I | grep "turn after" | sed 's/^.*HermesSmoke: /  /' | cut -c1-220 || true

log=$(adb logcat -d)
check "nothing on the core's stdout (python.stdout)" test "$(printf '%s\n' "$log" | grep -c 'python.stdout' || true)" = 0
check "no crash" test -z "$(adb logcat -d -b crash)"
exit "$failed"
