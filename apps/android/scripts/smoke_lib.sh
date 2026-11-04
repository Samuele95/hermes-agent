# The device smoke scripts' shared settings and checks, sourced by smoke.sh, restart_smoke.sh and ssh_smoke.sh,
# which run under `set -euo pipefail`. Settings: SMOKE_BASE_URL  SMOKE_MODEL  SMOKE_REVERSE_PORT (empty: the phone
# reaches the URL itself)  SMOKE_KEY (a dummy by default: it travels the keystore path, Ollama ignores it)
# SMOKE_APK  SMOKE_TEST_APK  ANDROID_SERIAL.

base_url=${SMOKE_BASE_URL:-http://127.0.0.1:11434/v1}
model=${SMOKE_MODEL:-gemma4:12b-it-q8_0}
key=${SMOKE_KEY:-sk-hermes-smoke-not-a-secret}
reverse_port=${SMOKE_REVERSE_PORT-11434}
app=com.nousresearch.hermes.debug
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
apk=${SMOKE_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${SMOKE_TEST_APK:-$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}

failed=0
check() {  # check <description> <command...>: report, and remember a failure
    local what=$1; shift
    if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failed=1; fi
}
# Here-strings, not `printf | grep -q`: under pipefail, grep -q's early exit makes printf's pipeline 141, and `!` would
# read that as "no failure status" (a long run with an early -4 then passed).
passed() {  # passed <tests> <instrumentation output>: OK (<tests> test[s]) with no error/failure/ignored/assumption status
    local ok="OK ($1 tests)"
    if [ "$1" = 1 ]; then ok="OK (1 test)"; fi
    grep -qF "$ok" <<<"$2" && ! grep -q "INSTRUMENTATION_STATUS_CODE: -[1234]" <<<"$2"
}
excerpt() {  # excerpt <lines> <pattern> <output>: the first matching lines; never ends the script (SIGPIPE, no match)
    printf '%s\n' "$3" | grep -E "$2" | head -"$1" || true
}
install_clean() {  # both APKs, the debug app's data cleared, the endpoint port reversed, logcat emptied
    adb install -r "$apk" >/dev/null
    adb install -r "$test_apk" >/dev/null
    adb shell pm clear "$app" >/dev/null
    if [ -n "$reverse_port" ]; then adb reverse "tcp:$reverse_port" "tcp:$reverse_port" >/dev/null; fi
    adb logcat -c
}
revision() { git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown; }

check_core_stdout() {  # check_core_stdout <log>
    check "nothing on the core's stdout (python.stdout)" test "$(printf '%s\n' "$1" | grep -c 'python.stdout' || true)" = 0
}
check_no_crash() {
    check "no crash" test -z "$(adb logcat -d -b crash)"
}
check_key_not_logged() {  # check_key_not_logged <log>
    check "the endpoint key is not in the log" test "$(printf '%s\n' "$1" | grep -cF "$key" || true)" = 0
}
check_files_readable() {
    # run-as must reach the app's files first, or a search of them would find nothing and pass.
    check "the app's files are readable (run-as)" sh -c 'adb shell run-as "$1" ls files >/dev/null' _ "$app"
}
check_key_in_no_file() {
    check "the endpoint key is in no file of the app" \
        test -z "$(adb shell run-as "$app" grep -rlF "$key" files cache no_backup/secrets 2>/dev/null || true)"
}
tally() {  # tally <output>: the run's own count line
    grep -E '^(OK \(|Tests run:)' <<<"$1" || echo "no test count (the run did not finish)"
}
