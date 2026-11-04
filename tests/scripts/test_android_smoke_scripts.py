"""The three device smoke scripts run every check and exit 1 after a failed test.

``apps/android/scripts/{smoke,restart_smoke,ssh_smoke}.sh`` drive instrumented tests on a phone and then
check its logs and files. Each runs under ``set -euo pipefail``; a failed test must still leave every
check line behind and an exit code that is the verdict (1 on any failed check, 0 otherwise, never 141).
Here they run against a fake ``adb`` on ``PATH`` that answers like a phone from canned files, no phone.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "apps" / "android"
pytestmark = pytest.mark.platforms("posix")

FAKE_ADB = f"""#!{sys.executable}
import json
import os
from pathlib import Path
import sys

state = Path(os.environ["FAKE_ADB"])
argv = sys.argv[1:]
with (state / "calls.jsonl").open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(argv) + "\\n")


def text(name):
    path = state / name
    return path.read_text(encoding="utf-8") if path.exists() else ""


def answer(out="", status=0):
    sys.stdout.write(out)
    sys.stdout.flush()
    sys.exit(status)


app = "com.nousresearch.hermes.debug"
if argv[:1] in (["install"], ["reverse"]) or argv == ["logcat", "-c"]:
    answer()
if argv[:2] == ["shell", "pm"] or argv[:3] == ["shell", "am", "force-stop"]:
    answer()
if argv == ["get-serialno"]:
    answer("FAKE0001\\n")
if argv[:3] == ["shell", "am", "instrument"]:
    test_class = argv[argv.index("class") + 1] if "class" in argv else ""
    method = test_class.split("#", 1)[1] if "#" in test_class else None
    status = text(f"instrument.{{method}}.status") if method else ""
    status = int(status.strip() or text("instrument.status").strip() or 0)
    answer(text(f"instrument.{{method}}.txt" if method else "instrument.txt"), status)
if argv == ["logcat", "-d", "-b", "crash"]:
    answer(text("crash.txt"))
if argv == ["logcat", "-d", "-s", "HermesSmoke:I"]:
    answer("".join(line for line in text("logcat.txt").splitlines(True) if "HermesSmoke" in line))
if argv == ["logcat", "-d"]:
    answer(text("logcat.txt"))
if argv == ["shell", "pidof", app]:
    answer(status=1)
if argv[:3] == ["shell", "run-as", app]:
    rest = argv[3:]
    if rest == ["ls", "files"] or rest[:2] == ["test", "-s"]:
        answer()
    if rest[:1] == ["grep"]:
        answer(status=1)
    if rest == ["cat", "files/.ssh/known_hosts"]:
        answer("[127.0.0.1]:2222 ssh-ed25519 AAAAC3fake\\n")
    if rest == ["cat", "files/.hermes/config.yaml"]:
        answer("terminal:\\n  backend: local\\n")
if len(argv) == 2 and argv[0] == "shell" and argv[1].startswith(f"run-as {{app}} grep"):
    answer(status=1)
