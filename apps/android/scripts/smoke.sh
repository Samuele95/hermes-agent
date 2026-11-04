#!/usr/bin/env bash
# Smoke test on a device: a clean install, then every @Smoke test except the three that own a script or a
# manual step (SshSmokeTest: ssh_smoke.sh; RestartSmokeTest: restart_smoke.sh; GatewayAcceptanceTest: the
# CP6g acceptance, by hand), chosen by the runner's annotation filter, so a new chat smoke test runs with no
# edit here. Each sets up a custom endpoint from the app's own screen and runs real turns on it. The run
# passes only on the runner's own "OK (N tests)" for the N @Test methods the sources hold; then the logs and
# the app's files are checked for leaks.
#
# Build first:  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Defaults reach an Ollama on this host through `adb reverse`; override with
#   SMOKE_BASE_URL  SMOKE_MODEL  SMOKE_REVERSE_PORT (empty: the phone reaches the URL itself)
#   SMOKE_KEY (a dummy by default: it travels the keystore path, Ollama ignores it)  ANDROID_SERIAL
#   SMOKE_ONLY (comma-separated class names, for a quick run: SMOKE_ONLY=RetrySmokeTest,CompressSmokeTest)
#   SMOKE_TEST_SOURCES (the directory of androidTest Kotlin sources the tests are counted from; default the app's)
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/smoke_lib.sh"

pkg=com.nousresearch.hermes.android
# @Smoke tests that need more than an endpoint, run elsewhere: SshSmokeTest (ssh_smoke.sh: its own host),
# RestartSmokeTest (restart_smoke.sh: a kill between its phases), GatewayAcceptanceTest (CP6g, by hand: it leaves a job).
not_here=SshSmokeTest,RestartSmokeTest,GatewayAcceptanceTest
sources=${SMOKE_TEST_SOURCES:-$root/app/src/androidTest/java/com/nousresearch/hermes/android}
own_tests() {  # "<class> <@Test methods>" for each @Smoke class this script runs (one class per file, named after it)
    local file name
    for file in "$sources"/*.kt; do
        grep -q '^@Smoke$' "$file" || continue
        name=$(basename "$file" .kt)
        case ",$not_here," in *",$name,"*) continue ;; esac
        echo "$name $(grep -cE '^[[:space:]]*@Test([^[:alnum:]]|$)' "$file" || true)"
    done
}

owned=$(own_tests)
picked=$owned
if [ -n "${SMOKE_ONLY:-}" ]; then
    own_names=" $(cut -d' ' -f1 <<<"$owned" | tr '\n' ' ')"
    IFS=, read -ra wanted <<<"$SMOKE_ONLY"
    for name in "${wanted[@]}"; do
        case "$own_names" in *" $name "*) ;; *) echo "SMOKE_ONLY: $name is not a smoke test smoke.sh runs" >&2; exit 2 ;; esac
    done
    picked=$(while read -r name tests; do
        case ",$SMOKE_ONLY," in *",$name,"*) echo "$name $tests" ;; esac
    done <<<"$owned")
fi
count=0
names=""
while read -r name tests; do
    [ -n "$name" ] || continue
    count=$(( count + tests ))
    names=${names:+$names,}$name
done <<<"$picked"
[ "$count" -gt 0 ] || { echo "no smoke test to run in $sources" >&2; exit 2; }
if [ -n "${SMOKE_ONLY:-}" ]; then
    selection=(-e class "$pkg.${names//,/,$pkg.}")
else
    selection=(-e annotation "$pkg.Smoke" -e notClass "$pkg.${not_here//,/,$pkg.}")
fi

install_clean

echo "smoke at $(revision): $count tests ($names); $model at $base_url on device $(adb get-serialno)"
began=$(date +%s)
result=$(adb shell am instrument -w -r "${selection[@]}" -e baseUrl "$base_url" -e model "$model" -e key "$key" \
    "$runner") || true  # a failed run is the check's to report
echo "instrumentation finished in $(( $(date +%s) - began )) s"
echo "the run: $(tally "$result")"
adb logcat -d -s HermesSmoke:I | grep 'HermesSmoke' | sed 's/^.*HermesSmoke: /  /' | cut -c1-300 || true

# OK (N tests) also follows a test that did not really pass: -1 error, -2 failure, -3 ignored, -4 assumption.
check "the smoke test ran and passed" passed "$count" "$result"
[ "$failed" = 0 ] || excerpt 20 "INSTRUMENTATION_STATUS: stack=|^\s+at |Error" "$result"

log=$(adb logcat -d)
check_core_stdout "$log"
check_no_crash
check_key_not_logged "$log"
check_files_readable
check_key_in_no_file

exit "$failed"
