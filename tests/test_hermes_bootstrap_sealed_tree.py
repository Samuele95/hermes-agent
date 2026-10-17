"""Importing the agent from a sealed tree is a library import, not a launch.

An embedding host (a mobile app that ships the source without ``.git``/``pyproject.toml``, no PM
install stamp, and its own interpreter) imports ``run_agent`` in-process. ``hermes_bootstrap`` runs at
that import; for such a tree it must take its inert branches: no ``os.execv`` relaunch, no
``SystemExit``, the host's ``HERMES_HOME`` honoured and temp files kept under it. These hold today
only through file conditions (``hermes_cli.venv_sync.prepare_launch``,
``pm.environments.activate_dependencies``); this test pins them.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

PROGRAM = textwrap.dedent("""
    import json, os, sys
    with open(sys.argv[1], "a", encoding="utf-8") as starts:
        starts.write("start\\n")  # a relaunch re-runs this program and writes it twice
    import run_agent  # noqa: F401
    from hermes_constants import get_hermes_home
    print(json.dumps({"root": str(sys.modules["hermes_bootstrap"]._root), "home": str(get_hermes_home()),
                      "tmpdir": os.environ.get("TMPDIR"), "executable": sys.executable}))
""")


def test_run_agent_imports_in_process_from_a_sealed_tree(tmp_path):
    sealed = tmp_path / "sealed"
    sealed.mkdir()
    # Only the bootstrap is copied: its own location is the tree root it inspects. The packages it
    # and run_agent import resolve through PYTHONPATH from the real checkout.
    shutil.copyfile(REPO / "hermes_bootstrap.py", sealed / "hermes_bootstrap.py")
    home = tmp_path / "home"
    starts = tmp_path / "starts.log"
    env = {k: os.environ[k] for k in ("PATH", "SYSTEMROOT", "WINDIR") if k in os.environ}
    env.update({"HOME": str(tmp_path), "USERPROFILE": str(tmp_path), "HERMES_HOME": str(home),
                "PYTHONPATH": os.pathsep.join([str(sealed), str(REPO)]), "PYTHONDONTWRITEBYTECODE": "1"})

    child = subprocess.run([sys.executable, "-c", PROGRAM, str(starts)], cwd=str(tmp_path), env=env,
                           capture_output=True, text=True, timeout=180, check=False)

    assert child.returncode == 0, child.stderr
    assert starts.read_text(encoding="utf-8") == "start\n"
    report = json.loads(child.stdout.splitlines()[-1])
    assert report == {"root": str(sealed.resolve()), "home": str(home),
                      "tmpdir": str(home / "cache" / "scratch"), "executable": sys.executable}
