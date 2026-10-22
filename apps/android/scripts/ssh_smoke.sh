#!/usr/bin/env bash
# SSH smoke on a device: on a clean install, a host is set up from the app's own SSH screen (its
# fingerprint shown and confirmed, the app's key logging in), then a turn's terminal command runs on
# that host and its output - a marker only the host holds - comes back in the reply (SshSmokeTest).
# The host is scripts/ssh_smoke_server.py on this machine, reached through `adb reverse`; it records
# which public key logged in, so the check below proves it was the key the app showed.
#
# Build first:  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Run from an activated test environment that has asyncssh:  source ./activate --test-extras all,asyncssh
# Same endpoint settings as scripts/smoke.sh: SMOKE_BASE_URL  SMOKE_MODEL  SMOKE_REVERSE_PORT  SMOKE_KEY  ANDROID_SERIAL
# SSH_SMOKE_PORT (2222)  SSH_SMOKE_PYTHON (default: the activated test interpreter)
set -euo pipefail

base_url=${SMOKE_BASE_URL:-http://127.0.0.1:11434/v1}
model=${SMOKE_MODEL:-gemma4:12b-it-q8_0}
key=${SMOKE_KEY:-sk-hermes-smoke-not-a-secret}
reverse_port=${SMOKE_REVERSE_PORT-11434}
ssh_port=${SSH_SMOKE_PORT:-2222}
python=${SSH_SMOKE_PYTHON:-${__HERMES_TEST_PYTHON:-python3}}
marker=ssh-marker-$(date +%s)-$RANDOM
app=com.nousresearch.hermes.debug
root=$(cd "$(dirname "$0")/.." && pwd)
apk=${SMOKE_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${SMOKE_TEST_APK:-$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}

failed=0
check() {  # check <description> <command...>: report, and remember a failure
    local what=$1; shift
    if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failed=1; fi
}

host=$(mktemp -d)
server=""
cleanup() {
    [ -z "$server" ] || kill "$server" 2>/dev/null || true
    adb reverse --remove "tcp:$ssh_port" >/dev/null 2>&1 || true
    rm -rf "$host"
}
trap cleanup EXIT
mkdir "$host/home"
printf '%s\n' "$marker" >"$host/home/hermes-marker.txt"
"$python" "$root/scripts/ssh_smoke_server.py" --port "$ssh_port" --home "$host/home" --record "$host/logged-in-key" \
    >"$host/server.out" 2>"$host/server.err" &
server=$!
for _ in $(seq 100); do grep -q '^fingerprint ' "$host/server.out" && break; sleep 0.1; done
fingerprint=$(sed -n 's/^fingerprint //p' "$host/server.out")
[ -n "$fingerprint" ] || { echo "the SSH server did not start:"; cat "$host/server.err"; exit 1; }

adb install -r "$apk" >/dev/null
adb install -r "$test_apk" >/dev/null
adb shell pm clear "$app" >/dev/null
if [ -n "$reverse_port" ]; then adb reverse "tcp:$reverse_port" "tcp:$reverse_port" >/dev/null; fi
adb reverse "tcp:$ssh_port" "tcp:$ssh_port" >/dev/null
adb logcat -c

echo "ssh smoke at $(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown): $model at $base_url," \
     "host 127.0.0.1:$ssh_port ($fingerprint) on device $(adb get-serialno)"
began=$(date +%s)
result=$(adb shell am instrument -w -r -e class com.nousresearch.hermes.android.SshSmokeTest \
    -e baseUrl "$base_url" -e model "$model" -e key "$key" \
    -e sshPort "$ssh_port" -e fingerprint "$fingerprint" -e marker "$marker" \
    "$app.test/androidx.test.runner.AndroidJUnitRunner")
echo "instrumentation finished in $(( $(date +%s) - began )) s"
adb logcat -d -s HermesSmoke:I | grep "turn after" | sed 's/^.*HermesSmoke: /  /' || true
# The chat as the test left it (SshSmokeTest saves it); SSH_SMOKE_SCREENSHOT names where it goes.
if [ -n "${SSH_SMOKE_SCREENSHOT:-}" ]; then
    adb pull "/sdcard/Android/data/$app/files/ssh-smoke.png" "$SSH_SMOKE_SCREENSHOT" >/dev/null 2>&1 \
        && echo "  screenshot $SSH_SMOKE_SCREENSHOT" || echo "  no screenshot"
fi

# OK (1 test) also follows a test that did not really pass: -1 error, -2 failure, -3 ignored, -4 assumption.
check "the ssh smoke test ran and passed" \
    sh -c 'printf "%s" "$1" | grep -q "OK (1 test)" && ! printf "%s" "$1" | grep -q "INSTRUMENTATION_STATUS_CODE: -[1234]"' _ "$result"
[ "$failed" = 0 ] || printf '%s\n' "$result" | grep -E "INSTRUMENTATION_STATUS: stack=|^\s+at |Error" | head -20

log=$(adb logcat -d)
shown_key=$(printf '%s\n' "$log" | sed -n 's/^.*HermesSmoke: public key [^ ]* \([^ ]*\).*$/\1/p' | tail -1)
check "the host was logged in to with the public key the app showed" \
    sh -c '[ -n "$1" ] && [ "$1" = "$(cat "$2" 2>/dev/null)" ]' _ "$shown_key" "$host/logged-in-key"
# The turn's log entry (SshSmokeTest) spans one logcat line per line of the reply.
turn=$(printf '%s\n' "$log" | sed -n '/HermesSmoke: turn after/,$p' | grep 'HermesSmoke: ')
check "the host's command output came back in the reply" sh -c 'printf "%s" "$1" | grep -qF "Reply(text=" && printf "%s" "$1" | grep -qF "$2"' _ "$turn" "$marker"
check "nothing on the core's stdout (python.stdout)" test "$(printf '%s\n' "$log" | grep -c 'python.stdout' || true)" = 0
check "no crash" test -z "$(adb logcat -d -b crash)"
check "no private key in the log" test "$(printf '%s\n' "$log" | grep -c 'PRIVATE KEY' || true)" = 0
check "the endpoint key is not in the log" test "$(printf '%s\n' "$log" | grep -cF "$key" || true)" = 0
# run-as must reach the app's files first, or the searches below would find nothing and pass.
check "the app's files are readable (run-as)" sh -c 'adb shell run-as "$1" ls files >/dev/null' _ "$app"
check "the host key is pinned in the app's known_hosts" \
    sh -c 'adb shell run-as "$1" cat files/.ssh/known_hosts | grep -q "^\[127.0.0.1\]:$2 "' _ "$app" "$ssh_port"
check "the app's identity is in the keystore" sh -c 'adb shell run-as "$1" test -s no_backup/secrets/TERMINAL_SSH_PRIVATE_KEY' _ "$app"
# The identity is an OpenSSH-format key (hermes_android.ssh.new_key); the keystore blob above is AES-GCM ciphertext.
check "the app's private key is in no file of the app as plaintext" \
    test -z "$(adb shell "run-as $app grep -rl 'BEGIN OPENSSH PRIVATE KEY' files cache no_backup/secrets" 2>/dev/null || true)"

exit "$failed"
