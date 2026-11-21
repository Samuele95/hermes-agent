"""The Android workflow closes the app's wheel set over the app's extras and hands it over with its proof.

A pull_request run builds at the merge commit, so the payload it assembles does not snapshot the
branch tip. A local build takes only the wheel set (with index.json and SHA256SUMS, which say what it
was built from) and assembles it with its own ``--ref HEAD``.
"""
import re
import tomllib
from pathlib import Path

from ruamel.yaml import YAML

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/android-apk.yml"

WHEEL_SET = [
    "android-build/termux/wheelhouse",
    "android-build/termux/runtime-libs",
    "android-build/termux/index.json",
    "android-build/termux/SHA256SUMS",
]


def _workflow():
    return YAML(typ="base").load(WORKFLOW.read_text(encoding="utf-8"))


def _uploads(job):
    return [step for step in job["steps"] if str(step.get("uses", "")).startswith("actions/upload-artifact@")]


def _recipe_step_index(steps):
    return next(i for i, step in enumerate(steps) if "scripts/termux/termux_build.sh" in step.get("run", ""))


def test_the_payload_job_closes_the_wheel_set_over_the_apps_extras():
    workflow = _workflow()
    extras = re.findall(r"--extra\s+(\S+)", workflow["env"]["APP_EXTRAS"])
    declared = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]["optional-dependencies"]

    assert set(extras) == {"asyncssh", "telegram", "mcp"}
    assert set(extras) <= declared.keys()
    steps = workflow["jobs"]["payload"]["steps"]
    recipe = steps[_recipe_step_index(steps)]["run"]
    assert re.search(r"termux_build\.sh .*\$APP_EXTRAS", recipe)


def test_the_payload_job_uploads_the_wheel_set_with_its_proof():
    jobs = _workflow()["jobs"]
    steps = jobs["payload"]["steps"]
    wheel_sets = [i for i, step in enumerate(steps) if step.get("with", {}).get("name") == "android-wheelhouse"]

    assert len(wheel_sets) == 1
    upload = steps[wheel_sets[0]]
    assert str(upload["uses"]).startswith("actions/upload-artifact@")
    assert upload["with"]["path"].split() == WHEEL_SET
    assert wheel_sets[0] > _recipe_step_index(steps)
    assert [step["with"]["name"] for step in _uploads(jobs["payload"])] == ["android-wheelhouse", "android-payload"]
    assert [step["with"]["name"] for step in _uploads(jobs["apk"])] == ["hermes-android-debug-apk"]


def test_every_upload_in_the_android_workflow_uses_one_pinned_action():
    pins = set(re.findall(r"actions/upload-artifact@(\S+)", WORKFLOW.read_text(encoding="utf-8")))

    assert len(pins) == 1
    assert re.fullmatch(r"[0-9a-f]{40}", pins.pop())
