#!/usr/bin/env bash
# Restart smoke on a device: on a clean install, turns make a memory, a skill and a conversation
# (RestartSmokeTest phase A); the app is killed; in a new process the core finds all three (phase B);
# a failed phase A skips phase B; the log checks run either way.
#
# Build first:  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Same endpoint settings as scripts/smoke.sh: SMOKE_BASE_URL  SMOKE_MODEL  SMOKE_REVERSE_PORT  SMOKE_KEY  ANDROID_SERIAL
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/smoke_lib.sh"

token=${SMOKE_TOKEN:-$(date +%s)}
phase() {  # phase <test method>: run one RestartSmokeTest method in a fresh app process
    adb shell am instrument -w -r -e class "com.nousresearch.hermes.android.RestartSmokeTest#$1" \
        -e baseUrl "$base_url" -e model "$model" -e key "$key" -e token "$token" "$runner"
}

install_clean

echo "restart smoke at $(revision): $model at $base_url on device $(adb get-serialno), token $token"
began=$(date +%s)
# Under pipefail, an excerpt (head's SIGPIPE, or grep with no match) or a failed run must not end the script.
made=$(phase aMemoryASkillAndAConversationAreMadeInTurns) || true
check "phase A: a memory, a skill and a conversation made in turns" passed 1 "$made"
if [ "$failed" = 0 ]; then
    adb shell am force-stop "$app"
    check "the app's process is gone" test -z "$(adb shell pidof "$app" || true)"
    found=$(phase afterAProcessKillTheCoreStillFindsThem) || true
    check "phase B: after the kill the core finds the memory, the skill and the conversation" passed 1 "$found"
    [ "$failed" = 0 ] || excerpt 5 "INSTRUMENTATION_STATUS: stack=|AssertionError" "$found"
else
    excerpt 5 "INSTRUMENTATION_STATUS: stack=|AssertionError" "$made"
    echo "phase B not run: it reads what phase A made"
fi
echo "both phases in $(( $(date +%s) - began )) s"
adb logcat -d -s HermesSmoke:I | grep "turn after" | sed 's/^.*HermesSmoke: /  /' | cut -c1-220 || true

log=$(adb logcat -d)
check_core_stdout "$log"
check_no_crash
exit "$failed"