sys.stderr.write(f"fake adb: unexpected {{argv}}\\n")
sys.exit(2)
"""

# Stands in for scripts/ssh_smoke_server.py: prints a fingerprint and records no key that logged in.
FAKE_SSH_SERVER = """#!/bin/sh
echo "fingerprint SHA256:fake"
exit 0
"""

PASSED_4 = "INSTRUMENTATION_STATUS_CODE: 0\n" * 4 + "\nOK (4 tests)\n"
PASSED_1 = "INSTRUMENTATION_STATUS_CODE: 0\n\nOK (1 test)\n"


def failure_stack(frames: int) -> str:
    lines = ["INSTRUMENTATION_STATUS: stack=java.lang.IllegalStateException: No compose hierarchies found in the app."]
    lines += [f"\tat androidx.compose.ui.test.Fake.frame{i}(Fake.kt:{i})" for i in range(frames)]
    lines += ["INSTRUMENTATION_STATUS_CODE: -2", "", "FAILURES!!!", "Tests run: 4,  Failures: 4", ""]
    return "\n".join(lines)


def early_assumption(tests: int) -> str:
    """A long run whose first test was an assumption failure (-4), the rest passing: ~250 KB, over the pipe buffer."""
    lines = ["INSTRUMENTATION_STATUS_CODE: -4"]
    lines += [f"INSTRUMENTATION_STATUS: stream=line {i} padding padding padding" for i in range(4000)]
    lines += ["INSTRUMENTATION_STATUS_CODE: 0"] * tests
    lines += ["", f"OK ({tests} test{'s' if tests != 1 else ''})", ""]
    return "\n".join(lines)


# About 150 KB of matching lines: well over the 64 KiB pipe buffer, so `head` closing early is a
# deterministic SIGPIPE for the writer, not a race.
BIG_STACK = failure_stack(3000)
SMALL_STACK = failure_stack(3)
# A crashed run: no line the failure excerpt's pattern matches.
CRASHED = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"

SMOKE_CHECKS = [
    "the smoke test ran and passed",
    "nothing on the core's stdout (python.stdout)",
    "no crash",
    "the endpoint key is not in the log",
    "the app's files are readable (run-as)",
    "the endpoint key is in no file of the app",
]
SSH_CHECKS = [
    "the ssh smoke test ran and passed",
    "the host was logged in to with the public key the app showed",
    "the host's command output came back in the reply",
    "nothing on the core's stdout (python.stdout)",
    "no crash",
    "no private key in the log",
    "the endpoint key is not in the log",
    "the app's files are readable (run-as)",
    "the host key is pinned in the app's known_hosts",
    "the terminal is back on the phone after the test (terminal.backend: local)",
    "the app's identity is in the keystore",
    "the app's private key is in no file of the app as plaintext",
]
PHASE_A = "aMemoryASkillAndAConversationAreMadeInTurns"
PHASE_B = "afterAProcessKillTheCoreStillFindsThem"
PHASE_A_CHECK = "phase A: a memory, a skill and a conversation made in turns"
PHASE_B_CHECK = "phase B: after the kill the core finds the memory, the skill and the conversation"
NOT_RUN = "phase B not run: it reads what phase A made"


class FakePhone:
    def __init__(self, tmp_path: Path):
        self.dir = tmp_path / "phone"
        self.dir.mkdir()
        tools = tmp_path / "tools"
        tools.mkdir()
        for name, body in (("adb", FAKE_ADB), ("fake-python", FAKE_SSH_SERVER)):
            tool = tools / name
            tool.write_text(body, encoding="utf-8")
            tool.chmod(0o755)
        apk = tmp_path / "app-debug.apk"
        apk.write_bytes(b"")
        env = {k: v for k, v in os.environ.items()
               if not k.startswith(("SMOKE_", "SSH_SMOKE_")) and k != "ANDROID_SERIAL"}
        self.env = dict(env, PATH=f"{tools}{os.pathsep}{env['PATH']}", FAKE_ADB=str(self.dir), TMPDIR=str(tmp_path),
                        SMOKE_APK=str(apk), SMOKE_TEST_APK=str(apk), SSH_SMOKE_PYTHON=str(tools / "fake-python"))

    def put(self, name: str, content: str) -> None:
        (self.dir / name).write_text(content, encoding="utf-8")

    def calls(self) -> list[list[str]]:
        log = self.dir / "calls.jsonl"
        return [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()] if log.exists() else []


@pytest.fixture
def fake_adb(tmp_path):
    return FakePhone(tmp_path)


def run(script: str, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["bash", str(APP / "scripts" / script)], env=env, capture_output=True, text=True,
                          encoding="utf-8", timeout=60, check=False)


def checks(stdout: str) -> list[str]:
    return [line for line in stdout.splitlines() if line.startswith(("ok   ", "FAIL "))]


def expect(verdicts: dict[str, bool], order: list[str]) -> list[str]:
    return [("ok   " if verdicts.get(what, True) else "FAIL ") + what for what in order]


def test_smoke_passes_with_every_check_ok(fake_adb):
    fake_adb.put("instrument.txt", PASSED_4)
    done = run("smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (0, expect({}, SMOKE_CHECKS)), done.stderr


def test_smoke_runs_every_check_after_a_long_failure_stack(fake_adb):
    fake_adb.put("instrument.txt", BIG_STACK)
    done = run("smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect({SMOKE_CHECKS[0]: False}, SMOKE_CHECKS)), done.stderr
    assert "INSTRUMENTATION_STATUS: stack=java.lang.IllegalStateException" in done.stdout
    assert "Fake.frame0(" in done.stdout
    assert "Fake.frame19(" not in done.stdout  # the excerpt's cap: the stack line and 19 frames


def test_smoke_runs_every_check_when_nothing_matches_the_excerpt(fake_adb):
    fake_adb.put("instrument.txt", CRASHED)
    done = run("smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect({SMOKE_CHECKS[0]: False}, SMOKE_CHECKS)), done.stderr


def test_smoke_runs_every_check_when_the_instrumentation_exits_non_zero(fake_adb):
    fake_adb.put("instrument.txt", SMALL_STACK)
    fake_adb.put("instrument.status", "1")
    done = run("smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect({SMOKE_CHECKS[0]: False}, SMOKE_CHECKS)), done.stderr


def test_smoke_still_fails_on_a_leak_after_a_passing_test(fake_adb):
    fake_adb.put("instrument.txt", PASSED_4)
    fake_adb.put("logcat.txt", "11-03 10:00:00.000  123  456 I python.stdout: hello\n")
    done = run("smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect({SMOKE_CHECKS[1]: False}, SMOKE_CHECKS)), done.stderr


SSH_FAILED = {what: False for what in SSH_CHECKS[:3]}


def test_ssh_smoke_runs_every_check_after_a_long_failure_stack(fake_adb):
    fake_adb.put("instrument.txt", BIG_STACK)
    done = run("ssh_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect(SSH_FAILED, SSH_CHECKS)), done.stderr


def test_ssh_smoke_runs_every_check_when_the_turn_never_happened(fake_adb):
    fake_adb.put("instrument.txt", SMALL_STACK)  # a short excerpt: only the missing turn line can stop the script
    done = run("ssh_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect(SSH_FAILED, SSH_CHECKS)), done.stderr


def test_restart_smoke_skips_phase_b_but_not_the_log_checks(fake_adb):
    fake_adb.put(f"instrument.{PHASE_A}.txt", SMALL_STACK)
    done = run("restart_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, [
        "FAIL " + PHASE_A_CHECK,
        "ok   nothing on the core's stdout (python.stdout)",
        "ok   no crash",
    ]), done.stderr
    assert NOT_RUN in done.stdout
    calls = fake_adb.calls()
    instrumented = [c for c in calls if c[:3] == ["shell", "am", "instrument"]]
    assert len(instrumented) == 1 and f"com.nousresearch.hermes.android.RestartSmokeTest#{PHASE_A}" in instrumented[0]
    assert not [c for c in calls if "force-stop" in c]


# The long stack: the excerpt holds one matching line here. The crash: it holds none, so grep exits 1.
@pytest.mark.parametrize("phase_b", [BIG_STACK, CRASHED], ids=["long stack", "crashed"])
def test_restart_smoke_runs_its_log_checks_after_a_failed_phase_b(fake_adb, phase_b):
    fake_adb.put(f"instrument.{PHASE_A}.txt", PASSED_1)
    fake_adb.put(f"instrument.{PHASE_B}.txt", phase_b)
    done = run("restart_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, [
        "ok   " + PHASE_A_CHECK,
        "ok   the app's process is gone",
        "FAIL " + PHASE_B_CHECK,
        "ok   nothing on the core's stdout (python.stdout)",
        "ok   no crash",
    ]), done.stderr


def test_restart_smoke_runs_every_check_when_phase_a_exits_non_zero(fake_adb):
    fake_adb.put(f"instrument.{PHASE_A}.txt", SMALL_STACK)
    fake_adb.put(f"instrument.{PHASE_A}.status", "1")
    done = run("restart_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, [
        "FAIL " + PHASE_A_CHECK,
        "ok   nothing on the core's stdout (python.stdout)",
        "ok   no crash",
    ]), done.stderr
    assert NOT_RUN in done.stdout
    assert len([c for c in fake_adb.calls() if c[:3] == ["shell", "am", "instrument"]]) == 1


def test_restart_smoke_runs_its_log_checks_when_phase_b_exits_non_zero(fake_adb):
    fake_adb.put(f"instrument.{PHASE_A}.txt", PASSED_1)
    fake_adb.put(f"instrument.{PHASE_B}.txt", SMALL_STACK)
    fake_adb.put(f"instrument.{PHASE_B}.status", "1")
    done = run("restart_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, [
        "ok   " + PHASE_A_CHECK,
        "ok   the app's process is gone",
        "FAIL " + PHASE_B_CHECK,
        "ok   nothing on the core's stdout (python.stdout)",
        "ok   no crash",
    ]), done.stderr


def test_ssh_smoke_runs_every_check_when_the_instrumentation_exits_non_zero(fake_adb):
    fake_adb.put("instrument.txt", SMALL_STACK)
    fake_adb.put("instrument.status", "1")
    done = run("ssh_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect(SSH_FAILED, SSH_CHECKS)), done.stderr


# Under pipefail, `printf | grep -q` turns grep's early exit at the -4 into a SIGPIPE (141) that `!` reads as
# "no failure status"; the checks must hold on such a run.
def test_restart_smoke_fails_phase_a_on_an_early_assumption_in_a_long_run(fake_adb):
    fake_adb.put(f"instrument.{PHASE_A}.txt", early_assumption(1))
    done = run("restart_smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, [
        "FAIL " + PHASE_A_CHECK,
        "ok   nothing on the core's stdout (python.stdout)",
        "ok   no crash",
    ]), done.stderr
    assert NOT_RUN in done.stdout


def test_smoke_fails_on_an_early_assumption_in_a_long_run(fake_adb):
    fake_adb.put("instrument.txt", early_assumption(4))
    done = run("smoke.sh", fake_adb.env)
    assert (done.returncode, checks(done.stdout)) == (1, expect({SMOKE_CHECKS[0]: False}, SMOKE_CHECKS)), done.stderr


SCRIPTS = ["smoke.sh", "restart_smoke.sh", "ssh_smoke.sh"]
DEFINES = re.compile(r"^\s*(check|passed|excerpt)\(\)", re.MULTILINE)


def test_each_script_uses_the_one_copy_of_the_checks():
    for script in SCRIPTS:
        text = (APP / "scripts" / script).read_text(encoding="utf-8")
        assert len(re.findall(r'^source .*/smoke_lib\.sh"$', text, re.MULTILINE)) == 1, script
        assert not DEFINES.findall(text), script
    lib = (APP / "scripts" / "smoke_lib.sh").read_text(encoding="utf-8")
    assert sorted(DEFINES.findall(lib)) == ["check", "excerpt", "passed"]
